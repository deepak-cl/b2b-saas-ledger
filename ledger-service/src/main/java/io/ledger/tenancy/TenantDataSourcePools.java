package io.ledger.tenancy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * One HikariCP pool per physical database ({@code tenant_datasources} row), created lazily
 * on first use. All SHARED tenants share the {@code shared-primary} pool; every ISOLATED
 * tenant gets its own.
 */
public class TenantDataSourcePools implements DisposableBean {

    /**
     * Search path a pooled connection has when no tenant is bound. Hikari restores it when
     * a connection returns to the pool, so a connection that escapes tenant binding sees
     * no tenant tables at all (fail closed).
     */
    static final String NEUTRAL_SCHEMA = "pg_catalog";

    private static final Logger log = LoggerFactory.getLogger(TenantDataSourcePools.class);

    private final TenantRegistry registry;
    private final SecretResolver secrets;
    private final MeterRegistry meterRegistry;
    private final Map<UUID, HikariDataSource> pools = new ConcurrentHashMap<>();

    public TenantDataSourcePools(TenantRegistry registry, SecretResolver secrets, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.secrets = secrets;
        this.meterRegistry = meterRegistry;
    }

    public DataSource pool(UUID datasourceId) {
        return pools.computeIfAbsent(datasourceId, this::createPool);
    }

    /** Unpooled access for schema migrations, so DDL never runs on (or pollutes) request pools. */
    public DataSource migrationDataSource(UUID datasourceId) {
        DataSourceDefinition def = definition(datasourceId);
        DriverManagerDataSource ds = new DriverManagerDataSource(def.jdbcUrl(), def.username(), secrets.resolve(def.secretRef()));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    public void evict(UUID datasourceId) {
        HikariDataSource pool = pools.remove(datasourceId);
        if (pool != null) {
            pool.close();
        }
    }

    private HikariDataSource createPool(UUID datasourceId) {
        DataSourceDefinition def = definition(datasourceId);
        if (!def.enabled()) {
            throw new IllegalStateException("Datasource " + def.name() + " is disabled");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("tenant-" + def.name());
        config.setJdbcUrl(def.jdbcUrl());
        config.setUsername(def.username());
        config.setPassword(secrets.resolve(def.secretRef()));
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(def.poolMaxSize());
        config.setMinimumIdle(def.poolMinIdle());
        config.setConnectionTimeout(5_000);
        config.setSchema(NEUTRAL_SCHEMA);
        // Autocommit stays on: setSchema() on checkout and Hikari's schema reset on return must
        // not open a transaction, or callers can no longer pick isolation/read-only mode.
        config.setAutoCommit(true);
        config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
        log.info("Creating connection pool {} ({})", config.getPoolName(), def.jdbcUrl());
        return new HikariDataSource(config);
    }

    private DataSourceDefinition definition(UUID datasourceId) {
        return registry.findDataSource(datasourceId)
                .orElseThrow(() -> new IllegalStateException("Unknown datasource " + datasourceId));
    }

    @Override
    public void destroy() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
