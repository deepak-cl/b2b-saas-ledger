package io.ledger.ai;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.ledger.common.error.LedgerErrorCode;
import io.ledger.common.error.LedgerException;
import io.ledger.tenancy.TenantContext;
import io.ledger.tenancy.TenantInfo;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a tenant-scoped question. Retrieval is filtered to that tenant, the model may only
 * call {@link LedgerTools}, and token spend is capped per tenant per calendar month.
 */
public class FinancialAiAuditService {

    private static final String SYSTEM = """
            You are the financial auditor for exactly one tenant. Answer only from the tool results
            and the retrieved notes. Never invent figures, never ask for or emit raw SQL unless the
            user explicitly asks for a read-only check, and never refer to other tenants.
            Call spendingAnomalies for questions about anomalies, spikes, or period comparisons.
            """;

    private final ChatClient chatClient;
    private final TenantVectorStore vectors;
    private final EmbeddingModel embeddingModel;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactionManager;
    private final AiProperties properties;
    private final JsonMapper json;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final Cache<String, AuditInsight> cache;
    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public FinancialAiAuditService(ChatClient.Builder chatClientBuilder, TenantVectorStore vectors,
                                   EmbeddingModel embeddingModel, JdbcClient jdbc,
                                   PlatformTransactionManager transactionManager, AiProperties properties, JsonMapper json) {
        this.chatClient = chatClientBuilder.defaultSystem(SYSTEM).build();
        this.vectors = vectors;
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.properties = properties;
        this.json = json;
        this.circuitBreaker = CircuitBreaker.of("ai-audit", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(java.time.Duration.ofSeconds(30))
                .ignoreException(ex -> ex instanceof LedgerException)
                .build());
        this.bulkhead = Bulkhead.of("ai-audit", BulkheadConfig.custom()
                .maxConcurrentCalls(4)
                .maxWaitDuration(java.time.Duration.ZERO)
                .build());
        this.cache = Caffeine.newBuilder().maximumSize(500).expireAfterWrite(properties.cacheTtl()).build();
    }

    public AuditInsight answer(AuditQuery query) {
        TenantInfo tenant = TenantContext.require().tenant();
        limit(tenant);
        YearMonth to = query.to() == null ? YearMonth.now() : YearMonth.parse(query.to());
        YearMonth from = query.from() == null ? to.minusMonths(5) : YearMonth.parse(query.from());
        if (from.isAfter(to)) {
            throw new LedgerException(LedgerErrorCode.VALIDATION_FAILED, "`from` is after `to`");
        }
        String currency = query.currency() == null ? tenant.baseCurrency() : query.currency();
        long version = jdbc.sql("SELECT coalesce(max(entry_no), 0) FROM journal_entries").query(Long.class).single();
        String cacheKey = tenant.id() + "|" + query.query().trim() + "|" + from + "|" + to + "|" + currency + "|" + version;
        AuditInsight cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            Usage usage = new Usage(cached.usage().model(), 0, 0, true);
            UUID id = persist(query.query(), cached.answer(), List.of(), cached.findings(), usage, null,
                    "NOT_APPLICABLE", null, 0);
            return new AuditInsight(id, cached.answer(), cached.findings(), List.of(), cached.sources(), usage);
        }
        budget(tenant);
        long started = System.nanoTime();
        List<Source> sources = retrieve(query.query());
        LedgerTools tools = new LedgerTools(jdbc, transactionManager, properties, from, to, currency);
        String evidence = evidence(query.query(), tools, from, to, currency);
        String user = query.query().trim() + "\nWindow " + from + " to " + to + " currency " + currency
                + (sources.isEmpty() ? "" : "\nPrior notes:\n" + sources.stream().map(Source::summary).reduce("", (a, b) -> a + "\n" + b))
                + "\nTool result:\n" + evidence;
        ChatResponse response;
        try {
            response = bulkhead.executeSupplier(() -> circuitBreaker.executeSupplier(() ->
                    chatClient.prompt().user(user).tools(tools).call().chatResponse()));
        } catch (CallNotPermittedException | BulkheadFullException e) {
            throw new LedgerException(LedgerErrorCode.AI_PROVIDER_UNAVAILABLE, "AI provider is unavailable", Map.of(), e);
        } catch (RuntimeException e) {
            if (e instanceof LedgerException ledger) {
                throw ledger;
            }
            throw new LedgerException(LedgerErrorCode.AI_PROVIDER_UNAVAILABLE, "AI provider call failed", Map.of(), e);
        }
        String answer = response.getResult().getOutput().getText();
        if (answer == null || answer.isBlank()) {
            answer = "The model returned no answer.";
        }
        int promptTokens = response.getMetadata().getUsage() == null || response.getMetadata().getUsage().getPromptTokens() == null
                ? 0 : response.getMetadata().getUsage().getPromptTokens();
        int completionTokens = response.getMetadata().getUsage() == null || response.getMetadata().getUsage().getCompletionTokens() == null
                ? 0 : response.getMetadata().getUsage().getCompletionTokens();
        String model = response.getMetadata().getModel() == null ? properties.provider() : response.getMetadata().getModel();
        Usage usage = new Usage(model, promptTokens, completionTokens, false);
        int latency = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
        UUID id = persist(query.query(), answer, tools.invocations(), tools.findings(), usage,
                tools.generatedSql(), tools.sqlStatus(), tools.rejectionReason(), latency);
        remember(query.query(), answer, tools.findings());
        AuditInsight insight = new AuditInsight(id, answer, tools.findings(), toolViews(tools), sources, usage);
        cache.put(cacheKey, insight);
        return insight;
    }

    /** Streams token, finding, and done events. Budget and rate-limit failures happen before the stream opens. */
    public SseEmitter stream(AuditQuery query) {
        AuditInsight insight = answer(query);
        SseEmitter emitter = new SseEmitter(60_000L);
        try {
            for (String chunk : chunks(insight.answer())) {
                emitter.send(SseEmitter.event().name("token").data(json.writeValueAsString(Map.of("text", chunk))));
            }
            for (Finding finding : insight.findings()) {
                emitter.send(SseEmitter.event().name("finding").data(json.writeValueAsString(finding)));
            }
            emitter.send(SseEmitter.event().name("done").data(json.writeValueAsString(insight)));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** Runs the one whitelist tool that matches the question, then lets the model narrate it. */
    private static String evidence(String query, LedgerTools tools, YearMonth from, YearMonth to, String currency) {
        int marker = query.indexOf("readonly:");
        if (marker >= 0) {
            return tools.runReadOnlyQuery(query.substring(marker + "readonly:".length()).trim());
        }
        return tools.spendingAnomalies(from.toString(), to.toString(), currency);
    }

    private void limit(TenantInfo tenant) {
        RateLimiter limiter = limiters.computeIfAbsent(tenant.slug(), slug -> RateLimiter.of(slug, RateLimiterConfig.custom()
                .limitForPeriod(properties.requestsPerMinute())
                .limitRefreshPeriod(java.time.Duration.ofMinutes(1))
                .timeoutDuration(java.time.Duration.ZERO)
                .build()));
        if (!limiter.acquirePermission()) {
            throw new LedgerException(LedgerErrorCode.RATE_LIMITED, "AI request rate exceeded for this tenant");
        }
    }

    private void budget(TenantInfo tenant) {
        long used = jdbc.sql("""
                SELECT coalesce(sum(coalesce(prompt_tokens, 0) + coalesce(completion_tokens, 0)), 0)
                FROM ai_audit_logs
                WHERE created_at >= date_trunc('month', now())
                """).query(Long.class).single();
        if (used >= properties.monthlyTokenBudget()) {
            throw new LedgerException(LedgerErrorCode.AI_BUDGET_EXCEEDED,
                    "Tenant " + tenant.slug() + " has used " + used + " tokens this month");
        }
    }

    private List<Source> retrieve(String query) {
        try {
            List<Source> sources = new ArrayList<>();
            for (Document document : vectors.search(query, 4)) {
                sources.add(new Source(document.getId(), document.getScore() == null ? 0 : document.getScore(),
                        document.getText() == null ? "" : document.getText()));
            }
            return sources;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private void remember(String query, String answer, List<Finding> findings) {
        try {
            String summary = query.trim() + " — " + (answer.length() > 500 ? answer.substring(0, 500) : answer);
            if (!findings.isEmpty()) {
                summary = summary + " Findings: " + findings.size();
            }
            vectors.addSummary(summary);
        } catch (RuntimeException ignored) {
            // A missed embedding must not fail an answer that already posted.
        }
    }

    private UUID persist(String query, String answer, List<Map<String, Object>> tools, List<Finding> findings,
                         Usage usage, String sql, String sqlStatus, String rejection, int latency) {
        String principal = TenantContext.require().principal() == null ? "unknown" : TenantContext.require().principal();
        UUID userId = UUID.nameUUIDFromBytes(principal.getBytes(StandardCharsets.UTF_8));
        String vector = null;
        try {
            vector = toVector(embeddingModel.embed(query));
        } catch (RuntimeException ignored) {
            vector = null;
        }
        return jdbc.sql("""
                INSERT INTO ai_audit_logs
                    (user_id, request_id, query_text, query_embedding, model_provider, model_name, tool_calls,
                     generated_sql, sql_validation_status, rejection_reason, response_summary, anomalies, status,
                     prompt_tokens, completion_tokens, latency_ms)
                VALUES
                    (:userId, :requestId, :query, CAST(:embedding AS public.vector), :provider, :model, CAST(:tools AS jsonb),
                     :sql, :sqlStatus, :rejection, :summary, CAST(:anomalies AS jsonb), 'SUCCEEDED',
                     :prompt, :completion, :latency)
                RETURNING id
                """)
                .param("userId", userId)
                .param("requestId", MDC.get("requestId"))
                .param("query", query)
                .param("embedding", vector)
                .param("provider", properties.provider())
                .param("model", usage.model())
                .param("tools", json.writeValueAsString(tools))
                .param("sql", sql)
                .param("sqlStatus", sqlStatus == null ? "NOT_APPLICABLE" : sqlStatus)
                .param("rejection", rejection)
                .param("summary", answer)
                .param("anomalies", json.writeValueAsString(findings))
                .param("prompt", usage.promptTokens())
                .param("completion", usage.completionTokens())
                .param("latency", latency)
                .query(UUID.class)
                .single();
    }

    private static String toVector(float[] values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values[i]);
        }
        return builder.append(']').toString();
    }

    private static List<ToolCallView> toolViews(LedgerTools tools) {
        List<ToolCallView> views = new ArrayList<>();
        for (Map<String, Object> invocation : tools.invocations()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) invocation.get("arguments");
            views.add(new ToolCallView(String.valueOf(invocation.get("name")), arguments));
        }
        return views;
    }

    private static List<String> chunks(String answer) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < answer.length(); i += 48) {
            parts.add(answer.substring(i, Math.min(answer.length(), i + 48)));
        }
        if (parts.isEmpty()) {
            parts.add("");
        }
        return parts;
    }

    public record AuditQuery(String query, String from, String to, String currency) {
    }

    public record AuditInsight(UUID queryId, String answer, List<Finding> findings, List<ToolCallView> toolCalls,
                               List<Source> sources, Usage usage) {
    }

    public record Finding(String type, String severity, String title, String detail, String accountCode, String category,
                          String period, String amount, String baseline, String currency) {
    }

    public record ToolCallView(String name, Map<String, Object> arguments) {
    }

    public record Source(String id, Double score, String summary) {
    }

    public record Usage(String model, int promptTokens, int completionTokens, boolean cached) {
    }
}
