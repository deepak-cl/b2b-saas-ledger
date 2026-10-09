package io.ledger.ingestion.recon;

import io.ledger.ingestion.IngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly, after the books are quiet. One tenant's failure is recorded and does not stop the others. */
@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final IngestionProperties properties;
    private final ReconciliationService reconciliation;

    public ReconciliationScheduler(IngestionProperties properties, ReconciliationService reconciliation) {
        this.properties = properties;
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "${ledger.ingestion.reconciliation.cron:0 15 2 * * *}")
    public void nightly() {
        if (!properties.reconciliation().enabled()) {
            return;
        }
        for (ReconciliationService.Report report : reconciliation.reconcileAll()) {
            log.info("Reconciliation {} for {}: balances={} chain={} periods={}",
                    report.status(), report.tenant(), report.balanceMismatches(), report.chainBreaks(),
                    report.periodMismatches());
        }
    }
}
