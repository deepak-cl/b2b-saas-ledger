package io.ledger.tenancy;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledger.tenancy")
public record TenancyProperties(
        /* How long tenant lookups are cached; status changes from other nodes surface within this. */
        @DefaultValue("30s") Duration registryCacheTtl,
        /* Run pending tenant migrations for every non-offboarded tenant at startup. */
        @DefaultValue("true") boolean migrateOnStartup,
        /* Parallel tenant migrations at startup. */
        @DefaultValue("4") int migrationParallelism,
        /* Concurrent postings per tenant before callers get TENANT_BUSY (protects shared pools). */
        @DefaultValue("8") int maxConcurrentPostingsPerTenant,
        @DefaultValue("2s") Duration postingQueueTimeout,
        /* Embedding vector size for tenant AI tables (Flyway placeholder). */
        @DefaultValue("1536") int embeddingDimensions) {
}
