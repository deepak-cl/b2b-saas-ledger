-- =============================================================================
-- Per-tenant ledger schema. Flyway runs this once per tenant with the tenant
-- schema as default schema (shared cluster) or inside a dedicated database
-- (ISOLATED tier). Objects are created unqualified so they land in the tenant
-- schema; every function pins `search_path FROM CURRENT` so triggers can never
-- resolve tables of another tenant, whatever the caller's search_path is.
--
-- Money: NUMERIC(20,4), always positive, side given by `direction` (D/C).
-- Balances are stored debit-positive: balance = debit_total - credit_total.
--
-- Custom SQLSTATEs (mapped to API error codes by the service):
--   LG001 unbalanced journal entry      LG002 journal entry has < 2 lines
--   LG003 ledger record is immutable     LG004 journal entry already sealed
--   LG005 accounting period closed       LG006 insufficient funds (overdraft guard)
--   LG007 accounting period not opened
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

-- -----------------------------------------------------------------------------
-- Chart of accounts
-- -----------------------------------------------------------------------------
CREATE TABLE accounts
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code           VARCHAR(32)  NOT NULL UNIQUE,
    name           VARCHAR(200) NOT NULL,
    type           VARCHAR(16)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    -- Explicit (not derived from type) so contra accounts are expressible.
    normal_balance CHAR(1)      NOT NULL CHECK (normal_balance IN ('D', 'C')),
    currency       CHAR(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    parent_id      BIGINT REFERENCES accounts (id),
    category       VARCHAR(64), -- e.g. CLOUD, PAYROLL; used by analytics and AI tools
    allow_negative BOOLEAN      NOT NULL DEFAULT TRUE,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    external_ref   VARCHAR(128), -- XBRL concept, PaySim account name, ...
    metadata       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Target of ledger_entries' composite FK: a line's currency must equal its account's.
    CONSTRAINT accounts_id_currency_uk UNIQUE (id, currency)
);

CREATE INDEX accounts_type_idx ON accounts (type);
CREATE INDEX accounts_category_idx ON accounts (category) WHERE category IS NOT NULL;
CREATE INDEX accounts_parent_idx ON accounts (parent_id) WHERE parent_id IS NOT NULL;
CREATE UNIQUE INDEX accounts_external_ref_uk ON accounts (external_ref) WHERE external_ref IS NOT NULL;

-- -----------------------------------------------------------------------------
-- Accounting periods (month granularity). Every posting takes FOR SHARE on its
-- period row and closing a period UPDATEs it, so a close waits for in-flight
-- postings and later postings see CLOSED. Rows are created together with the
-- ledger partitions; posting into a period without a row is rejected.
-- -----------------------------------------------------------------------------
CREATE TABLE accounting_periods
(
    period_start DATE PRIMARY KEY CHECK (period_start = date_trunc('month', period_start)::date),
    status       VARCHAR(8)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    closed_at    TIMESTAMPTZ,
    closed_by    UUID,
    CONSTRAINT accounting_periods_closed_ck CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

-- -----------------------------------------------------------------------------
-- Journal entries (transaction headers) and their lines.
-- -----------------------------------------------------------------------------
CREATE TABLE journal_entries
(
    id                UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    entry_no          BIGINT       NOT NULL GENERATED ALWAYS AS IDENTITY UNIQUE,
    idempotency_key   VARCHAR(128) NOT NULL UNIQUE,
    effective_date    DATE         NOT NULL,
    posted_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    description       VARCHAR(500) NOT NULL,
    source            VARCHAR(32)  NOT NULL
        CHECK (source IN ('API', 'SEC_EDGAR', 'PAYSIM', 'RECONCILIATION', 'REVERSAL', 'SYSTEM')),
    reverses_entry_id UUID UNIQUE REFERENCES journal_entries (id),
    external_ref      VARCHAR(128),
    created_by        UUID, -- control-plane users.id (may live in another database: no FK)
    metadata          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- Target of the lines' composite FK, which pins line.effective_date to the header's.
    CONSTRAINT journal_entries_id_date_uk UNIQUE (id, effective_date),
    CONSTRAINT journal_entries_reversal_ck CHECK ((source = 'REVERSAL') = (reverses_entry_id IS NOT NULL))
);

CREATE INDEX journal_entries_effective_date_idx ON journal_entries (effective_date);
CREATE INDEX journal_entries_posted_at_brin ON journal_entries USING brin (posted_at);
CREATE INDEX journal_entries_source_ref_idx ON journal_entries (source, external_ref) WHERE external_ref IS NOT NULL;

CREATE TABLE ledger_entries
(
    id               BIGINT        NOT NULL GENERATED ALWAYS AS IDENTITY,
    journal_entry_id UUID          NOT NULL,
    line_no          SMALLINT      NOT NULL CHECK (line_no > 0),
    effective_date   DATE          NOT NULL,
    account_id       BIGINT        NOT NULL,
    direction        CHAR(1)       NOT NULL CHECK (direction IN ('D', 'C')),
    amount           NUMERIC(20, 4) NOT NULL CHECK (amount > 0),
    currency         CHAR(3)       NOT NULL,
    memo             VARCHAR(500),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id, effective_date),
    CONSTRAINT ledger_entries_line_uk UNIQUE (journal_entry_id, line_no, effective_date),
    CONSTRAINT ledger_entries_journal_fk FOREIGN KEY (journal_entry_id, effective_date)
        REFERENCES journal_entries (id, effective_date),
    CONSTRAINT ledger_entries_account_fk FOREIGN KEY (account_id, currency)
        REFERENCES accounts (id, currency)
) PARTITION BY RANGE (effective_date);

-- Covering index for balance / statement aggregation: index-only scans per account and date range.
CREATE INDEX ledger_entries_account_date_idx ON ledger_entries (account_id, effective_date) INCLUDE (direction, amount);

-- Safety net only: postings require an opened period, which
-- ledger_ensure_partitions() creates together with the matching partition.
CREATE TABLE ledger_entries_default PARTITION OF ledger_entries DEFAULT;

CREATE FUNCTION ledger_ensure_partitions(p_from DATE, p_to DATE) RETURNS INTEGER
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
DECLARE
    m       DATE := date_trunc('month', p_from)::date;
    created INTEGER := 0;
    part    TEXT;
BEGIN
    WHILE m <= p_to
        LOOP
            part := format('ledger_entries_%s', to_char(m, 'YYYYMM'));
            IF to_regclass(part) IS NULL THEN
                EXECUTE format('CREATE TABLE %I PARTITION OF ledger_entries FOR VALUES FROM (%L) TO (%L)',
                               part, m, (m + INTERVAL '1 month')::date);
                created := created + 1;
            END IF;
            INSERT INTO accounting_periods (period_start) VALUES (m) ON CONFLICT DO NOTHING;
            m := (m + INTERVAL '1 month')::date;
        END LOOP;
    RETURN created;
END;
$$;

SELECT ledger_ensure_partitions((date_trunc('month', current_date) - INTERVAL '24 months')::date,
                                (date_trunc('month', current_date) + INTERVAL '12 months')::date);

-- -----------------------------------------------------------------------------
-- Running balances (one row per account) and monthly rollups. Maintained only by
-- the statement-level trigger on ledger_entries, in the posting transaction.
-- -----------------------------------------------------------------------------
CREATE TABLE account_balances
(
    account_id    BIGINT PRIMARY KEY REFERENCES accounts (id),
    debit_total   NUMERIC(24, 4) NOT NULL DEFAULT 0 CHECK (debit_total >= 0),
    credit_total  NUMERIC(24, 4) NOT NULL DEFAULT 0 CHECK (credit_total >= 0),
    balance       NUMERIC(24, 4) GENERATED ALWAYS AS (debit_total - credit_total) STORED,
    version       BIGINT         NOT NULL DEFAULT 0,
    last_entry_at TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE account_period_balances
(
    account_id   BIGINT         NOT NULL REFERENCES accounts (id),
    period_start DATE           NOT NULL,
    debit_total  NUMERIC(24, 4) NOT NULL DEFAULT 0,
    credit_total NUMERIC(24, 4) NOT NULL DEFAULT 0,
    net_change   NUMERIC(24, 4) GENERATED ALWAYS AS (debit_total - credit_total) STORED,
    line_count   BIGINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, period_start)
);

CREATE INDEX account_period_balances_period_idx ON account_period_balances (period_start) INCLUDE (account_id, net_change);

CREATE FUNCTION accounts_init_balance() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
BEGIN
    INSERT INTO account_balances (account_id) VALUES (NEW.id);
    RETURN NULL;
END;
$$;

CREATE TRIGGER accounts_init_balance
    AFTER INSERT
    ON accounts
    FOR EACH ROW
EXECUTE FUNCTION accounts_init_balance();

-- -----------------------------------------------------------------------------
-- Tamper-evident hash chain. Each sealed journal entry gets the next link:
--   entry_hash = sha256(prev_hash || canonical(header, lines))
-- The single-row head is locked at seal time, which serialises commits per
-- tenant for the duration of the seal (microseconds), not the whole transaction.
-- -----------------------------------------------------------------------------
CREATE TABLE ledger_chain_head
(
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    seq       BIGINT NOT NULL,
    last_hash BYTEA  NOT NULL
);

INSERT INTO ledger_chain_head (seq, last_hash)
VALUES (0, sha256(convert_to('genesis:' || current_schema(), 'UTF8')));

CREATE TABLE ledger_hash_chain
(
    seq              BIGINT PRIMARY KEY,
    journal_entry_id UUID        NOT NULL UNIQUE REFERENCES journal_entries (id),
    prev_hash        BYTEA       NOT NULL,
    entry_hash       BYTEA       NOT NULL UNIQUE,
    sealed_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION journal_entry_canonical(p_journal_id UUID) RETURNS TEXT
    LANGUAGE sql
    STABLE
    SET search_path FROM CURRENT AS
$$
SELECT concat_ws('|', j.id, j.idempotency_key, j.effective_date, j.source, j.description,
                 coalesce(j.reverses_entry_id::text, ''),
                 (SELECT string_agg(concat_ws(':', l.line_no, l.account_id, l.direction, l.amount, l.currency),
                                    ';' ORDER BY l.line_no)
                  FROM ledger_entries l
                  WHERE l.journal_entry_id = j.id
                    AND l.effective_date = j.effective_date))
FROM journal_entries j
WHERE j.id = p_journal_id;
$$;

-- Deferred to COMMIT: validates double-entry invariants once all lines exist,
-- then appends the entry to the hash chain (which also "seals" it).
CREATE FUNCTION journal_entry_seal() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
DECLARE
    v_lines    INTEGER;
    v_currency CHAR(3);
    v_head     ledger_chain_head%ROWTYPE;
    v_hash     BYTEA;
BEGIN
    SELECT count(*)
    INTO v_lines
    FROM ledger_entries
    WHERE journal_entry_id = NEW.id
      AND effective_date = NEW.effective_date;

    IF v_lines < 2 THEN
        RAISE EXCEPTION 'journal entry % has % line(s); at least 2 required', NEW.id, v_lines
            USING ERRCODE = 'LG002';
    END IF;

    SELECT currency
    INTO v_currency
    FROM ledger_entries
    WHERE journal_entry_id = NEW.id
      AND effective_date = NEW.effective_date
    GROUP BY currency
    HAVING sum(amount) FILTER (WHERE direction = 'D') IS DISTINCT FROM sum(amount) FILTER (WHERE direction = 'C')
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'journal entry % is unbalanced in %: debits <> credits', NEW.id, v_currency
            USING ERRCODE = 'LG001';
    END IF;

    SELECT * INTO v_head FROM ledger_chain_head FOR UPDATE;
    v_hash := sha256(v_head.last_hash || convert_to(journal_entry_canonical(NEW.id), 'UTF8'));

    INSERT INTO ledger_hash_chain (seq, journal_entry_id, prev_hash, entry_hash)
    VALUES (v_head.seq + 1, NEW.id, v_head.last_hash, v_hash);

    UPDATE ledger_chain_head SET seq = v_head.seq + 1, last_hash = v_hash;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entries_seal
    AFTER INSERT
    ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION journal_entry_seal();

-- Returns the first chain link whose stored hash does not match a recomputation
-- (no rows = chain intact).
CREATE FUNCTION ledger_verify_chain()
    RETURNS TABLE
            (
                seq              BIGINT,
                journal_entry_id UUID
            )
    LANGUAGE sql
    STABLE
    SET search_path FROM CURRENT AS
$$
SELECT c.seq, c.journal_entry_id
FROM ledger_hash_chain c
         LEFT JOIN ledger_hash_chain p ON p.seq = c.seq - 1
WHERE c.entry_hash <> sha256(c.prev_hash || convert_to(journal_entry_canonical(c.journal_entry_id), 'UTF8'))
   OR (p.seq IS NOT NULL AND c.prev_hash <> p.entry_hash)
ORDER BY c.seq
LIMIT 1;
$$;

-- -----------------------------------------------------------------------------
-- Line guards: no lines into sealed entries (i.e. committed in an earlier
-- transaction) or into periods that are closed or were never opened.
-- -----------------------------------------------------------------------------
CREATE FUNCTION ledger_entries_guard() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
DECLARE
    v_status VARCHAR(8);
BEGIN
    IF EXISTS (SELECT 1 FROM ledger_hash_chain WHERE journal_entry_id = NEW.journal_entry_id) THEN
        RAISE EXCEPTION 'journal entry % is sealed; post a reversal instead', NEW.journal_entry_id
            USING ERRCODE = 'LG004';
    END IF;

    SELECT status
    INTO v_status
    FROM accounting_periods
    WHERE period_start = date_trunc('month', NEW.effective_date)::date
        FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'accounting period % is not opened', to_char(NEW.effective_date, 'YYYY-MM')
            USING ERRCODE = 'LG007';
    ELSIF v_status = 'CLOSED' THEN
        RAISE EXCEPTION 'accounting period % is closed', to_char(NEW.effective_date, 'YYYY-MM')
            USING ERRCODE = 'LG005';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_entries_guard
    BEFORE INSERT
    ON ledger_entries
    FOR EACH ROW
EXECUTE FUNCTION ledger_entries_guard();

-- -----------------------------------------------------------------------------
-- Balance maintenance: one statement-level pass per INSERT (cheap for batch
-- loads). Balance rows are locked in ascending account_id order, the same order
-- the service uses for its SELECT ... FOR UPDATE, so concurrent postings cannot
-- deadlock on each other.
-- -----------------------------------------------------------------------------
CREATE FUNCTION ledger_entries_apply_balances() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path FROM CURRENT AS
$$
DECLARE
    v_account BIGINT;
BEGIN
    PERFORM 1
    FROM account_balances b
    WHERE b.account_id IN (SELECT DISTINCT n.account_id FROM new_lines n)
    ORDER BY b.account_id
        FOR UPDATE;

    UPDATE account_balances b
    SET debit_total   = b.debit_total + d.debits,
        credit_total  = b.credit_total + d.credits,
        version       = b.version + 1,
        last_entry_at = now(),
        updated_at    = now()
    FROM (SELECT n.account_id,
                 coalesce(sum(n.amount) FILTER (WHERE n.direction = 'D'), 0) AS debits,
                 coalesce(sum(n.amount) FILTER (WHERE n.direction = 'C'), 0) AS credits
          FROM new_lines n
          GROUP BY n.account_id) d
    WHERE b.account_id = d.account_id;

    INSERT INTO account_period_balances AS p (account_id, period_start, debit_total, credit_total, line_count)
    SELECT n.account_id,
           date_trunc('month', n.effective_date)::date,
           coalesce(sum(n.amount) FILTER (WHERE n.direction = 'D'), 0),
           coalesce(sum(n.amount) FILTER (WHERE n.direction = 'C'), 0),
           count(*)
    FROM new_lines n
    GROUP BY 1, 2
    ORDER BY 1, 2
    ON CONFLICT (account_id, period_start) DO UPDATE
        SET debit_total  = p.debit_total + EXCLUDED.debit_total,
            credit_total = p.credit_total + EXCLUDED.credit_total,
            line_count   = p.line_count + EXCLUDED.line_count;

    SELECT a.id
    INTO v_account
    FROM accounts a
             JOIN account_balances b ON b.account_id = a.id
    WHERE a.id IN (SELECT DISTINCT n.account_id FROM new_lines n)
      AND NOT a.allow_negative
      AND CASE a.normal_balance WHEN 'D' THEN b.balance ELSE -b.balance END < 0
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'insufficient funds on account %', v_account USING ERRCODE = 'LG006';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER ledger_entries_apply_balances
    AFTER INSERT
    ON ledger_entries
    REFERENCING NEW TABLE AS new_lines
    FOR EACH STATEMENT
EXECUTE FUNCTION ledger_entries_apply_balances();

-- -----------------------------------------------------------------------------
-- Immutability: posted records are append-only. Corrections are reversals.
-- -----------------------------------------------------------------------------
CREATE FUNCTION forbid_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% on %.% is not allowed: ledger records are immutable', TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'LG003';
END;
$$;

CREATE TRIGGER journal_entries_immutable
    BEFORE UPDATE OR DELETE
    ON journal_entries
    FOR EACH ROW
EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER journal_entries_no_truncate
    BEFORE TRUNCATE
    ON journal_entries
    FOR EACH STATEMENT
EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER ledger_entries_immutable
    BEFORE UPDATE OR DELETE
    ON ledger_entries
    FOR EACH ROW
EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE
    ON ledger_entries
    FOR EACH STATEMENT
EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER ledger_hash_chain_immutable
    BEFORE UPDATE OR DELETE
    ON ledger_hash_chain
    FOR EACH ROW
EXECUTE FUNCTION forbid_mutation();

-- -----------------------------------------------------------------------------
-- Transactional outbox. A relay drains it with FOR UPDATE SKIP LOCKED and
-- publishes to Redpanda when the `events` profile is enabled.
-- -----------------------------------------------------------------------------
CREATE TABLE outbox_events
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(128) NOT NULL,
    payload        JSONB        NOT NULL,
    headers        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     TEXT
);

CREATE INDEX outbox_events_pending_idx ON outbox_events (id) WHERE published_at IS NULL;

-- -----------------------------------------------------------------------------
-- AI audit trail: every natural-language query, what the model was allowed to
-- run, and what it returned. Append-only; the embedding enables "similar past
-- investigations" lookups within the tenant.
-- -----------------------------------------------------------------------------
CREATE TABLE ai_audit_logs
(
    id                    UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id               UUID         NOT NULL,
    request_id            VARCHAR(64),
    query_text            TEXT         NOT NULL,
    query_embedding       public.vector(${embedding_dimensions}),
    model_provider        VARCHAR(32)  NOT NULL,
    model_name            VARCHAR(128) NOT NULL,
    tool_calls            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    generated_sql         TEXT,
    sql_validation_status VARCHAR(16)  NOT NULL DEFAULT 'NOT_APPLICABLE'
        CHECK (sql_validation_status IN ('NOT_APPLICABLE', 'ACCEPTED', 'REJECTED')),
    rejection_reason      TEXT,
    response_summary      TEXT,
    anomalies             JSONB,
    status                VARCHAR(16)  NOT NULL CHECK (status IN ('SUCCEEDED', 'PARTIAL', 'REJECTED', 'FAILED')),
    prompt_tokens         INTEGER,
    completion_tokens     INTEGER,
    latency_ms            INTEGER,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ai_audit_logs_created_idx ON ai_audit_logs (created_at DESC);
CREATE INDEX ai_audit_logs_user_idx ON ai_audit_logs (user_id, created_at DESC);
CREATE INDEX ai_audit_logs_embedding_hnsw ON ai_audit_logs
    USING hnsw (query_embedding public.vector_cosine_ops) WITH (m = 16, ef_construction = 64);

CREATE TRIGGER ai_audit_logs_immutable
    BEFORE UPDATE OR DELETE
    ON ai_audit_logs
    FOR EACH ROW
EXECUTE FUNCTION forbid_mutation();

-- -----------------------------------------------------------------------------
-- Reporting view
-- -----------------------------------------------------------------------------
CREATE VIEW trial_balance AS
SELECT a.id                                                                  AS account_id,
       a.code,
       a.name,
       a.type,
       a.currency,
       b.debit_total,
       b.credit_total,
       CASE a.normal_balance WHEN 'D' THEN b.balance ELSE -b.balance END     AS normal_balance_amount
FROM accounts a
         JOIN account_balances b ON b.account_id = a.id;
