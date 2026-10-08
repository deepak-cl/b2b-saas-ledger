package io.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end behaviour of the ledger API through the real security filter chain, tenant
 * routing and database triggers: shared-schema tenants {@code acme} and {@code globex}, and
 * {@code umbrella} on its own database (ISOLATED tier).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerApiIT extends IntegrationTestSupport {

    private static final RequestPostProcessor ALICE = user("alice", "/tenants/acme/ACCOUNTANT", "/tenants/globex/VIEWER");
    private static final RequestPostProcessor BOB = user("bob", "/tenants/umbrella/OWNER");

    @BeforeAll
    void tenants() throws Exception {
        ensureSharedTenant("acme", "USD");
        ensureSharedTenant("globex", "USD");
        ensureIsolatedTenant("umbrella", "EUR");
        mvc.perform(postTransaction("acme", "acme-seed-capital", transaction("Seed capital",
                        line("1000", "DEBIT", "100000", "USD"), line("3000", "CREDIT", "100000", "USD"))).with(ALICE))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(200, 201));
    }

    private static String key() {
        return "it-" + UUID.randomUUID();
    }

    @Nested
    class Posting {

        @Test
        void postsBalancedTransactionThenReplaysAndRejectsKeyReuse() throws Exception {
            String key = key();
            String body = transaction("AWS invoice", line("6100", "DEBIT", "1250.00", "USD"), line("1000", "CREDIT", "1250", "USD"));

            MvcResult created = mvc.perform(postTransaction("acme", key, body).with(ALICE))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.lines.length()").value(2))
                    .andExpect(jsonPath("$.totals[0].debits").value(1250.0))
                    .andExpect(jsonPath("$.chainSeq").isNumber())
                    .andExpect(jsonPath("$.entryHash").isString())
                    .andReturn();
            String id = body(created).get("id").asString();

            // Same key + same body (different amount scale) -> original entry, no second posting.
            mvc.perform(postTransaction("acme", key, body.replace("\"1250\"", "\"1250.0000\"")).with(ALICE))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.id").value(id));

            mvc.perform(postTransaction("acme", key, body.replace("AWS invoice", "Something else")).with(ALICE))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                    .andExpect(jsonPath("$.transactionId").value(id));

            mvc.perform(get("/api/v1/ledger/transaction/" + id).header("X-Tenant-ID", "acme").with(ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value("AWS invoice"));
        }

        @Test
        void unbalancedEntryIs422WithFinancialErrorCode() throws Exception {
            mvc.perform(postTransaction("acme", key(), transaction("x",
                            line("6100", "DEBIT", "10.00", "USD"), line("1000", "CREDIT", "9.99", "USD"))).with(ALICE))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                    .andExpect(jsonPath("$.code").value("LEDGER_UNBALANCED"))
                    .andExpect(jsonPath("$.retryable").value(false))
                    .andExpect(jsonPath("$.tenant").value("acme"))
                    .andExpect(jsonPath("$.errors[0].message").value("debits and credits differ by 0.01 USD"));
        }

        @Test
        void fieldValidationErrorsAre400() throws Exception {
            mvc.perform(postTransaction("acme", key(), transaction("x",
                            line("6100", "DEBIT", "-5", "USD"), line("1000", "CREDIT", "-5", "usd"))).with(ALICE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
        }

        @Test
        void idempotencyKeyIsRequiredAndValidated() throws Exception {
            String body = transaction("x", line("6100", "DEBIT", "1", "USD"), line("1000", "CREDIT", "1", "USD"));
            mvc.perform(postTransaction("acme", null, body).with(ALICE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));
            mvc.perform(postTransaction("acme", "short", body).with(ALICE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
        }

        @Test
        void overdraftOfNonNegativeAccountIsRejectedAtomically() throws Exception {
            Object before = queryRow("ledger", "SELECT count(*) AS n FROM t_acme.journal_entries").get("n");

            mvc.perform(postTransaction("acme", key(), transaction("Too big",
                            line("6200", "DEBIT", "999999999", "USD"), line("1000", "CREDIT", "999999999", "USD"))).with(ALICE))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

            assertThat(queryRow("ledger", "SELECT count(*) AS n FROM t_acme.journal_entries").get("n")).isEqualTo(before);
        }

        @Test
        void unknownAccountsAndCurrencyMismatchAre422() throws Exception {
            mvc.perform(postTransaction("acme", key(), transaction("x",
                            line("9876", "DEBIT", "1", "USD"), line("1000", "CREDIT", "1", "USD"))).with(ALICE))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                    .andExpect(jsonPath("$.accountCodes[0]").value("9876"));
            mvc.perform(postTransaction("acme", key(), transaction("x",
                            line("6100", "DEBIT", "1", "EUR"), line("1000", "CREDIT", "1", "EUR"))).with(ALICE))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
        }
    }

    @Nested
    class TenantAccess {

        private final String body = transaction("x", line("6100", "DEBIT", "1", "USD"), line("1000", "CREDIT", "1", "USD"));

        @Test
        void requestsWithoutTokenAre401() throws Exception {
            mvc.perform(get("/api/v1/ledger/accounts").header("X-Tenant-ID", "acme"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        void tenantHeaderIsRequiredAndWellFormed() throws Exception {
            mvc.perform(get("/api/v1/ledger/accounts").with(ALICE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TENANT_HEADER_MISSING"));
            mvc.perform(get("/api/v1/ledger/accounts").header("X-Tenant-ID", "../t_acme").with(ALICE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TENANT_HEADER_INVALID"));
        }

        @Test
        void tenantMustBeInTokenClaim() throws Exception {
            mvc.perform(postTransaction("umbrella", key(), body).with(ALICE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
        }

        @Test
        void claimForUnknownTenantIs404() throws Exception {
            mvc.perform(get("/api/v1/ledger/accounts").header("X-Tenant-ID", "ghost")
                            .with(user("carol", "/tenants/ghost/OWNER")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
        }

        @Test
        void roleDecidesPermissions() throws Exception {
            // alice is VIEWER in globex: may read, may not post or query AI.
            mvc.perform(get("/api/v1/ledger/accounts").header("X-Tenant-ID", "globex").with(ALICE))
                    .andExpect(status().isOk());
            mvc.perform(postTransaction("globex", key(), body).with(ALICE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            mvc.perform(post("/api/v1/ai/audit/query").header("X-Tenant-ID", "globex")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"anomalies\"}").with(ALICE))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            mvc.perform(post("/api/v1/ai/audit/query").header("X-Tenant-ID", "acme")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"anomalies\"}").with(ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.implemented").value(false));
        }

        @Test
        void adminApiNeedsPlatformAdminRole() throws Exception {
            mvc.perform(get("/api/v1/admin/tenants").with(user("owner", "/tenants/acme/OWNER")))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/tenants").with(platformAdmin()))
                    .andExpect(status().isOk());
        }

        @Test
        void suspendedTenantIs423UntilReactivated() throws Exception {
            ensureSharedTenant("hooli", "USD");
            RequestPostProcessor owner = user("gavin", "/tenants/hooli/OWNER");
            mvc.perform(post("/api/v1/admin/tenants/hooli/suspend").with(platformAdmin())).andExpect(status().isOk());

            mvc.perform(get("/api/v1/ledger/accounts").header("X-Tenant-ID", "hooli").with(owner))
                    .andExpect(status().isLocked())
                    .andExpect(jsonPath("$.code").value("TENANT_UNAVAILABLE"));

            mvc.perform(post("/api/v1/admin/tenants/hooli/activate").with(platformAdmin())).andExpect(status().isOk());
            mvc.perform(get("/api/v1/ledger/accounts").header("X-Tenant-ID", "hooli").with(owner))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class Isolation {

        @Test
        void transactionOfOneTenantIsInvisibleToAnother() throws Exception {
            MvcResult created = mvc.perform(postTransaction("acme", key(), transaction("acme only",
                            line("6300", "DEBIT", "42", "USD"), line("1000", "CREDIT", "42", "USD"))).with(ALICE))
                    .andExpect(status().isCreated()).andReturn();
            String id = body(created).get("id").asString();

            // Same user, other tenant: the id does not exist there.
            mvc.perform(get("/api/v1/ledger/transaction/" + id).header("X-Tenant-ID", "globex").with(ALICE))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
            assertThat(queryRow("ledger", "SELECT count(*) AS n FROM t_globex.journal_entries").get("n")).isEqualTo(0L);
        }

        @Test
        void isolatedTenantDataLivesOnlyInItsOwnDatabase() throws Exception {
            mvc.perform(postTransaction("umbrella", key(), transaction("EUR seed",
                            line("1000", "DEBIT", "5000", "EUR"), line("3000", "CREDIT", "5000", "EUR"))).with(BOB))
                    .andExpect(status().isCreated());

            assertThat((Long) queryRow("ledger_umbrella", "SELECT count(*) AS n FROM t_umbrella.journal_entries").get("n"))
                    .isPositive();
            assertThat(queryRow("ledger", "SELECT 1 AS x FROM pg_namespace WHERE nspname = 't_umbrella'")).isEmpty();
        }

        @Test
        void hashChainsStayIntact() throws Exception {
            assertThat(queryRow("ledger", "SELECT * FROM t_acme.ledger_verify_chain()")).isEmpty();
            assertThat(queryRow("ledger_umbrella", "SELECT * FROM t_umbrella.ledger_verify_chain()")).isEmpty();
        }
    }

    @Nested
    class BalanceSheet {

        @Test
        void balancesAndReflectsPostings() throws Exception {
            ensureSharedTenant("pied_piper", "USD");
            RequestPostProcessor owner = user("richard", "/tenants/pied_piper/OWNER");
            mvc.perform(postTransaction("pied_piper", key(), transaction("Seed",
                    line("1000", "DEBIT", "10000", "USD"), line("3000", "CREDIT", "10000", "USD"))).with(owner))
                    .andExpect(status().isCreated());
            mvc.perform(postTransaction("pied_piper", key(), transaction("Invoice",
                    line("1100", "DEBIT", "3000", "USD"), line("4000", "CREDIT", "3000", "USD"))).with(owner))
                    .andExpect(status().isCreated());
            mvc.perform(postTransaction("pied_piper", key(), transaction("Cloud bill",
                    line("6100", "DEBIT", "1200", "USD"), line("2000", "CREDIT", "1200", "USD"))).with(owner))
                    .andExpect(status().isCreated());

            MvcResult result = mvc.perform(get("/api/v1/analytics/balance-sheet").param("periods", "2")
                            .header("X-Tenant-ID", "pied_piper").with(owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balanced[0]").value(true))
                    .andExpect(jsonPath("$.balanced[1]").value(true))
                    .andReturn();
            JsonNode sheet = body(result);
            assertThat(sheet.get("assets").get("totals").get(1).decimalValue()).isEqualByComparingTo("13000");
            assertThat(sheet.get("liabilities").get("totals").get(1).decimalValue()).isEqualByComparingTo("1200");
            assertThat(sheet.get("equity").get("totals").get(1).decimalValue()).isEqualByComparingTo("10000");
            assertThat(sheet.get("currentEarnings").get(1).decimalValue()).isEqualByComparingTo("1800");
        }
    }
}
