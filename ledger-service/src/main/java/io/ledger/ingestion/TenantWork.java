package io.ledger.ingestion;

import io.ledger.common.error.LedgerErrorCode;
import io.ledger.common.error.LedgerException;
import io.ledger.tenancy.TenantInfo;
import io.ledger.tenancy.TenantRegistry;

/** Resolves an active tenant for background work. Callers bind {@code TenantScope.system}. */
public final class TenantWork {

    private TenantWork() {
    }

    public static TenantInfo requireActive(TenantRegistry registry, String slug) {
        TenantInfo tenant = registry.findBySlug(slug)
                .orElseThrow(() -> new LedgerException(LedgerErrorCode.TENANT_NOT_FOUND, "Tenant " + slug + " not found"));
        if (!tenant.isActive()) {
            throw new LedgerException(LedgerErrorCode.TENANT_UNAVAILABLE, "Tenant " + slug + " is " + tenant.status());
        }
        return tenant;
    }
}
