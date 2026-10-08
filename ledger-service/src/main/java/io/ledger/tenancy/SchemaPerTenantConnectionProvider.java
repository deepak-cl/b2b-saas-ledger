package io.ledger.tenancy;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;

/**
 * Hibernate's view of tenancy: the tenant identifier is the tenant slug, resolved to a
 * connection on the tenant's database with its schema selected.
 */
public class SchemaPerTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private final TenantRegistry registry;
    private final TenantRoutingDataSource routingDataSource;
    private final DataSource controlPlaneDataSource;

    public SchemaPerTenantConnectionProvider(TenantRegistry registry, TenantRoutingDataSource routingDataSource,
                                             DataSource controlPlaneDataSource) {
        this.registry = registry;
        this.routingDataSource = routingDataSource;
        this.controlPlaneDataSource = controlPlaneDataSource;
    }

    /** Only used by Hibernate for tenant-less metadata work; never exposes tenant tables. */
    @Override
    public Connection getAnyConnection() throws SQLException {
        return controlPlaneDataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantSlug) throws SQLException {
        if (TenantIdentifierResolver.NO_TENANT.equals(tenantSlug)) {
            throw new TenantContext.TenantContextMissingException();
        }
        TenantInfo tenant = registry.findBySlug(tenantSlug)
                .orElseThrow(() -> new IllegalStateException("Unknown tenant " + tenantSlug));
        return routingDataSource.getConnection(tenant);
    }

    @Override
    public void releaseConnection(String tenantSlug, Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean handlesConnectionSchema() {
        return true;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return unwrapType.isInstance(this);
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        if (isUnwrappableAs(unwrapType)) {
            return unwrapType.cast(this);
        }
        throw new IllegalArgumentException("Cannot unwrap to " + unwrapType);
    }
}
