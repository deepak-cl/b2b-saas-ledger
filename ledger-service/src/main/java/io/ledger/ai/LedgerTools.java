package io.ledger.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.ledger.ai.FinancialAiAuditService.Finding;
import io.ledger.common.error.LedgerException;
import io.ledger.tenancy.TenantContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only functions the model may call. Each one is a fixed query in the caller's tenant
 * schema. The model never supplies SQL except through {@link #runReadOnlyQuery}, which is
 * parsed and then executed as the read-only role.
 */
public class LedgerTools {

    private final JdbcClient jdbc;
    private final TransactionTemplate readTx;
    private final AiProperties properties;
    private final YearMonth from;
    private final YearMonth to;
    private final String currency;
    private final List<Finding> findings = new ArrayList<>();
    private final List<Map<String, Object>> invocations = new ArrayList<>();
    private String generatedSql;
    private String sqlStatus = "NOT_APPLICABLE";
    private String rejectionReason;

    public LedgerTools(JdbcClient jdbc, PlatformTransactionManager transactionManager, AiProperties properties,
                       YearMonth from, YearMonth to, String currency) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.from = from;
        this.to = to;
        this.currency = currency;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public List<Finding> findings() {
        return List.copyOf(findings);
    }

    public List<Map<String, Object>> invocations() {
        return List.copyOf(invocations);
    }

    public String generatedSql() {
        return generatedSql;
    }

    public String sqlStatus() {
        return sqlStatus;
    }

    public String rejectionReason() {
        return rejectionReason;
    }

    @Tool(name = "accountBalances", description = "Current balance of each active account in one currency. Amounts are debit-positive.")
    public String accountBalances(String currency) {
        note("accountBalances", currency);
        String code = currency(currency);
        List<Map<String, Object>> rows = readTx.execute(status -> jdbc.sql("""
                SELECT a.code, a.name, a.type, b.balance
                FROM accounts a
                JOIN account_balances b ON b.account_id = a.id
                WHERE a.currency = :currency AND a.is_active
                ORDER BY a.code
                LIMIT 40
                """).param("currency", code).query().listOfRows());
        return format(rows);
    }

    @Tool(name = "monthlyActivity", description = "Expense totals by account and month (YYYY-MM) inside the window.")
    public String monthlyActivity(String from, String to, String currency) {
        note("monthlyActivity", from, to, currency);
        YearMonth start = month(from, this.from);
        YearMonth end = month(to, this.to);
        String code = currency(currency);
        List<Map<String, Object>> rows = readTx.execute(status -> jdbc.sql("""
                SELECT a.code, a.name, coalesce(a.category, '') AS category,
                       to_char(b.period_start, 'YYYY-MM') AS period, b.net_change AS amount
                FROM account_period_balances b
                JOIN accounts a ON a.id = b.account_id
                WHERE a.type = 'EXPENSE' AND a.currency = :currency
                  AND b.period_start >= :from AND b.period_start <= :to
                ORDER BY a.code, b.period_start
                LIMIT 80
                """).param("currency", code).param("from", start.atDay(1)).param("to", end.atDay(1)).query().listOfRows());
        return format(rows);
    }

    @Tool(name = "spendingAnomalies", description = "Expense months that are at least 1.5x the median of that account's other months in the window.")
    public String spendingAnomalies(String from, String to, String currency) {
        note("spendingAnomalies", from, to, currency);
        Map<String, List<MonthSpend>> byAccount = new LinkedHashMap<>();
        YearMonth start = month(from, this.from);
        YearMonth end = month(to, this.to);
        String code = currency(currency);
        List<Map<String, Object>> rows = readTx.execute(status -> jdbc.sql("""
                SELECT a.code, a.name, to_char(b.period_start, 'YYYY-MM') AS period, b.net_change AS amount
                FROM account_period_balances b
                JOIN accounts a ON a.id = b.account_id
                WHERE a.type = 'EXPENSE' AND a.currency = :currency
                  AND b.period_start >= :from AND b.period_start <= :to
                ORDER BY a.code, b.period_start
                """).param("currency", code).param("from", start.atDay(1)).param("to", end.atDay(1)).query().listOfRows());
        for (Map<String, Object> row : rows) {
            byAccount.computeIfAbsent(String.valueOf(row.get("code")), ignored -> new ArrayList<>())
                    .add(new MonthSpend(String.valueOf(row.get("name")), String.valueOf(row.get("period")),
                            (BigDecimal) row.get("amount")));
        }
        List<String> lines = new ArrayList<>();
        for (var entry : byAccount.entrySet()) {
            List<MonthSpend> months = entry.getValue();
            if (months.size() < 2) {
                continue;
            }
            for (MonthSpend candidate : months) {
                List<BigDecimal> others = months.stream().filter(month -> month != candidate).map(month -> month.amount).sorted().toList();
                BigDecimal median = others.get(others.size() / 2);
                if (median.signum() <= 0) {
                    continue;
                }
                if (candidate.amount.compareTo(median.multiply(new BigDecimal("1.5"))) > 0
                        && candidate.amount.subtract(median).abs().compareTo(BigDecimal.ONE) >= 0) {
                    String severity = candidate.amount.compareTo(median.multiply(new BigDecimal("2"))) > 0 ? "HIGH" : "MEDIUM";
                    findings.add(new Finding("ANOMALY", severity,
                            candidate.name + " spike in " + candidate.period,
                            "Spend " + money(candidate.amount) + " versus a median of " + money(median) + " in the other months.",
                            entry.getKey(), null, candidate.period, money(candidate.amount), money(median), code));
                    lines.add(entry.getKey() + " " + candidate.period + " " + money(candidate.amount)
                            + " median " + money(median) + " " + severity);
                }
            }
        }
        return lines.isEmpty() ? "No spending anomalies in " + start + " to " + end + "." : String.join("\n", lines);
    }

    @Tool(name = "comparePeriods", description = "Net change of one account in two months (YYYY-MM).")
    public String comparePeriods(String accountCode, String periodA, String periodB) {
        note("comparePeriods", accountCode, periodA, periodB);
        if (accountCode == null || !accountCode.matches("[A-Za-z0-9]{1,32}")) {
            return "accountCode is required";
        }
        YearMonth a = month(periodA, from);
        YearMonth b = month(periodB, to);
        List<Map<String, Object>> rows = readTx.execute(status -> jdbc.sql("""
                SELECT to_char(b.period_start, 'YYYY-MM') AS period, b.net_change AS amount
                FROM account_period_balances b
                JOIN accounts a ON a.id = b.account_id
                WHERE a.code = :code AND b.period_start IN (:a, :b)
                ORDER BY b.period_start
                """).param("code", accountCode).param("a", a.atDay(1)).param("b", b.atDay(1)).query().listOfRows());
        return format(rows);
    }

    @Tool(name = "runReadOnlyQuery", description = "Optional: one SELECT against the tenant ledger tables. Writes, other schemas, and system functions are rejected.")
    public String runReadOnlyQuery(String sql) {
        note("runReadOnlyQuery", sql);
        String schema = TenantContext.require().tenant().schemaName();
        try {
            String accepted = ReadOnlySql.validate(sql, schema);
            generatedSql = accepted;
            sqlStatus = "ACCEPTED";
            List<Map<String, Object>> rows = readTx.execute(status -> {
                jdbc.sql("SET LOCAL ROLE ledger_ai_reader").update();
                jdbc.sql("SELECT set_config('statement_timeout', :timeout, true)")
                        .param("timeout", properties.statementTimeout().toMillis() + "ms")
                        .query().singleValue();
                return jdbc.sql("SELECT * FROM (" + accepted + ") ai_q LIMIT " + properties.sqlRowLimit())
                        .query().listOfRows();
            });
            return format(rows);
        } catch (LedgerException e) {
            generatedSql = sql;
            sqlStatus = "REJECTED";
            rejectionReason = e.getMessage();
            return "Rejected: " + e.getMessage();
        }
    }

    private void note(String name, String... args) {
        Map<String, Object> arguments = new TreeMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null && !args[i].isBlank()) {
                arguments.put("arg" + i, args[i]);
            }
        }
        invocations.add(Map.of("name", name, "arguments", arguments));
    }

    private String currency(String requested) {
        if (requested != null && requested.matches("^[A-Z]{3}$")) {
            return requested;
        }
        return currency;
    }

    private static YearMonth month(String requested, YearMonth fallback) {
        if (requested != null && requested.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            return YearMonth.parse(requested);
        }
        return fallback;
    }

    private static String format(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "No rows.";
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            row.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).forEach(entry -> {
                Object value = entry.getValue();
                cells.add(entry.getKey() + "=" + (value instanceof BigDecimal money ? money(money) : value));
            });
            lines.add(String.join(" ", cells));
        }
        return String.join("\n", lines);
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private record MonthSpend(String name, String period, BigDecimal amount) {
    }

}
