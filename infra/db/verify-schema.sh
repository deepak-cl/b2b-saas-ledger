#!/usr/bin/env bash
# Applies the control-plane and tenant migrations with the real Flyway CLI against
# the dev Postgres (infra/docker-compose.yml) and runs the schema smoke tests.
#
#   docker compose -f infra/docker-compose.yml up -d --wait
#   infra/db/verify-schema.sh            # migrate + test
#   RESET=1 infra/db/verify-schema.sh    # drop everything first
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NETWORK="${NETWORK:-ledger-dev_default}"
PG_CONTAINER="${PG_CONTAINER:-ledger-dev-postgres-1}"
EMBEDDING_DIMENSIONS="${EMBEDDING_DIMENSIONS:-1536}"
TENANTS=(acme globex)

psql_exec() { docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -q -U ledger -d ledger "$@"; }

flyway() {
  docker run --rm --network "$NETWORK" \
    -v "$ROOT/ledger-service/src/main/resources/db/migration:/migrations:ro" \
    flyway/flyway:12 \
    -url=jdbc:postgresql://postgres:5432/ledger -user=ledger -password=ledger \
    -placeholders.embedding_dimensions="$EMBEDDING_DIMENSIONS" \
    -outputType=json -q "$@" migrate >/dev/null
}

if [[ "${RESET:-0}" == "1" ]]; then
  echo "==> Resetting database"
  psql_exec -c "SET client_min_messages = warning; DROP SCHEMA IF EXISTS ai CASCADE; $(printf 'DROP SCHEMA IF EXISTS t_%s CASCADE; ' "${TENANTS[@]}") DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
fi

echo "==> Migrating control plane"
flyway -schemas=public,ai -locations=filesystem:/migrations/control \
  -placeholders.shared_jdbc_url=jdbc:postgresql://postgres:5432/ledger \
  -placeholders.shared_db_username=ledger \
  -placeholders.shared_db_secret_ref=env:LEDGER_DB_PASSWORD

for t in "${TENANTS[@]}"; do
  echo "==> Provisioning tenant $t (schema t_$t)"
  psql_exec -c "INSERT INTO public.tenants (slug, display_name, datasource_id, schema_name, status)
                SELECT '$t', initcap('$t') || ' Corp', id, 't_$t', 'ACTIVE'
                FROM public.tenant_datasources WHERE name = 'shared-primary'
                ON CONFLICT (slug) DO NOTHING;"
  flyway -schemas="t_$t" -locations=filesystem:/migrations/tenant
done

echo "==> Running smoke tests"
psql_exec < "$ROOT/infra/db/smoke-test.sql"
echo "==> All schema checks passed"
