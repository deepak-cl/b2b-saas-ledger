# Ledger API (Bruno)

This collection is the runnable copy of [../api/openapi.yaml](../api/openapi.yaml). One request per `operationId`. When the contract gains or changes an operation, add or edit the matching `.bru` file in the same change.

## Open it

1. Install [Bruno](https://www.usebruno.com/).
2. Open this folder as a collection (`docs/bruno`).
3. Select the **Local** environment.
4. Start Postgres, Keycloak, the ledger, and the gateway as in the repository [README](../../README.md).

## Order

1. **Auth / Platform admin token**, then **Admin / Provision Acme** and **Provision Globex**. A `409` `TENANT_EXISTS` means that company is already provisioned.
2. **Auth / Alice token**. Later tenant calls use that token and `X-Tenant-ID: {{tenant}}` (`acme`).
3. **Ledger / Post transaction**. The first send creates the journal and stores `transactionId`. Sending it again with the same `idempotencyKey` replays (`200`). Put a new value in `idempotencyKey` before posting a different expense.
4. **AI / Ask (JSON)** for a cash question, then **Ask whether spending spiked** for the spike tool. **Ask (SSE)** is the same call with `Accept: text/event-stream`.

Admin calls use `{{adminToken}}` and send no tenant header. Alice cannot provision.
