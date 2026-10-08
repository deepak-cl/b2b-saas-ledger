package io.ledger.tenancy;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two DataSources:
 * <ul>
 *   <li>{@code controlPlaneDataSource} (from {@code spring.datasource.*}): tenant registry,
 *       control-plane Flyway migrations, provisioning.</li>
 *   <li>{@code tenantDataSource} (primary): routes to the current tenant's database and
 *       schema. JPA, Spring Data repositories, JdbcClient/JdbcTemplate and the transaction
 *       manager all use it.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenancyProperties.class)
public class TenancyConfiguration {

    @Bean
    @FlywayDataSource
    HikariDataSource controlPlaneDataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setPoolName("control-plane");
        ds.setMaximumPoolSize(5);
        return ds;
    }

    @Bean
    SecretResolver secretResolver(Environment environment) {
        return new SecretResolver(environment);
    }

    // Control-plane JdbcClients are created privately: a JdbcClient bean would make Boot
    // skip auto-configuring the tenant-scoped JdbcClient on the primary DataSource.
    @Bean
    TenantRegistry tenantRegistry(@Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource,
                                  TenancyProperties properties) {
        return new TenantRegistry(JdbcClient.create(controlPlaneDataSource), properties.registryCacheTtl());
    }

    @Bean
    TenantDataSourcePools tenantDataSourcePools(TenantRegistry registry, SecretResolver secrets, MeterRegistry meterRegistry) {
        return new TenantDataSourcePools(registry, secrets, meterRegistry);
    }

    @Bean
    TenantMigrator tenantMigrator(TenantDataSourcePools pools, TenancyProperties properties) {
        return new TenantMigrator(pools, properties);
    }

    @Bean
    @Primary
    TenantRoutingDataSource tenantDataSource(TenantDataSourcePools pools) {
        return new TenantRoutingDataSource(pools);
    }

    @Bean
    HibernatePropertiesCustomizer tenantHibernateProperties(TenantRegistry registry,
                                                            TenantRoutingDataSource tenantDataSource,
                                                            @Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource) {
        var connectionProvider = new SchemaPerTenantConnectionProvider(registry, tenantDataSource, controlPlaneDataSource);
        var identifierResolver = new TenantIdentifierResolver();
        return props -> {
            props.put(MultiTenancySettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
            props.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, identifierResolver);
            // No tenant is bound at boot; never let Hibernate open connections for metadata.
            props.put(JdbcSettings.ALLOW_METADATA_ON_BOOT, false);
        };
    }

    @Bean
    TenantContextTaskDecorator tenantContextTaskDecorator() {
        return new TenantContextTaskDecorator();
    }
}
