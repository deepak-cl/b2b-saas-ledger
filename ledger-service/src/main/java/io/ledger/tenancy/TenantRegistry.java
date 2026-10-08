package io.ledger.tenancy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Read side of the control plane: tenants and their datasources. Lookups are cached
 * briefly; status changes made through this service evict immediately, changes made
 * elsewhere become visible within the TTL.
 */
public class TenantRegistry {

    private static final String TENANT_COLUMNS = """
            SELECT id, slug, display_name, tier, status, datasource_id, schema_name, base_currency
            FROM public.tenants
            """;

    private static final RowMapper<TenantInfo> TENANT_MAPPER = (rs, i) -> new TenantInfo(
            rs.getObject("id", UUID.class),
            rs.getString("slug"),
            rs.getString("display_name"),
            TenantInfo.TenantTier.valueOf(rs.getString("tier")),
            TenantInfo.TenantStatus.valueOf(rs.getString("status")),
            rs.getObject("datasource_id", UUID.class),
            rs.getString("schema_name"),
            rs.getString("base_currency"));

    private static final RowMapper<DataSourceDefinition> DATASOURCE_MAPPER = (rs, i) -> new DataSourceDefinition(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            TenantInfo.TenantTier.valueOf(rs.getString("kind")),
            rs.getString("jdbc_url"),
            rs.getString("username"),
            rs.getString("secret_ref"),
            rs.getInt("pool_max_size"),
            rs.getInt("pool_min_idle"),
            rs.getBoolean("enabled"));

    private final JdbcClient controlPlane;
    private final Cache<String, Optional<TenantInfo>> bySlug;

    public TenantRegistry(JdbcClient controlPlane, Duration cacheTtl) {
        this.controlPlane = controlPlane;
        this.bySlug = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(cacheTtl).build();
    }

    public Optional<TenantInfo> findBySlug(String slug) {
        return bySlug.get(slug, s -> controlPlane.sql(TENANT_COLUMNS + " WHERE slug = :slug")
                .param("slug", s)
                .query(TENANT_MAPPER)
                .optional());
    }

    public List<TenantInfo> findAll() {
        return controlPlane.sql(TENANT_COLUMNS + " ORDER BY slug").query(TENANT_MAPPER).list();
    }

    public Optional<DataSourceDefinition> findDataSource(UUID id) {
        return controlPlane.sql("SELECT * FROM public.tenant_datasources WHERE id = :id")
                .param("id", id)
                .query(DATASOURCE_MAPPER)
                .optional();
    }

    public void evict(String slug) {
        bySlug.invalidate(slug);
    }
}
