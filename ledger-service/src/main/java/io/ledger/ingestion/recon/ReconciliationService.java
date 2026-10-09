package io.ledger.ingestion.recon;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import io.ledger.ingestion.TenantWork;
import io.ledger.tenancy.TenantContext;
import io.ledger.tenancy.TenantContext.TenantScope;
import io.ledger.tenancy.TenantInfo;
import io.ledger.tenancy.TenantRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recomputes each tenant's balances from its lines under REPEATABLE READ, read only, and
 * compares them to {@code account_balances} and {@code account_period_balances}. Also runs
 * {@code ledger_verify_chain()}. The check and the result row use different databases, so a
 * mismatch is recorded even though the tenant transaction cannot write.
 */
@Service
public class ReconciliationService {

    public record Report(UUID id, String tenant, String status, int balanceMismatches, int chainBreaks,
                         int periodMismatches, String detail) {
    }

    private final JdbcClient control;
    private final JdbcClient tenantJdbc;
    private final TransactionTemplate readTx;
    private final TenantRegistry registry;

    public ReconciliationService(@Qualifier("controlPlaneDataSource") DataSource controlPlane, JdbcClient tenantJdbc,
                                 PlatformTransactionManager transactionManager, TenantRegistry registry) {
        this.control = JdbcClient.create(controlPlane);
        this.tenantJdbc = tenantJdbc;
        this.registry = registry;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTx.setReadOnly(true);
    }

    public List<Report> reconcileAll() {
        return registry.findAll().stream()
                .filter(TenantInfo::isActive)
                .map(t -> reconcile(t.slug()))
                .toList();
    }

    public Report reconcile(String slug) {
        TenantInfo tenant = TenantWork.requireActive(registry, slug);
        UUID id = UUID.randomUUID();
        control.sql("""
                        INSERT INTO public.reconciliation_runs (id, tenant_id, status)
                        VALUES (:id, :tenant, 'RUNNING')
                        """)
                .param("id", id)
                .param("tenant", tenant.id())
                .update();
        try {
            Report report = TenantContext.callAs(TenantScope.system(tenant), () -> readTx.execute(status -> check(id, tenant)));
            store(report);
            return report;
        } catch (RuntimeException e) {
            Report failed = new Report(id, tenant.slug(), "FAILED", 0, 0, 0, rootMessage(e));
            store(failed);
            return failed;
        }
    }

    private Report check(UUID id, TenantInfo tenant) {
        int balances = count("""
                SELECT count(*) FROM account_balances b
                LEFT JOIN (
                    SELECT account_id,
                           sum(amount) FILTER (WHERE direction = 'D') AS d,
                           sum(amount) FILTER (WHERE direction = 'C') AS c
                    FROM ledger_entries GROUP BY account_id
                ) e ON e.account_id = b.account_id
                WHERE b.debit_total <> COALESCE(e.d, 0) OR b.credit_total <> COALESCE(e.c, 0)
                """);
        int periods = count("""
                SELECT count(*) FROM (
                    SELECT account_id, date_trunc('month', effective_date)::date AS p,
                           sum(amount) FILTER (WHERE direction = 'D') AS d,
                           sum(amount) FILTER (WHERE direction = 'C') AS c,
                           count(*) AS n
                    FROM ledger_entries GROUP BY 1, 2
                ) e
                FULL JOIN account_period_balances b
                    ON b.account_id = e.account_id AND b.period_start = e.p
                WHERE COALESCE(b.debit_total, 0) <> COALESCE(e.d, 0)
                   OR COALESCE(b.credit_total, 0) <> COALESCE(e.c, 0)
                   OR COALESCE(b.line_count, 0) <> COALESCE(e.n, 0)
                """);
        int chain = count("SELECT count(*) FROM ledger_verify_chain()");
        String status = balances == 0 && periods == 0 && chain == 0 ? "OK" : "MISMATCH";
        return new Report(id, tenant.slug(), status, balances, chain, periods, "");
    }

    private int count(String sql) {
        Long n = tenantJdbc.sql(sql).query(Long.class).single();
        return n == null ? 0 : Math.toIntExact(n);
    }

    private void store(Report report) {
        control.sql("""
                        UPDATE public.reconciliation_runs
                        SET finished_at = now(), status = :status,
                            balance_mismatches = :balances, chain_breaks = :chain, period_mismatches = :periods,
                            detail = CAST(:detail AS jsonb)
                        WHERE id = :id
                        """)
                .param("status", report.status())
                .param("balances", report.balanceMismatches())
                .param("chain", report.chainBreaks())
                .param("periods", report.periodMismatches())
                .param("detail", report.detail().isBlank() ? "{}" : "{\"error\":" + json(report.detail()) + "}")
                .param("id", report.id())
                .update();
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return message.length() > 400 ? message.substring(0, 400) : message;
    }
}
