-- =============================================================================
-- Control plane (shared cluster, schemas `public` + `ai`).
-- Holds the tenant registry, datasource routing table, identities and the shared
-- PGVector store. Tenant ledger data never lives here; see db/migration/tenant.
--
-- Flyway placeholders:
--   shared_jdbc_url, shared_db_username, shared_db_secret_ref, embedding_dimensions
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE SCHEMA IF NOT EXISTS ai;

CREATE OR REPLACE FUNCTION public.touch_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

-- -----------------------------------------------------------------------------
-- Physical database targets. AbstractRoutingDataSource keys its HikariCP pools
-- by tenant_datasources.id. kind = SHARED is the multi-schema cluster; kind =
-- ISOLATED is a dedicated database owned by exactly one tenant.
-- -----------------------------------------------------------------------------
CREATE TABLE public.tenant_datasources
(
    id            UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    name          VARCHAR(63) NOT NULL UNIQUE,
    kind          VARCHAR(16) NOT NULL CHECK (kind IN ('SHARED', 'ISOLATED')),
    jdbc_url      TEXT        NOT NULL CHECK (jdbc_url LIKE 'jdbc:postgresql://%'),
    username      VARCHAR(63) NOT NULL,
    -- Pointer to the credential (env:VAR, vault:path, ...). Never a plaintext password.
    secret_ref    TEXT        NOT NULL CHECK (secret_ref ~ '^(env|vault|file):.+'),
    pool_max_size INTEGER     NOT NULL DEFAULT 10 CHECK (pool_max_size BETWEEN 1 AND 200),
    pool_min_idle INTEGER     NOT NULL DEFAULT 1,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tenant_datasources_pool_ck CHECK (pool_min_idle BETWEEN 0 AND pool_max_size),
    -- Target of the composite FK from tenants that ties tier to datasource kind.
    CONSTRAINT tenant_datasources_id_kind_uk UNIQUE (id, kind)
);

CREATE TRIGGER tenant_datasources_touch
    BEFORE UPDATE
    ON public.tenant_datasources
    FOR EACH ROW
EXECUTE FUNCTION public.touch_updated_at();

INSERT INTO public.tenant_datasources (name, kind, jdbc_url, username, secret_ref, pool_max_size, pool_min_idle)
VALUES ('shared-primary', 'SHARED', '${shared_jdbc_url}', '${shared_db_username}', '${shared_db_secret_ref}', 30, 5);

-- -----------------------------------------------------------------------------
-- Tenant registry.
-- -----------------------------------------------------------------------------
CREATE TABLE public.tenants
(
    id            UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    slug          VARCHAR(48)  NOT NULL UNIQUE CHECK (slug ~ '^[a-z][a-z0-9_]{2,47}$'),
    display_name  VARCHAR(200) NOT NULL,
    tier          VARCHAR(16)  NOT NULL DEFAULT 'SHARED' CHECK (tier IN ('SHARED', 'ISOLATED')),
    datasource_id UUID         NOT NULL,
    -- Postgres identifier for the tenant schema; strict pattern makes it safe to
    -- interpolate into `SET search_path` after quote_ident().
    schema_name   VARCHAR(63)  NOT NULL UNIQUE CHECK (schema_name ~ '^t_[a-z0-9_]{3,60}$'),
    status        VARCHAR(16)  NOT NULL DEFAULT 'PROVISIONING'
        CHECK (status IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'OFFBOARDED')),
    base_currency CHAR(3)      NOT NULL DEFAULT 'USD' CHECK (base_currency ~ '^[A-Z]{3}$'),
    settings      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- SHARED tenants must sit on a SHARED datasource, ISOLATED on an ISOLATED one.
    CONSTRAINT tenants_datasource_fk FOREIGN KEY (datasource_id, tier)
        REFERENCES public.tenant_datasources (id, kind)
);

-- A dedicated database belongs to exactly one tenant.
CREATE UNIQUE INDEX tenants_isolated_datasource_uk ON public.tenants (datasource_id) WHERE tier = 'ISOLATED';
CREATE INDEX tenants_status_idx ON public.tenants (status);

CREATE TRIGGER tenants_touch
    BEFORE UPDATE
    ON public.tenants
    FOR EACH ROW
EXECUTE FUNCTION public.touch_updated_at();

-- -----------------------------------------------------------------------------
-- Identities (Keycloak is the source of truth for credentials) and their
-- per-tenant memberships. A user may belong to several tenants, which drives the
-- workspace switcher; the active tenant must be one of the JWT's tenant claims.
-- -----------------------------------------------------------------------------
CREATE TABLE public.users
(
    id            UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    idp_subject   VARCHAR(255) NOT NULL UNIQUE, -- Keycloak `sub`
    email         VARCHAR(320) NOT NULL,
    display_name  VARCHAR(200),
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX users_email_uk ON public.users (lower(email));

CREATE TRIGGER users_touch
    BEFORE UPDATE
    ON public.users
    FOR EACH ROW
EXECUTE FUNCTION public.touch_updated_at();

CREATE TABLE public.tenant_memberships
(
    tenant_id  UUID        NOT NULL REFERENCES public.tenants (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES public.users (id) ON DELETE CASCADE,
    role       VARCHAR(32) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'ACCOUNTANT', 'AUDITOR', 'VIEWER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id)
);

CREATE INDEX tenant_memberships_user_idx ON public.tenant_memberships (user_id);

-- -----------------------------------------------------------------------------
-- Shared PGVector store, column-compatible with Spring AI's PgVectorStore
-- (id, content, metadata, embedding; initialize-schema=false). Isolation is by
-- metadata filter on tenant_id, backed here by a generated NOT NULL column with a
-- real FK so no embedding can exist without a valid owning tenant.
-- -----------------------------------------------------------------------------
CREATE TABLE ai.vector_store
(
    id         UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    content    TEXT        NOT NULL,
    metadata   JSONB       NOT NULL,
    embedding  public.vector(${embedding_dimensions}) NOT NULL,
    tenant_id  UUID        NOT NULL GENERATED ALWAYS AS ((metadata ->> 'tenant_id')::uuid) STORED
        REFERENCES public.tenants (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX vector_store_embedding_hnsw ON ai.vector_store
    USING hnsw (embedding public.vector_cosine_ops) WITH (m = 16, ef_construction = 64);
-- Serves Spring AI's `metadata::jsonb @@ '<jsonpath>'` filter expressions.
CREATE INDEX vector_store_metadata_gin ON ai.vector_store USING gin (metadata jsonb_path_ops);
CREATE INDEX vector_store_tenant_idx ON ai.vector_store (tenant_id);
