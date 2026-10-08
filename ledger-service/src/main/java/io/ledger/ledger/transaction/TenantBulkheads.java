package io.ledger.ledger.transaction;

import java.util.function.Supplier;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.ledger.tenancy.TenancyProperties;
import org.springframework.stereotype.Component;

/**
 * Per-tenant concurrency limit for postings. Tenants on the shared cluster share one
 * connection pool; without this, one tenant's burst (e.g. a misbehaving integration)
 * could hold every connection and starve all others. Excess callers wait briefly, then
 * get TENANT_BUSY (503, retryable).
 */
@Component
public class TenantBulkheads {

    private final BulkheadRegistry registry;

    public TenantBulkheads(TenancyProperties properties) {
        this.registry = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxConcurrentPostingsPerTenant())
                .maxWaitDuration(properties.postingQueueTimeout())
                .build());
    }

    public <T> T execute(String tenantSlug, Supplier<T> work) {
        return registry.bulkhead("posting-" + tenantSlug).executeSupplier(work);
    }
}
