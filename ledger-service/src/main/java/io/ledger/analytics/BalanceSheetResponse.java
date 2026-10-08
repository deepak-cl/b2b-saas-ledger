package io.ledger.analytics;

import java.math.BigDecimal;
import java.util.List;

/**
 * Comparative balance sheet. Every list of amounts is aligned with {@link #periods()}
 * (oldest first), each value being the balance at the end of that month.
 */
public record BalanceSheetResponse(
        String tenant,
        String currency,
        List<String> periods,
        Section assets,
        Section liabilities,
        Section equity,
        /* Revenue minus expenses not yet closed into retained earnings. */
        List<BigDecimal> currentEarnings,
        List<BigDecimal> totalLiabilitiesAndEquity,
        /* assets == liabilities + equity + current earnings, per period. */
        List<Boolean> balanced) {

    public record Section(String type, List<Line> lines, List<BigDecimal> totals) {
    }

    public record Line(String accountCode, String accountName, List<BigDecimal> amounts) {
    }
}
