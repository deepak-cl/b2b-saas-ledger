package io.ledger.ledger.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.ledger.ledger.Direction;

public record TransactionResponse(
        UUID id,
        long entryNo,
        String idempotencyKey,
        LocalDate effectiveDate,
        OffsetDateTime postedAt,
        String description,
        String source,
        String externalRef,
        Map<String, Object> metadata,
        List<Line> lines,
        List<CurrencyTotal> totals,
        /* Position and SHA-256 link of this entry in the tenant's tamper-evident chain. */
        Long chainSeq,
        String entryHash) {

    public record Line(int lineNo, String accountCode, String accountName, Direction direction, BigDecimal amount,
                       String currency, String memo) {
    }

    public record CurrencyTotal(String currency, BigDecimal debits, BigDecimal credits) {
    }
}
