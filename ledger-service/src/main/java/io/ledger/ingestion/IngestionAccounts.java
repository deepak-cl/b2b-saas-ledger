package io.ledger.ingestion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chart rows created on demand for imported data. They sit beside the default chart and
 * never reuse the overdraft-guarded cash account, so a historical import cannot trip
 * {@code INSUFFICIENT_FUNDS}.
 */
@Component
public class IngestionAccounts {

    public static final String SEC_ASSETS = "1600";
    public static final String SEC_LIABILITIES = "2600";
    public static final String SEC_EQUITY = "3200";
    public static final String SEC_PLUG = "3900";
    public static final String PAYSIM_CASH = "1800";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public IngestionAccounts(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public void ensure(String code, String name, String type, String currency, String externalRef) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                        INSERT INTO accounts (code, name, type, normal_balance, currency, allow_negative, external_ref)
                        VALUES (:code, :name, :type, :normal, :currency, TRUE, :externalRef)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("code", code)
                .param("name", name)
                .param("type", type)
                .param("normal", "ASSET".equals(type) || "EXPENSE".equals(type) ? "D" : "C")
                .param("currency", currency)
                .param("externalRef", externalRef)
                .update());
    }

    /** Opens monthly partitions (and their accounting periods) so historical dates can be posted. */
    public void ensurePartitions(java.time.LocalDate from, java.time.LocalDate to) {
        java.time.LocalDate start = from.withDayOfMonth(1);
        java.time.LocalDate end = to.withDayOfMonth(1);
        if (end.isBefore(start)) {
            java.time.LocalDate swap = start;
            start = end;
            end = swap;
        }
        java.time.LocalDate fromMonth = start;
        java.time.LocalDate toMonth = end;
        tx.executeWithoutResult(status -> jdbc.sql("SELECT ledger_ensure_partitions(:from, :to)")
                .param("from", fromMonth)
                .param("to", toMonth)
                .query(Integer.class)
                .single());
    }
}
