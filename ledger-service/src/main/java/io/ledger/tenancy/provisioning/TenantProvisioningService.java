package io.ledger.tenancy.provisioning;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import io.ledger.common.error.LedgerErrorCode;
import io.ledger.common.error.LedgerException;
import io.ledger.tenancy.TenantContext;
import io.ledger.tenancy.TenantContext.TenantScope;
import io.ledger.tenancy.TenantInfo;
import io.ledger.tenancy.TenantInfo.TenantStatus;
import io.ledger.tenancy.TenantInfo.TenantTier;
import io.ledger.tenancy.TenantMigrator;
import io.ledger.tenancy.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates tenants end to end: control-plane rows, schema migration on the tenant's
 * database, default chart of accounts, activation. Re-running for a tenant stuck in
 * PROVISIONING resumes where it failed (every step is idempotent).
 */
@Service
public class TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    private final TenantRegistry registry;
    private final TenantMigrator migrator;
    private final JdbcClient controlPlane;
    private final TransactionTemplate controlPlaneTx;
    private final JdbcClient tenantJdbc;
    private final TransactionTemplate tenantTx;

    public TenantProvisioningService(TenantRegistry registry, TenantMigrator migrator,
                                     @Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource,
                                     JdbcClient tenantJdbc, PlatformTransactionManager transactionManager) {
        this.registry = registry;
        this.migrator = migrator;
        this.controlPlane = JdbcClient.create(controlPlaneDataSource);
        this.controlPlaneTx = new TransactionTemplate(new DataSourceTransactionManager(controlPlaneDataSource));
        this.tenantJdbc = tenantJdbc;
        this.tenantTx = new TransactionTemplate(transactionManager);
    }

    public TenantInfo provision(ProvisionTenantRequest request) {
        registry.evict(request.slug());
        TenantInfo tenant = registry.findBySlug(request.slug())
                .map(existing -> {
                    if (existing.status() != TenantStatus.PROVISIONING) {
                        throw new LedgerException(LedgerErrorCode.TENANT_EXISTS, "Tenant " + request.slug() + " already exists");
                    }
                    log.info("Resuming provisioning of tenant {}", existing.slug());
                    return existing;
                })
                .orElseGet(() -> register(request));

        try {
            migrator.migrate(tenant);
            if (request.effectiveSeedChartOfAccounts()) {
                seedChartOfAccounts(tenant);
            }
            setStatus(tenant.slug(), TenantStatus.ACTIVE);
        } catch (RuntimeException e) {
            log.error("Provisioning of tenant {} failed; it stays PROVISIONING and can be retried", tenant.slug(), e);
            throw new LedgerException(LedgerErrorCode.TENANT_PROVISIONING_FAILED,
                    "Provisioning failed for tenant " + tenant.slug() + "; retry the request", Map.of(), e);
        }
        log.info("Tenant {} provisioned ({}, schema {})", tenant.slug(), tenant.tier(), tenant.schemaName());
        return registry.findBySlug(tenant.slug()).orElseThrow();
    }

    public TenantInfo setStatus(String slug, TenantStatus status) {
        int updated = controlPlane.sql("UPDATE public.tenants SET status = :status WHERE slug = :slug")
                .param("status", status.name())
                .param("slug", slug)
                .update();
        registry.evict(slug);
        if (updated == 0) {
            throw new LedgerException(LedgerErrorCode.TENANT_NOT_FOUND, "Tenant " + slug + " not found");
        }
        return registry.findBySlug(slug).orElseThrow();
    }

    private TenantInfo register(ProvisionTenantRequest request) {
        controlPlaneTx.executeWithoutResult(status -> {
            UUID datasourceId;
            if (request.effectiveTier() == TenantTier.ISOLATED) {
                var db = request.isolatedDatabase();
                datasourceId = controlPlane.sql("""
                                INSERT INTO public.tenant_datasources (name, kind, jdbc_url, username, secret_ref, pool_max_size, pool_min_idle)
                                VALUES (:name, 'ISOLATED', :url, :username, :secretRef, :poolMax, 1)
                                RETURNING id
                                """)
                        .param("name", "isolated-" + request.slug())
                        .param("url", db.jdbcUrl())
                        .param("username", db.username())
                        .param("secretRef", db.secretRef())
                        .param("poolMax", db.poolMaxSize() == null ? 10 : db.poolMaxSize())
                        .query(UUID.class)
                        .single();
            } else {
                datasourceId = controlPlane.sql("SELECT id FROM public.tenant_datasources WHERE name = 'shared-primary'")
                        .query(UUID.class)
                        .single();
            }
            controlPlane.sql("""
                            INSERT INTO public.tenants (slug, display_name, tier, datasource_id, schema_name, status, base_currency)
                            VALUES (:slug, :displayName, :tier, :datasourceId, :schema, 'PROVISIONING', :currency)
                            """)
                    .param("slug", request.slug())
                    .param("displayName", request.displayName())
                    .param("tier", request.effectiveTier().name())
                    .param("datasourceId", datasourceId)
                    .param("schema", "t_" + request.slug())
                    .param("currency", request.effectiveBaseCurrency())
                    .update();
        });
        registry.evict(request.slug());
        return registry.findBySlug(request.slug()).orElseThrow();
    }

    private void seedChartOfAccounts(TenantInfo tenant) {
        TenantContext.runAs(TenantScope.system(tenant), () -> tenantTx.executeWithoutResult(status -> {
            for (DefaultAccount a : DEFAULT_CHART) {
                tenantJdbc.sql("""
                                INSERT INTO accounts (code, name, type, normal_balance, currency, category, allow_negative)
                                VALUES (:code, :name, :type, :normal, :currency, :category, :allowNegative)
                                ON CONFLICT (code) DO NOTHING
                                """)
                        .param("code", a.code())
                        .param("name", a.name())
                        .param("type", a.type())
                        .param("normal", a.type().equals("ASSET") || a.type().equals("EXPENSE") ? "D" : "C")
                        .param("currency", tenant.baseCurrency())
                        .param("category", a.category())
                        .param("allowNegative", a.allowNegative())
                        .update();
            }
        }));
    }

    private record DefaultAccount(String code, String name, String type, String category, boolean allowNegative) {
    }

    private static final List<DefaultAccount> DEFAULT_CHART = List.of(
            new DefaultAccount("1000", "Operating Cash", "ASSET", "CASH", false),
            new DefaultAccount("1100", "Accounts Receivable", "ASSET", null, true),
            new DefaultAccount("1500", "Prepaid Expenses", "ASSET", null, true),
            new DefaultAccount("2000", "Accounts Payable", "LIABILITY", null, true),
            new DefaultAccount("2100", "Accrued Liabilities", "LIABILITY", null, true),
            new DefaultAccount("2500", "Deferred Revenue", "LIABILITY", null, true),
            new DefaultAccount("3000", "Owner's Equity", "EQUITY", null, true),
            new DefaultAccount("3100", "Retained Earnings", "EQUITY", null, true),
            new DefaultAccount("4000", "Subscription Revenue", "REVENUE", null, true),
            new DefaultAccount("4100", "Professional Services Revenue", "REVENUE", null, true),
            new DefaultAccount("5000", "Cost of Revenue", "EXPENSE", null, true),
            new DefaultAccount("6100", "Cloud Infrastructure", "EXPENSE", "CLOUD", true),
            new DefaultAccount("6200", "Payroll", "EXPENSE", "PAYROLL", true),
            new DefaultAccount("6300", "Software Subscriptions", "EXPENSE", "SAAS", true),
            new DefaultAccount("6400", "Marketing", "EXPENSE", "MARKETING", true),
            new DefaultAccount("6500", "Travel", "EXPENSE", "TRAVEL", true));
}
