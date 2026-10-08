package io.ledger.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * The tenant-scoped DataSource used by JPA and JDBC alike.
 *
 * <p>Routing happens on two axes: {@link #determineCurrentLookupKey()} picks the physical
 * database (the tenant's {@code datasource_id}, i.e. the shared cluster or a dedicated
 * database), and every connection handed out is bound to the tenant's schema via
 * {@link Connection#setSchema(String)} (Hikari tracks the change and restores the neutral
 * schema when the connection is returned).
 *
 * <p>There is deliberately no default target: without a bound tenant this DataSource
 * refuses to hand out connections.
 */
public class TenantRoutingDataSource extends AbstractRoutingDataSource {

    private final TenantDataSourcePools pools;

    public TenantRoutingDataSource(TenantDataSourcePools pools) {
        this.pools = pools;
        setTargetDataSources(Map.of());
        setLenientFallback(false);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.require().tenant().datasourceId();
    }

    @Override
    protected DataSource determineTargetDataSource() {
        return pools.pool((java.util.UUID) determineCurrentLookupKey());
    }

    @Override
    public Connection getConnection() throws SQLException {
        return getConnection(TenantContext.require().tenant());
    }

    @Override
    public Connection getConnection(String username, String password) {
        throw new UnsupportedOperationException("Tenant connections use datasource credentials from the control plane");
    }

    /** Connection for an explicit tenant, used by Hibernate's multi-tenant connection provider. */
    public Connection getConnection(TenantInfo tenant) throws SQLException {
        Connection connection = pools.pool(tenant.datasourceId()).getConnection();
        try {
            connection.setSchema(tenant.schemaName());
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }
}
