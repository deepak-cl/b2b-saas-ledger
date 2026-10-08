package io.ledger.tenancy;

import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs {@code db/migration/tenant} against one tenant schema, on the tenant's own database. */
public class TenantMigrator {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrator.class);

    private final TenantDataSourcePools pools;
    private final TenancyProperties properties;

    public TenantMigrator(TenantDataSourcePools pools, TenancyProperties properties) {
        this.pools = pools;
        this.properties = properties;
    }

    public MigrateResult migrate(TenantInfo tenant) {
        MigrateResult result = Flyway.configure()
                .dataSource(pools.migrationDataSource(tenant.datasourceId()))
                .schemas(tenant.schemaName())
                .defaultSchema(tenant.schemaName())
                .createSchemas(true)
                .locations("classpath:db/migration/tenant")
                .placeholders(Map.of("embedding_dimensions", String.valueOf(properties.embeddingDimensions())))
                .load()
                .migrate();
        if (result.migrationsExecuted > 0) {
            log.info("Tenant {}: applied {} migration(s), schema {} now at version {}",
                    tenant.slug(), result.migrationsExecuted, tenant.schemaName(), result.targetSchemaVersion);
        }
        return result;
    }
}
