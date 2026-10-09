# System specification

This is the mechanics of the ledger: what it is for, how to run it, what happens when someone records an expense, and how a question about one company cannot read another company's books.

The runnable steps and environment variables live in [README.md](README.md). The HTTP contract is [docs/api/openapi.yaml](docs/api/openapi.yaml), and the same calls are in the [Bruno collection](docs/bruno/README.md).

## 1. Executive summary

The product is bookkeeping software that many companies share without sharing a ledger. Each company is a tenant. Shared-tier companies live in separate PostgreSQL schemas on one cluster. An isolated-tier company can be placed in its own database. The application code is the same either way: one datasource key picks the pool, and one schema name picks `t_<slug>`.

A person signs in with Keycloak. The access token lists the companies they belong to, as groups `/tenants/<slug>/<ROLE>`. The web desk shows only those companies. Every later call sends `X-Tenant-ID` for the company on screen. The gateway rejects the call when that slug is not in the token.

Recording an expense writes a double-entry journal: the same amount is debited to an expense account and credited to cash. The database refuses an unbalanced journal, and the posted row is sealed into a hash chain.

Asking a question does not hand the model the database. The service picks one whitelist tool from the wording of the question, runs it inside the current tenant, and the model only narrates that result. Past answers are embedded in pgvector with a `tenant_id` filter, so retrieval cannot return another company's notes. The default installation uses a deterministic stub, so none of this requires an API key.

The web desk at port 5173 is a local client for that flow. It is not a complete accounting product: there is no API to list historical journals, so the on-screen activity is what was posted in this browser session, plus an optional generated sample. Production deployment, CI, and the Prometheus/Grafana stack from Shot 6 are not in this repository.

## 2. Local setup

Prerequisites, process order, demo users, and provisioning `acme` and `globex` are in the README. After that, the pieces are:

| Piece | Role |
|---|---|
| `infra/docker-compose.yml` | PostgreSQL 17 + pgvector, Keycloak 26 with the `ledger` realm imported |
| `ledger-service` on 8081 | Flyway control plane, tenant migrations, posting, analytics, ingestion, AI |
| `gateway` on 8080 | JWT check, tenant membership, rate limit, proxy to 8081 |
| `frontend` on 5173 | Sign-in, company switch, one expense form, balance chart, Ask dialog |

Control-plane tables migrate when the ledger process starts. A tenant schema migrates when `POST /api/v1/admin/tenants` provisions that company. Repeating the call for a company stuck in `PROVISIONING` resumes it. `seedChartOfAccounts: true` inserts the default chart, including `1000` Operating Cash and `6100` Cloud Infrastructure.

AI profiles, in order of cost:

1. **Default.** `LEDGER_AI_CHAT=none`. `StubModels` echoes the tool result. Tests and `e2e.sh` use this.
2. **OpenAI.** `--spring.profiles.active=openai` and `OPENAI_API_KEY`. Chat model `gpt-4o-mini`, embeddings `text-embedding-3-small` (1536 dimensions).
3. **Anthropic.** `--spring.profiles.active=anthropic` and `ANTHROPIC_API_KEY`. Chat model Claude Haiku. Embeddings stay on the stub unless `LEDGER_AI_EMBEDDING` is pointed at another provider.
4. **Ollama.** `--spring.profiles.active=ollama` for a local model.

`SEC_USER_AGENT` must be set before `POST /api/v1/admin/ingestion/sec`. The SEC asks for a contact string such as `Ledger Local you@example.com`, and the client shares one bucket of 10 requests per second.

## 3. Lifecycle of one expense

This is the path from the button on the web desk to a sealed row in `t_<slug>`.

1. **The form refuses a company that cannot post.** The desk loads `GET /api/v1/ledger/accounts`. `expenseSides` looks for an expense account (code `6100`, otherwise any `EXPENSE`) and a cash account (code `1000`, otherwise any `ASSET`). If either is missing, or they are the same account, or the currencies differ, the button does not submit. A viewer never sees an enabled post: the role lacks `LEDGER_POST`.

2. **The browser sends one journal.** `postTransaction` calls `POST /api/v1/ledger/transaction` with `Authorization: Bearer <access token>`, `X-Tenant-ID` set to the selected slug, and a fresh `Idempotency-Key`. The body is two lines of equal amount, for example debit `6100` and credit `1000`, both in the account currency. Amounts are JSON numbers. The key matches `^[A-Za-z0-9._:-]{8,128}$`.

3. **The gateway decides whether this person may touch this company.** It checks the JWT issuer, signature, and audience `ledger-api`. `X-Tenant-ID` must appear as `/tenants/<slug>/<ROLE>` in the `tenants` claim. A spoofed header is `403` with code `TENANT_ACCESS_DENIED` before a database connection is used. The gateway assigns `X-Request-Id` and takes one token from that tenant's ledger bucket.

4. **The ledger service repeats the check and binds the tenant.** Unknown slug is `404 TENANT_NOT_FOUND`. A company that is not `ACTIVE` is `423 TENANT_UNAVAILABLE`. `TenantContext` holds the tenant for the request. `TenantRoutingDataSource` selects the Hikari pool for `datasource_id`. Hibernate sets the connection schema to `t_<slug>`. Method security reads the role from that context. `ACCOUNTANT` and above may post. `AUDITOR` and `VIEWER` may not.

5. **Idempotency.** The service hashes the canonical body. The same key and the same body return the original journal with `200` and `Idempotent-Replayed: true`. The same key and a different body return `409 IDEMPOTENCY_KEY_REUSED`. A retry of a lost response is safe. A concurrent burst from one tenant beyond the bulkhead returns `503 TENANT_BUSY` and can be retried with the same key.

6. **The database makes an unbalanced journal unrepresentable.** The insert runs in one transaction. For each currency, debits must equal credits. Trigger SQLSTATEs `LG001`–`LG007` become problem codes such as `LEDGER_UNBALANCED`, `ACCOUNT_NOT_FOUND`, and `INSUFFICIENT_FUNDS`. A deferred seal trigger at commit writes the SHA-256 hash chain (`chainSeq`, `entryHash`). A posted row is not updated. A correction is a new reversing journal.

7. **What the desk shows afterwards.** The response is appended to the in-memory activity list for this visit. Refreshing the page drops the session, because the access token is kept in React state only. The balance chart re-reads `GET /api/v1/analytics/balance-sheet?periods=6`, which builds month columns from `account_period_balances` in one `REPEATABLE READ` snapshot. Liabilities and equity are shown credit-positive.

The same posting function is what SEC and PaySim imports call. Those journals use `source` `SEC_EDGAR` or `PAYSIM` and their own idempotency keys, so running an import again replays instead of doubling the books.

## 4. How a question stays inside one company

`POST /api/v1/ai/audit/query` requires a role with `AI_QUERY`. `VIEWER` is refused. `Accept: application/json` returns the whole insight. `Accept: text/event-stream` emits `token`, then `finding`, then `done`. A rate-limit or budget failure is `429` before the stream opens.

The service does the following, in order:

1. **Cache.** The key is the tenant id, the trimmed question, the month window, the currency, and `max(entry_no)`. A repeat of the same question against an unchanged ledger returns the stored answer and does not spend the ledger's per-tenant limiter. The gateway still counts the HTTP call. The cache lives in memory for 10 minutes.

2. **Budget and rate.** After a cache miss, a Resilience4j limiter allows 60 requests a minute for that tenant. The sum of prompt and completion tokens in `ai_audit_logs` for the calendar month is compared with `LEDGER_AI_MONTHLY_TOKENS`. Over the budget, the call is `429 AI_BUDGET_EXCEEDED`.

3. **Retrieval.** Spring AI searches `ai.vector_store` with `Filter.Expression tenant_id == <current tenant>`. Only a short summary of a previous question and answer is stored, and the same text is not embedded twice. A new database connection sets `hnsw.iterative_scan = relaxed_order` when pgvector supports it.

4. **One tool, chosen from the question.** The model is not asked to pick. The service runs exactly one whitelist call:
   - The question contains `readonly:` — the remainder is a single `SELECT`, checked by JSqlParser (one statement, ledger tables only, no system functions, no other schema) and executed as `ledger_ai_reader` inside a read-only transaction with `statement_timeout`.
   - The question mentions an anomaly, spike, or jump — `spendingAnomalies` over `account_period_balances` for the window.
   - The question asks for a monthly trend — `monthlyActivity`.
   - Anything else — `accountBalances`, and the spoken answer prefers the accounts the question named (cash, receivables, payables, cloud).

   Those methods are also registered on `ChatClient`, so a real model could call them. The stub does not. It returns the sentence the service already built from the tool output. A cash question therefore cannot come back as a spike check.

5. **The write boundary.** Tools do not insert journals. The only write on this path is the audit row: question, answer, tool name, findings, token counts, and the embedding of the summary. The posting transaction and the audit transaction are separate. A failed model call does not leave a half-posted journal, because no journal was started.

6. **What the desk does with the stream.** The Ask dialog sends the text in the search field. Suggestion chips send their own sentence immediately. Token events append to the answer. The page does not keep a previous answer and replay it for a later question.

A person in Acme cannot read Globex by changing a header, a vector filter, or the SQL tool. The header is checked against the token, the vector filter is the tenant bound on the request, and `ledger_ai_reader` runs after the connection schema is already `t_<slug>`.

## 5. Contract and collection

Every public operation is in `docs/api/openapi.yaml` and has a matching `.bru` request under `docs/bruno/`. Errors are `application/problem+json` with a stable `code`. Clients branch on `code`, not on the sentence in `detail`.

When an endpoint is added or its body changes, update the OpenAPI file and the Bruno request in the same change. The collection is the executable copy of the contract, not a second design.
