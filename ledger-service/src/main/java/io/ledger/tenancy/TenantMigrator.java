package io.ledger.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
        grantReader(tenant);
        if (result.migrationsExecuted > 0) {
            log.info("Tenant {}: applied {} migration(s), schema {} now at version {}",
                    tenant.slug(), result.migrationsExecuted, tenant.schemaName(), result.targetSchemaVersion);
        }
        return result;
    }

    /**
     * The optional natural-language SQL tool runs as this role, which can only read the
     * tenant schema. The role is cluster-wide; the grants are per database.
     */
    private void grantReader(TenantInfo tenant) {
        String schema = tenant.schemaName();
        if (!schema.matches("t_[a-z][a-z0-9_]*")) {
            throw new IllegalStateException("Refusing to grant on schema " + schema);
        }
        try (Connection connection = pools.migrationDataSource(tenant.datasourceId()).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    DO $body$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledger_ai_reader') THEN
                            CREATE ROLE ledger_ai_reader NOLOGIN;
                        END IF;
                    END
                    $body$
                    """);
            statement.execute("GRANT ledger_ai_reader TO CURRENT_USER");
            statement.execute("GRANT USAGE ON SCHEMA " + schema + " TO ledger_ai_reader");
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO ledger_ai_reader");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not grant ledger_ai_reader on " + schema, e);
        }
    }
}
