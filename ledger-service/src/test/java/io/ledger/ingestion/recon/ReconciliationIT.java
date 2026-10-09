package io.ledger.ingestion.recon;

import static org.assertj.core.api.Assertions.assertThat;

import io.ledger.IntegrationTestSupport;
import io.ledger.ingestion.recon.ReconciliationService.Report;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationIT extends IntegrationTestSupport {

    @Autowired
    private ReconciliationService reconciliation;

    @Test
    void cleanLedgerIsOkAndATamperedBalanceIsAMismatch() throws Exception {
        ensureSharedTenant("recon_co", "USD");
        mvc.perform(postTransaction("recon_co", "recon-seed-0001", transaction("Seed",
                        line("1000", "DEBIT", "100", "USD"), line("3000", "CREDIT", "100", "USD")))
                        .with(user("recon-owner", "/tenants/recon_co/OWNER")))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(200, 201));

        Report ok = reconciliation.reconcile("recon_co");
        assertThat(ok.status()).isEqualTo("OK");
        assertThat(ok.balanceMismatches()).isZero();
        assertThat(ok.chainBreaks()).isZero();
        assertThat(ok.periodMismatches()).isZero();

        exec("ledger", """
                UPDATE t_recon_co.account_balances
                SET debit_total = debit_total + 1
                WHERE account_id = (SELECT id FROM t_recon_co.accounts WHERE code = '1000')
                """);
        Report mismatch = reconciliation.reconcile("recon_co");
        assertThat(mismatch.status()).isEqualTo("MISMATCH");
        assertThat(mismatch.balanceMismatches()).isEqualTo(1);
        assertThat(mismatch.chainBreaks()).isZero();

        assertThat(queryRow("ledger", "SELECT status FROM public.reconciliation_runs WHERE id = '" + mismatch.id() + "'")
                .get("status")).isEqualTo("MISMATCH");
    }
}
