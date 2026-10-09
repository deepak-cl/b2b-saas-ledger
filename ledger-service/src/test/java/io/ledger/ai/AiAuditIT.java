package io.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ledger.IntegrationTestSupport;
import io.ledger.tenancy.TenantContext;
import io.ledger.tenancy.TenantRegistry;
import io.ledger.tenancy.TenantRole;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The audit path with the stub model: tools, tenant filter, cache, read-only SQL, and the token budget. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiAuditIT extends IntegrationTestSupport {

    private static final RequestPostProcessor ALICE = user("alice", "/tenants/ai_co/ACCOUNTANT", "/tenants/ai_other/ACCOUNTANT");

    @Autowired
    private TenantVectorStore vectors;
    @Autowired
    private TenantRegistry registry;
    @Autowired
    private org.springframework.ai.chat.model.ChatModel chatModel;

    @BeforeAll
    void tenant() throws Exception {
        ensureSharedTenant("ai_co", "USD");
        ensureSharedTenant("ai_other", "USD");
        postJournal("ai_co", "ai-seed-01", "2026-01-01", "Seed", "1000", "DEBIT", "5000", "3000", "CREDIT", "5000");
        postJournal("ai_co", "ai-cloud-jan", "2026-01-15", "Cloud January", "6100", "DEBIT", "100", "1000", "CREDIT", "100");
        postJournal("ai_co", "ai-cloud-feb", "2026-02-15", "Cloud February", "6100", "DEBIT", "1000", "1000", "CREDIT", "1000");
    }

    @Test
    void anomalyQuestionUsesTheToolAndReplaysFromCache() throws Exception {
        int calls = ((StubModels.Chat) chatModel).calls();
        MvcResult first = ask("ai_co", "{\"query\":\"Find cloud spending anomalies\",\"from\":\"2026-01\",\"to\":\"2026-02\"}");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        var body = body(first);
        assertThat(body.path("findings")).isNotEmpty();
        assertThat(body.path("findings").get(0).path("accountCode").asString()).isEqualTo("6100");
        assertThat(body.path("findings").get(0).path("period").asString()).isEqualTo("2026-02");
        assertThat(body.path("toolCalls").get(0).path("name").asString()).isEqualTo("spendingAnomalies");
        assertThat(body.path("usage").path("cached").asBoolean()).isFalse();
        int after = ((StubModels.Chat) chatModel).calls();
        assertThat(after).isGreaterThan(calls);

        MvcResult second = ask("ai_co", "{\"query\":\"Find cloud spending anomalies\",\"from\":\"2026-01\",\"to\":\"2026-02\"}");
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(second).path("usage").path("cached").asBoolean()).isTrue();
        assertThat(((StubModels.Chat) chatModel).calls()).isEqualTo(after);
    }

    @Test
    void vectorSearchStaysInsideTheTenant() {
        String note = "ai-co private anomaly note";
        TenantContext.runAs(scope("ai_co"), () -> vectors.addSummary(note));
        TenantContext.runAs(scope("ai_other"), () ->
                assertThat(vectors.search(note, 4)).extracting(org.springframework.ai.document.Document::getText).doesNotContain(note));
        TenantContext.runAs(scope("ai_co"), () ->
                assertThat(vectors.search(note, 4)).extracting(org.springframework.ai.document.Document::getText).contains(note));
        TenantContext.runAs(scope("ai_co"), () -> vectors.addSummary(note));
        long copies = countNotes(note);
        assertThat(copies).isEqualTo(1);
    }

    @Test
    void readOnlySqlIsCheckedBeforeItRuns() throws Exception {
        MvcResult accepted = ask("ai_co", "{\"query\":\"readonly: SELECT code FROM accounts WHERE code = '6100'\"}");
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(accepted.getResponse().getContentAsString()).contains("6100");
        assertThat(auditStatus(body(accepted).path("queryId").asString())).isEqualTo("ACCEPTED");

        MvcResult rejected = ask("ai_co", "{\"query\":\"readonly: DROP TABLE accounts\"}");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
        assertThat(rejected.getResponse().getContentAsString()).contains("Rejected");
        assertThat(auditStatus(body(rejected).path("queryId").asString())).isEqualTo("REJECTED");
    }

    @Test
    void streamEmitsTokenFindingAndDone() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/ai/audit/query")
                        .header("X-Tenant-ID", "ai_co")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"Find cloud spending anomalies\",\"from\":\"2026-01\",\"to\":\"2026-02\"}")
                        .with(ALICE))
                .andExpect(status().isOk())
                .andReturn();
        String events = result.getResponse().getContentAsString();
        assertThat(events).contains("event:token", "event:finding", "event:done");
    }

    @Test
    void monthlyBudgetStopsFurtherCalls() throws Exception {
        ensureSharedTenant("ai_budget", "USD");
        RequestPostProcessor owner = user("budget-user", "/tenants/ai_budget/OWNER");
        int status = 200;
        for (int i = 0; i < 8 && status == 200; i++) {
            status = mvc.perform(post("/api/v1/ai/audit/query")
                            .header("X-Tenant-ID", "ai_budget")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"budget check " + i + "\"}")
                            .with(owner))
                    .andReturn().getResponse().getStatus();
        }
        assertThat(status).isEqualTo(429);
        mvc.perform(post("/api/v1/ai/audit/query")
                        .header("X-Tenant-ID", "ai_budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"budget check overflow\"}")
                        .with(owner))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AI_BUDGET_EXCEEDED"));
    }

    private MvcResult ask(String tenant, String json) throws Exception {
        return mvc.perform(post("/api/v1/ai/audit/query")
                        .header("X-Tenant-ID", tenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(ALICE))
                .andReturn();
    }

    private void postJournal(String tenant, String key, String date, String description, String debitAccount, String debitSide,
                      String debitAmount, String creditAccount, String creditSide, String creditAmount) throws Exception {
        String body = """
                {"effectiveDate":"%s","description":"%s","lines":[
                  {"accountCode":"%s","direction":"%s","amount":"%s","currency":"USD"},
                  {"accountCode":"%s","direction":"%s","amount":"%s","currency":"USD"}]}
                """.formatted(date, description, debitAccount, debitSide, debitAmount, creditAccount, creditSide, creditAmount);
        mvc.perform(postTransaction(tenant, key, body).with(ALICE))
                .andExpect(r -> assertThat(r.getResponse().getStatus())
                        .as(r.getResponse().getContentAsString())
                        .isIn(200, 201));
    }

    private TenantContext.TenantScope scope(String slug) {
        return new TenantContext.TenantScope(registry.findBySlug(slug).orElseThrow(), TenantRole.ACCOUNTANT, "alice");
    }

    private long countNotes(String note) {
        try {
            Object count = queryRow("ledger",
                    "SELECT count(*) AS n FROM ai.vector_store WHERE content = '" + note + "'").get("n");
            return ((Number) count).longValue();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String auditStatus(String id) throws Exception {
        return String.valueOf(queryRow("ledger",
                "SELECT sql_validation_status AS s FROM t_ai_co.ai_audit_logs WHERE id = '" + id + "'").get("s"));
    }
}
