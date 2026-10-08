package io.ledger.tenancy.provisioning;

import io.ledger.tenancy.TenantInfo.TenantTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProvisionTenantRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{2,47}$",
                message = "lowercase letters, digits and underscores; 3-48 chars; starts with a letter")
        String slug,
        @NotBlank @Size(max = 200) String displayName,
        TenantTier tier,
        @Pattern(regexp = "^[A-Z]{3}$") String baseCurrency,
        Boolean seedChartOfAccounts,
        @Valid IsolatedDatabase isolatedDatabase) {

    /** Connection details of an existing, empty database for the ISOLATED tier. */
    public record IsolatedDatabase(
            @NotBlank @Pattern(regexp = "^jdbc:postgresql://.+") String jdbcUrl,
            @NotBlank @Size(max = 63) String username,
            @NotBlank @Pattern(regexp = "^(env|file):.+", message = "must be env:NAME or file:/path") String secretRef,
            @Min(1) @Max(200) Integer poolMaxSize) {
    }

    public TenantTier effectiveTier() {
        return tier == null ? TenantTier.SHARED : tier;
    }

    public String effectiveBaseCurrency() {
        return baseCurrency == null ? "USD" : baseCurrency;
    }

    public boolean effectiveSeedChartOfAccounts() {
        return seedChartOfAccounts == null || seedChartOfAccounts;
    }

    @AssertTrue(message = "isolatedDatabase is required for tier ISOLATED and not allowed for SHARED")
    public boolean isIsolatedDatabaseConsistent() {
        return (effectiveTier() == TenantTier.ISOLATED) == (isolatedDatabase != null);
    }
}
