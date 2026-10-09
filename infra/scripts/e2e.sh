#!/usr/bin/env bash
# End-to-end smoke test against the real local stack:
#   docker compose -f infra/docker-compose.yml up -d     (Postgres + Keycloak)
#   ./gradlew :ledger-service:bootRun                    (port 8081)
#   ./gradlew :gateway:bootRun                           (port 8080)
#   infra/scripts/e2e.sh
#
# Provisions two tenants through the admin API (one SHARED, one ISOLATED in its own database),
# grants Keycloak group memberships, then drives the public API through the gateway with real
# tokens. Re-runnable: provisioning and memberships are idempotent, idempotency keys are unique
# per run. Never calls an AI provider (the AI path is only used to exercise the rate limiter).
set -euo pipefail

GATEWAY=${GATEWAY:-http://localhost:8080}
KEYCLOAK=${KEYCLOAK:-http://localhost:8180}
PG_CONTAINER=${PG_CONTAINER:-ledger-dev-postgres-1}
SHARED=${E2E_SHARED_TENANT:-e2e_shared}
ISOLATED=${E2E_ISOLATED_TENANT:-e2e_isolated}
RUN=$(date +%s)
TODAY=$(date +%F)

pass=0
fail=0
ok()   { pass=$((pass + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { fail=$((fail + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; [ -n "${2:-}" ] && printf '       %s\n' "$2"; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1], {'d': d}))" "$1"; }

# expect <label> <expected-status> <expected-code-or-empty> -- curl args...
# Leaves the body in $BODY and headers in $HEADERS.
expect() {
  local label=$1 want=$2 code=$3; shift 4
  local hdr; hdr=$(mktemp)
  BODY=$(curl -s -D "$hdr" -o - -w '\n%{http_code}' "$@")
  local got=${BODY##*$'\n'}; BODY=${BODY%$'\n'*}
  HEADERS=$(cat "$hdr"); rm -f "$hdr"
  if [ "$got" != "$want" ]; then bad "$label" "expected HTTP $want, got $got: ${BODY:0:300}"; return 0; fi
  if [ -n "$code" ]; then
    local actual; actual=$(printf '%s' "$BODY" | json "d.get('code')" 2>/dev/null || true)
    if [ "$actual" != "$code" ]; then bad "$label" "expected code $code, got $actual"; return 0; fi
  fi
  ok "$label"
}

token() {
  curl -sf -d grant_type=password -d client_id=ledger-web -d "username=$1" -d "password=$1" \
    "$KEYCLOAK/realms/ledger/protocol/openid-connect/token" | json "d['access_token']"
}

# ------------------------------------------------------------------ Keycloak admin helpers
kc_admin_token() {
  curl -sf -d grant_type=password -d client_id=admin-cli -d username="${KC_ADMIN:-admin}" -d password="${KC_ADMIN_PASSWORD:-admin}" \
    "$KEYCLOAK/realms/master/protocol/openid-connect/token" | json "d['access_token']"
}
kc() { curl -sf -H "Authorization: Bearer $KC_TOKEN" -H 'Content-Type: application/json' "$@"; }
kc_group_id() { kc "$KEYCLOAK/admin/realms/ledger/group-by-path/$1" 2>/dev/null | json "d['id']" 2>/dev/null || true; }
kc_ensure_child() { # parent-path child-name -> id
  local id; id=$(kc_group_id "$1/$2")
  if [ -z "$id" ]; then
    kc -o /dev/null -X POST -d "{\"name\":\"$2\"}" "$KEYCLOAK/admin/realms/ledger/groups/$(kc_group_id "$1")/children"
    id=$(kc_group_id "$1/$2")
  fi
  printf '%s' "$id"
}
kc_grant() { # user tenant ROLE
  kc_ensure_child tenants "$2" >/dev/null
  local gid uid
  gid=$(kc_ensure_child "tenants/$2" "$3")
  uid=$(kc "$KEYCLOAK/admin/realms/ledger/users?username=$1&exact=true" | json "d[0]['id']")
  kc -o /dev/null -X PUT "$KEYCLOAK/admin/realms/ledger/users/$uid/groups/$gid"
}
# Demo users (alice, bob) keep the memberships from the realm import. Smoke tests use their own user.
kc_ensure_user() { # username
  local uid
  uid=$(kc "$KEYCLOAK/admin/realms/ledger/users?username=$1&exact=true" | json "d[0]['id']" 2>/dev/null || true)
  if [ -z "$uid" ] || [ "$uid" = "None" ]; then
    kc -o /dev/null -X POST -d "{\"username\":\"$1\",\"enabled\":true,\"emailVerified\":true,\"firstName\":\"E2E\",\"lastName\":\"User\",\"credentials\":[{\"type\":\"password\",\"value\":\"$1\",\"temporary\":false}]}" \
      "$KEYCLOAK/admin/realms/ledger/users"
  fi
}

post_tx() { # label status code token tenant key body
  expect "$1" "$2" "$3" -- -X POST "$GATEWAY/api/v1/ledger/transaction" \
    -H "Authorization: Bearer $4" -H "X-Tenant-ID: $5" -H "Idempotency-Key: $6" \
    -H 'Content-Type: application/json' -d "$7"
}
tx() { # description line...
  local desc=$1; shift
  local IFS=,
  printf '{"effectiveDate":"%s","description":"%s","lines":[%s]}' "$TODAY" "$desc" "$*"
}
ln() { printf '{"accountCode":"%s","direction":"%s","amount":"%s","currency":"%s"}' "$1" "$2" "$3" "$4"; }

# ------------------------------------------------------------------ 0. preflight
step "0. Preflight"
curl -sf "$GATEWAY/actuator/health" >/dev/null && ok "gateway is up" || { bad "gateway is not reachable at $GATEWAY"; exit 1; }
curl -sf "$KEYCLOAK/realms/ledger/.well-known/openid-configuration" >/dev/null && ok "keycloak realm is up" || { bad "keycloak not reachable"; exit 1; }

# ------------------------------------------------------------------ 1. provisioning
step "1. Tenant provisioning (platform admin)"
ADMIN=$(token platform-admin)
ALICE=$(token alice)

expect "non-admin cannot provision" 403 PERMISSION_DENIED -- -X POST "$GATEWAY/api/v1/admin/tenants" \
  -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d '{}'

if [ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ADMIN" "$GATEWAY/api/v1/admin/tenants/$SHARED")" = 404 ]; then
  expect "provision $SHARED (SHARED, USD)" 201 "" -- -X POST "$GATEWAY/api/v1/admin/tenants" \
    -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
    -d "{\"slug\":\"$SHARED\",\"displayName\":\"E2E Shared\",\"tier\":\"SHARED\",\"baseCurrency\":\"USD\"}"
else
  ok "$SHARED already provisioned"
fi

if [ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ADMIN" "$GATEWAY/api/v1/admin/tenants/$ISOLATED")" = 404 ]; then
  docker exec "$PG_CONTAINER" psql -q -U ledger -d ledger -c "DROP DATABASE IF EXISTS ledger_$ISOLATED" -c "CREATE DATABASE ledger_$ISOLATED" >/dev/null
  expect "provision $ISOLATED (ISOLATED, EUR, own database)" 201 "" -- -X POST "$GATEWAY/api/v1/admin/tenants" \
    -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
    -d "{\"slug\":\"$ISOLATED\",\"displayName\":\"E2E Isolated\",\"tier\":\"ISOLATED\",\"baseCurrency\":\"EUR\",
         \"isolatedDatabase\":{\"jdbcUrl\":\"jdbc:postgresql://localhost:5432/ledger_$ISOLATED\",\"username\":\"ledger\",\"secretRef\":\"env:LEDGER_DB_PASSWORD\",\"poolMaxSize\":4}}"
else
  ok "$ISOLATED already provisioned"
fi

expect "invalid provisioning request is 400" 400 VALIDATION_FAILED -- -X POST "$GATEWAY/api/v1/admin/tenants" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"slug":"x","displayName":""}'

# ------------------------------------------------------------------ 2. memberships
step "2. Keycloak memberships"
KC_TOKEN=$(kc_admin_token)
kc_ensure_user e2e
kc_grant e2e "$SHARED" ACCOUNTANT && ok "e2e -> /tenants/$SHARED/ACCOUNTANT"
kc_grant bob "$ISOLATED" OWNER && ok "bob -> /tenants/$ISOLATED/OWNER"
kc_grant bob "$SHARED" VIEWER && ok "bob -> /tenants/$SHARED/VIEWER"
E2E=$(token e2e)
BOB=$(token bob)
claims=$(printf '%s' "$E2E" | cut -d. -f2 | python3 -c "import sys,base64,json; s=sys.stdin.read().strip(); s+='='*(-len(s)%4); print(json.loads(base64.urlsafe_b64decode(s))['tenants'])")
[[ $claims == *"/tenants/$SHARED/ACCOUNTANT"* ]] && ok "e2e's token carries the new tenant claim" || bad "tenant claim missing" "$claims"

# ------------------------------------------------------------------ 3. postings
step "3. Postings through the gateway"
post_tx "seed capital (201)" 201 "" "$E2E" "$SHARED" "e2e-$RUN-seed" "$(tx 'Seed capital' "$(ln 1000 DEBIT 50000 USD)" "$(ln 3000 CREDIT 50000 USD)")"
TX_ID=$(printf '%s' "$BODY" | json "d.get('id')" 2>/dev/null || true)
grep -qi '^location:' <<<"$HEADERS" && ok "Location header present" || bad "Location header missing"
grep -qi '^x-request-id:' <<<"$HEADERS" && ok "X-Request-Id header present" || bad "X-Request-Id missing"
[ "$(grep -ci '^x-request-id:' <<<"$HEADERS")" = 1 ] && ok "X-Request-Id not duplicated" || bad "X-Request-Id duplicated"

BODY_AWS=$(tx 'AWS invoice' "$(ln 6100 DEBIT 1250.00 USD)" "$(ln 1000 CREDIT 1250 USD)")
post_tx "cloud invoice (201)" 201 "" "$E2E" "$SHARED" "e2e-$RUN-aws" "$BODY_AWS"
post_tx "replay same key + body (200)" 200 "" "$E2E" "$SHARED" "e2e-$RUN-aws" "$BODY_AWS"
grep -qi '^idempotent-replayed: true' <<<"$HEADERS" && ok "Idempotent-Replayed: true" || bad "replay header missing"
post_tx "same key, different body (409)" 409 IDEMPOTENCY_KEY_REUSED "$E2E" "$SHARED" "e2e-$RUN-aws" "$(tx 'Other' "$(ln 6100 DEBIT 1 USD)" "$(ln 1000 CREDIT 1 USD)")"
post_tx "unbalanced (422)" 422 LEDGER_UNBALANCED "$E2E" "$SHARED" "e2e-$RUN-unbal" "$(tx 'Bad' "$(ln 6100 DEBIT 10 USD)" "$(ln 1000 CREDIT 9.99 USD)")"
post_tx "overdraft cash (422)" 422 INSUFFICIENT_FUNDS "$E2E" "$SHARED" "e2e-$RUN-over" "$(tx 'Huge' "$(ln 6200 DEBIT 9999999 USD)" "$(ln 1000 CREDIT 9999999 USD)")"
expect "missing Idempotency-Key (400)" 400 IDEMPOTENCY_KEY_MISSING -- -X POST "$GATEWAY/api/v1/ledger/transaction" \
  -H "Authorization: Bearer $E2E" -H "X-Tenant-ID: $SHARED" -H 'Content-Type: application/json' -d "$BODY_AWS"
post_tx "viewer cannot post (403)" 403 PERMISSION_DENIED "$BOB" "$SHARED" "e2e-$RUN-viewer" "$BODY_AWS"
post_tx "ISOLATED tenant posting (201)" 201 "" "$BOB" "$ISOLATED" "e2e-$RUN-iso" "$(tx 'EUR seed' "$(ln 1000 DEBIT 8000 EUR)" "$(ln 3000 CREDIT 8000 EUR)")"
post_tx "ISOLATED tenant invoice (201)" 201 "" "$BOB" "$ISOLATED" "e2e-$RUN-iso-inv" "$(tx 'Invoice' "$(ln 1100 DEBIT 1500 EUR)" "$(ln 4000 CREDIT 1500 EUR)")"

# ------------------------------------------------------------------ 4. isolation
step "4. Tenant isolation"
expect "alice -> $ISOLATED is denied at the gateway (403)" 403 TENANT_ACCESS_DENIED -- \
  "$GATEWAY/api/v1/ledger/accounts" -H "Authorization: Bearer $ALICE" -H "X-Tenant-ID: $ISOLATED"
expect "no tenant header (400)" 400 TENANT_HEADER_MISSING -- "$GATEWAY/api/v1/ledger/accounts" -H "Authorization: Bearer $ALICE"
expect "no token (401)" 401 UNAUTHENTICATED -- "$GATEWAY/api/v1/ledger/accounts" -H "X-Tenant-ID: $SHARED"
if [ -n "$TX_ID" ]; then
  expect "$SHARED transaction id is unknown in $ISOLATED (404)" 404 TRANSACTION_NOT_FOUND -- \
    "$GATEWAY/api/v1/ledger/transaction/$TX_ID" -H "Authorization: Bearer $BOB" -H "X-Tenant-ID: $ISOLATED"
fi
rows=$(docker exec "$PG_CONTAINER" psql -At -U ledger -d "ledger_$ISOLATED" -c "SELECT count(*) FROM t_$ISOLATED.journal_entries")
[ "$rows" -gt 0 ] && ok "$ISOLATED rows live in database ledger_$ISOLATED ($rows entries)" || bad "no rows in isolated database"
shared_has=$(docker exec "$PG_CONTAINER" psql -At -U ledger -d ledger -c "SELECT count(*) FROM pg_namespace WHERE nspname = 't_$ISOLATED'")
[ "$shared_has" = 0 ] && ok "shared database has no schema for $ISOLATED" || bad "isolated tenant schema leaked into shared database"
broken=$(docker exec "$PG_CONTAINER" psql -At -U ledger -d ledger -c "SELECT count(*) FROM t_$SHARED.ledger_verify_chain()")
[ "$broken" = 0 ] && ok "hash chain of $SHARED verifies" || bad "hash chain broken"

# ------------------------------------------------------------------ 5. analytics
step "5. Balance sheet"
expect "$SHARED balance sheet (200)" 200 "" -- "$GATEWAY/api/v1/analytics/balance-sheet?periods=3" \
  -H "Authorization: Bearer $E2E" -H "X-Tenant-ID: $SHARED"
[ "$(printf '%s' "$BODY" | json "all(d['balanced'])")" = True ] && ok "$SHARED balances in every period" || bad "$SHARED unbalanced" "$BODY"
expect "$ISOLATED balance sheet (200)" 200 "" -- "$GATEWAY/api/v1/analytics/balance-sheet?periods=2" \
  -H "Authorization: Bearer $BOB" -H "X-Tenant-ID: $ISOLATED"
summary=$(printf '%s' "$BODY" | json "(d['currency'], d['assets']['totals'][-1], d['totalLiabilitiesAndEquity'][-1], all(d['balanced']))")
[[ $summary == *"True)" ]] && ok "$ISOLATED balances: $summary" || bad "$ISOLATED unbalanced" "$summary"

# ------------------------------------------------------------------ 6. rate limiting
step "6. Per-tenant AI rate limit at the gateway"
codes=""
for i in $(seq 1 65); do
  codes+="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/v1/ai/audit/query" \
    -H "Authorization: Bearer $E2E" -H "X-Tenant-ID: $SHARED" -H 'Content-Type: application/json' -d '{"query":"ping"}') "
done
[[ $codes == *429* ]] && ok "AI bucket exhausted -> 429 (statuses: $codes)" || bad "no 429 within 65 AI calls" "$codes"
expect "429 is problem+json with Retry-After" 429 RATE_LIMITED -- -X POST "$GATEWAY/api/v1/ai/audit/query" \
  -H "Authorization: Bearer $E2E" -H "X-Tenant-ID: $SHARED" -H 'Content-Type: application/json' -d '{"query":"ping"}'
grep -qi '^retry-after:' <<<"$HEADERS" && ok "Retry-After present" || bad "Retry-After missing"
expect "ledger calls of the same tenant still pass" 200 "" -- "$GATEWAY/api/v1/ledger/accounts" \
  -H "Authorization: Bearer $E2E" -H "X-Tenant-ID: $SHARED"

# ------------------------------------------------------------------ summary
printf '\n\033[1m%d passed, %d failed\033[0m\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
