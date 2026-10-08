package io.ledger.analytics;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.ledger.analytics.BalanceSheetResponse.Line;
import io.ledger.analytics.BalanceSheetResponse.Section;
import io.ledger.tenancy.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the balance sheet from monthly rollups ({@code account_period_balances}), so a
 * 12-month comparative reads (accounts x 12) rows regardless of ledger size. Runs in one
 * REPEATABLE READ, read-only transaction: every number comes from the same snapshot, so
 * the sheet balances even while postings stream in.
 */
@Service
public class BalanceSheetService {

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshotTx;

    public BalanceSheetService(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.snapshotTx = new TransactionTemplate(transactionManager);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTx.setReadOnly(true);
        this.snapshotTx.setTimeout(30);
    }

    private record Row(String code, String name, String type, int period, BigDecimal debitBalance) {
    }

    public BalanceSheetResponse build(YearMonth asOf, int periods, String currency) {
        List<YearMonth> months = new ArrayList<>();
        for (int i = periods - 1; i >= 0; i--) {
            months.add(asOf.minusMonths(i));
        }
        List<Row> rows = snapshotTx.execute(status -> jdbc.sql("""
                        WITH periods AS (
                            SELECT ordinality - 1 AS idx, period_start::date AS period_start
                            FROM generate_series(CAST(:first AS date), CAST(:last AS date), interval '1 month')
                                 WITH ORDINALITY AS g(period_start, ordinality)
                        )
                        SELECT a.code, a.name, a.type, p.idx,
                               COALESCE((SELECT sum(b.net_change)
                                         FROM account_period_balances b
                                         WHERE b.account_id = a.id AND b.period_start <= p.period_start), 0) AS balance
                        FROM accounts a CROSS JOIN periods p
                        WHERE a.currency = :currency
                        ORDER BY a.code, p.idx
                        """)
                .param("first", months.getFirst().atDay(1))
                .param("last", months.getLast().atDay(1))
                .param("currency", currency)
                .query((rs, i) -> new Row(rs.getString("code"), rs.getString("name"), rs.getString("type"),
                        rs.getInt("idx"), rs.getBigDecimal("balance")))
                .list());

        Map<String, Map<String, BigDecimal[]>> byType = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Row r : rows) {
            names.put(r.code(), r.name());
            byType.computeIfAbsent(r.type(), t -> new LinkedHashMap<>())
                    .computeIfAbsent(r.code(), c -> zeros(periods))[r.period()] = r.debitBalance();
        }

        // Presentation signs: assets are debit-positive; liabilities and equity credit-positive.
        Section assets = section("ASSET", byType, names, periods, false);
        Section liabilities = section("LIABILITY", byType, names, periods, true);
        Section equity = section("EQUITY", byType, names, periods, true);
        List<BigDecimal> earnings = new ArrayList<>(Collections.nCopies(periods, BigDecimal.ZERO));
        for (String type : List.of("REVENUE", "EXPENSE")) {
            for (BigDecimal[] amounts : byType.getOrDefault(type, Map.of()).values()) {
                for (int i = 0; i < periods; i++) {
                    earnings.set(i, earnings.get(i).subtract(amounts[i]));
                }
            }
        }

        List<BigDecimal> liabilitiesAndEquity = new ArrayList<>();
        List<Boolean> balanced = new ArrayList<>();
        for (int i = 0; i < periods; i++) {
            BigDecimal total = liabilities.totals().get(i).add(equity.totals().get(i)).add(earnings.get(i));
            liabilitiesAndEquity.add(total);
            balanced.add(assets.totals().get(i).compareTo(total) == 0);
        }
        return new BalanceSheetResponse(TenantContext.require().tenant().slug(), currency,
                months.stream().map(YearMonth::toString).toList(), assets, liabilities, equity, earnings,
                liabilitiesAndEquity, balanced);
    }

    private static Section section(String type, Map<String, Map<String, BigDecimal[]>> byType, Map<String, String> names,
                                   int periods, boolean creditPositive) {
        List<Line> lines = new ArrayList<>();
        BigDecimal[] totals = zeros(periods);
        for (Map.Entry<String, BigDecimal[]> e : byType.getOrDefault(type, Map.of()).entrySet()) {
            BigDecimal[] amounts = e.getValue();
            if (java.util.Arrays.stream(amounts).allMatch(a -> a.signum() == 0)) {
                continue;
            }
            List<BigDecimal> presented = new ArrayList<>();
            for (int i = 0; i < periods; i++) {
                BigDecimal v = creditPositive ? amounts[i].negate() : amounts[i];
                presented.add(v);
                totals[i] = totals[i].add(v);
            }
            lines.add(new Line(e.getKey(), names.get(e.getKey()), presented));
        }
        return new Section(type, lines, List.of(totals));
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }
}
