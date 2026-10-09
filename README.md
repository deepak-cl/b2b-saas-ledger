# Multi-tenant ledger

Shared bookkeeping software for more than one company. Each company is a tenant with its own chart of accounts, journals, and answers. A person who belongs to two companies sees only the one they have selected. Questions about the books stay inside that company: vector search is filtered by tenant, and the model can only call a fixed set of read-only tools.

The local web desk is a way to sign in, switch company, post one balanced expense, and ask a question. It is not a finished product, and this repository does not include the Shot 6 deploy, CI, or monitoring stack.

## What you need

- Java 21
- Node.js 20 or newer
- Docker, for PostgreSQL 17 (with pgvector) and Keycloak

No API key is required. The default AI profile is a local stub that narrates the tool result and never calls OpenAI or Anthropic.

## Start the stack

From the repository root:

```bash
docker compose -f infra/docker-compose.yml up -d --wait
./gradlew :ledger-service:bootRun
```

In a second terminal:

```bash
./gradlew :gateway:bootRun
```

In a third terminal:

```bash
cd frontend && npm install && npm test && npm run dev
```

| Process | URL |
|---|---|
| Web desk | http://localhost:5173 |
| API gateway | http://localhost:8080 |
| Ledger service (direct) | http://localhost:8081 |
| OpenAPI UI | http://localhost:8081/swagger-ui.html |
| Keycloak | http://localhost:8180 |

Postgres listens on `localhost:5432` (`ledger` / `ledger`, database `ledger`). Keycloak admin is `admin` / `admin`.

The web dev server proxies `/api` to the gateway and `/realms` to Keycloak, so the browser stays on port 5173.

### Demo people

Realm `ledger`. Password equals the username.

| User | What they can do |
|---|---|
| `alice` | Accountant for `acme`. Viewer for `globex` (read only, cannot ask). |
| `bob` | Owner of `globex`. |
| `platform-admin` | Creates companies. No tenant header. |

Keycloak knows these memberships on first start. The database does not contain the companies until you provision them. Sign in to the [Bruno collection](docs/bruno/README.md) or call the admin API:

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=ledger-web \
  -d username=platform-admin -d password=platform-admin \
  http://localhost:8180/realms/ledger/protocol/openid-connect/token \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')

curl -s -X POST http://localhost:8080/api/v1/admin/tenants \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"slug":"acme","displayName":"Acme","tier":"SHARED","baseCurrency":"USD","seedChartOfAccounts":true}'

curl -s -X POST http://localhost:8080/api/v1/admin/tenants \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"slug":"globex","displayName":"Globex","tier":"SHARED","baseCurrency":"USD","seedChartOfAccounts":true}'
```

`409` with code `TENANT_EXISTS` means that company is already there. Then open http://localhost:5173 and sign in as `alice`.

A seeded company includes Operating Cash (`1000`) and Cloud Infrastructure (`6100`), which is what the expense form posts. Giving someone a new company is not a screen in the web desk: `platform-admin` provisions the company, and a Keycloak group `/tenants/<slug>/<ROLE>` grants access. The person signs in again to pick it up.

## Configuration

Defaults are enough for local use. Set a variable only when you are leaving the stub.

| Variable | Default | Used for |
|---|---|---|
| `LEDGER_DB_URL` | `jdbc:postgresql://localhost:5432/ledger` | Control-plane JDBC URL |
| `LEDGER_DB_USER` / `LEDGER_DB_PASSWORD` | `ledger` / `ledger` | Database login |
| `LEDGER_OIDC_ISSUER` | `http://localhost:8180/realms/ledger` | JWT issuer |
| `LEDGER_OIDC_JWKS` | issuer `.../protocol/openid-connect/certs` | JWT signature keys |
| `LEDGER_SERVICE_URL` | `http://localhost:8081` | Gateway route to the ledger |
| `LEDGER_AI_PROVIDER` | `stub` | Which narrator is recorded on the audit row |
| `LEDGER_AI_CHAT` / `LEDGER_AI_EMBEDDING` | `none` | Spring AI model switches. `none` keeps the stub |
| `LEDGER_AI_MONTHLY_TOKENS` | `200000` | Per-tenant token budget for the calendar month |
| `LEDGER_EMBEDDING_DIMENSIONS` | `1536` | Must match the vector column. Changing it needs a new migration |
| `SEC_USER_AGENT` | empty | Required before any SEC EDGAR call, for example `Ledger Local you@example.com` |
| `OPENAI_API_KEY` | empty | Only with `--spring.profiles.active=openai` |
| `ANTHROPIC_API_KEY` | empty | Only with `--spring.profiles.active=anthropic` |

Paid chat, still from `ledger-service`:

```bash
OPENAI_API_KEY=sk-... ./gradlew :ledger-service:bootRun --args='--spring.profiles.active=openai'
ANTHROPIC_API_KEY=sk-ant-... ./gradlew :ledger-service:bootRun --args='--spring.profiles.active=anthropic'
```

The OpenAI profile uses `gpt-4o-mini` and `text-embedding-3-small`. The Anthropic profile uses Claude Haiku for chat and leaves embeddings on the stub unless you also point `LEDGER_AI_EMBEDDING` at OpenAI or run the `ollama` profile. Credits apply only if you have configured that provider yourself. The gateway rate limit for `/api/v1/ai/**` is 60 requests a minute per tenant.

## Check that it works

```bash
RESET=1 infra/db/verify-schema.sh
./gradlew test
infra/scripts/e2e.sh
```

`./gradlew test` uses Testcontainers and mock JWTs. It does not need Keycloak or an LLM. `e2e.sh` needs the compose stack, the ledger, and the gateway. It provisions its own shared and isolated tenants and does not call a paid model.

## Read next

- [SYSTEM_SPEC.md](SYSTEM_SPEC.md) — how a posting moves from the button to the hash chain, and how an AI question stays inside one tenant.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — tenancy, schema, and the choices behind each phase.
- [docs/api/openapi.yaml](docs/api/openapi.yaml) — the HTTP contract.
- [docs/bruno/README.md](docs/bruno/README.md) — the same contract as a Bruno collection. Add a request there whenever `openapi.yaml` gains an operation.
