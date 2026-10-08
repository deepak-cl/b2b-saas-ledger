package io.ledger.ledger.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import io.ledger.ledger.Direction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** A double-entry transaction: two or more lines whose debits equal credits per currency. */
@BalancedEntry
public record PostTransactionRequest(
        @NotNull @Schema(example = "2026-10-08") LocalDate effectiveDate,
        @NotBlank @Size(max = 500) @Schema(example = "AWS invoice 2026-09") String description,
        @Size(max = 128) String externalRef,
        @Size(max = 50, message = "at most 50 metadata keys") Map<String, Object> metadata,
        @NotNull @Size(min = 2, max = 500) List<@Valid @NotNull Line> lines) {

    public record Line(
            @NotBlank @Size(max = 32) @Schema(example = "6100") String accountCode,
            @NotNull Direction direction,
            @NotNull @Positive @Digits(integer = 16, fraction = 4) @Schema(example = "1250.00", type = "string")
            BigDecimal amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$") @Schema(example = "USD") String currency,
            @Size(max = 500) String memo) {
    }
}
