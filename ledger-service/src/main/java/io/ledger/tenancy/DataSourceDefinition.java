package io.ledger.tenancy;

import java.util.UUID;

/** A row of {@code tenant_datasources}: one physical database tenants can live in. */
public record DataSourceDefinition(
        UUID id,
        String name,
        TenantInfo.TenantTier kind,
        String jdbcUrl,
        String username,
        String secretRef,
        int poolMaxSize,
        int poolMinIdle,
        boolean enabled) {

    @Override
    public String toString() {
        return "DataSourceDefinition[id=%s, name=%s, kind=%s, jdbcUrl=%s]".formatted(id, name, kind, jdbcUrl);
    }
}
