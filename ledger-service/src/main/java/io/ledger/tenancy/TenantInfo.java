package io.ledger.tenancy;

import java.util.UUID;

/** Immutable control-plane view of a tenant, as cached by {@link TenantRegistry}. */
public record TenantInfo(
        UUID id,
        String slug,
        String displayName,
        TenantTier tier,
        TenantStatus status,
        UUID datasourceId,
        String schemaName,
        String baseCurrency) {

    public enum TenantTier { SHARED, ISOLATED }

    public enum TenantStatus { PROVISIONING, ACTIVE, SUSPENDED, OFFBOARDED }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }
}
