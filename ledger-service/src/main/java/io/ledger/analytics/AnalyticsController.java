package io.ledger.analytics;

import java.time.YearMonth;

import io.ledger.common.error.LedgerErrorCode;
import io.ledger.common.error.LedgerException;
import io.ledger.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Aggregated financial statements")
public class AnalyticsController {

    private final BalanceSheetService balanceSheet;

    public AnalyticsController(BalanceSheetService balanceSheet) {
        this.balanceSheet = balanceSheet;
    }

    @GetMapping("/balance-sheet")
    @PreAuthorize("@tenantSecurity.has('LEDGER_READ')")
    @Operation(summary = "Comparative balance sheet at month ends")
    public BalanceSheetResponse balanceSheet(
            @Parameter(description = "Last month to report, YYYY-MM (default: current month)", example = "2026-10")
            @RequestParam(required = false) YearMonth asOf,
            @Parameter(description = "Number of month-end columns, 1-24", example = "3")
            @RequestParam(defaultValue = "3") int periods,
            @Parameter(description = "ISO currency (default: tenant base currency)", example = "USD")
            @RequestParam(required = false) String currency) {
        if (periods < 1 || periods > 24) {
            throw new LedgerException(LedgerErrorCode.VALIDATION_FAILED, "periods must be between 1 and 24");
        }
        String ccy = currency != null ? currency : TenantContext.require().tenant().baseCurrency();
        if (!ccy.matches("^[A-Z]{3}$")) {
            throw new LedgerException(LedgerErrorCode.VALIDATION_FAILED, "currency must be an ISO 4217 code");
        }
        return balanceSheet.build(asOf != null ? asOf : YearMonth.now(), periods, ccy);
    }
}
