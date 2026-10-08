package io.ledger.tenancy.provisioning;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import io.ledger.tenancy.TenancyProperties;
import io.ledger.tenancy.TenantInfo;
import io.ledger.tenancy.TenantInfo.TenantStatus;
import io.ledger.tenancy.TenantMigrator;
import io.ledger.tenancy.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Brings every tenant schema up to the latest tenant migration at startup. A failing
 * tenant is logged and skipped so one broken schema cannot take the service down for
 * everyone; Flyway's own lock makes concurrent runs from several nodes safe.
 */
@Component
public class TenantSchemaUpgrader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaUpgrader.class);

    private final TenantRegistry registry;
    private final TenantMigrator migrator;
    private final TenancyProperties properties;

    public TenantSchemaUpgrader(TenantRegistry registry, TenantMigrator migrator, TenancyProperties properties) {
        this.registry = registry;
        this.migrator = migrator;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.migrateOnStartup()) {
            return;
        }
        List<TenantInfo> tenants = registry.findAll().stream()
                .filter(t -> t.status() == TenantStatus.ACTIVE || t.status() == TenantStatus.SUSPENDED)
                .toList();
        Semaphore permits = new Semaphore(properties.migrationParallelism());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = tenants.stream().<Future<?>>map(t -> executor.submit(() -> {
                permits.acquireUninterruptibly();
                try {
                    migrator.migrate(t);
                } catch (RuntimeException e) {
                    log.error("Migration failed for tenant {}; tenant left on its current schema version", t.slug(), e);
                } finally {
                    permits.release();
                }
            })).toList();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        log.info("Tenant schemas checked: {}", tenants.size());
    }
}
