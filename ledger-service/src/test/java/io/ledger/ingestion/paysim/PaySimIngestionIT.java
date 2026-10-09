package io.ledger.ingestion.paysim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import io.ledger.IntegrationTestSupport;
import io.ledger.ingestion.paysim.PaySimIngestionService.PaySimRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.repository.explore.JobExplorer;
import org.springframework.mock.web.MockMultipartFile;

class PaySimIngestionIT extends IntegrationTestSupport {

    private static final String CSV = """
            step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud
            1,CASH_IN,100.00,C1,0,100,M1,0,0,0,0
            1,PAYMENT,40.00,C1,100,60,M2,0,40,0,0
            25,TRANSFER,15.50,C2,50,34.5,C3,10,25.5,1,0
            25,CASH_OUT,10,C2,34.5,24.5,M4,0,0,0,0
            1,NOPE,5,C9,0,0,M9,0,0,0,0
            48,DEBIT,7.25,C4,20,12.75,M5,0,0,0,0
            """;

    @Autowired
    private PaySimIngestionService ingestion;

    @Autowired
    private JobExplorer jobExplorer;

    @Test
    void loadsAcrossPartitionsAndReplaysTheSameFile() throws Exception {
        ensureSharedTenant("paysim_co", "USD");
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "ledger-paysim-test");
        Files.createDirectories(dir);
        Path csv = dir.resolve("sample.csv");
        Files.writeString(csv, CSV);

        PaySimRun first = ingestion.run("paysim_co", csv);
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.posted()).isEqualTo(5); // the NOPE row is skipped
        assertThat(first.skipped()).isEqualTo(1);
        assertThat(first.replayed()).isZero();

        BigDecimal cash = (BigDecimal) queryRow("ledger", """
                SELECT b.debit_total - b.credit_total AS cash
                FROM t_paysim_co.account_balances b JOIN t_paysim_co.accounts a ON a.id = b.account_id
                WHERE a.code = '1800'
                """).get("cash");
        // CASH_IN 100 in; PAYMENT 40, TRANSFER 15.50, CASH_OUT 10, DEBIT 7.25 out.
        assertThat(cash).isEqualByComparingTo("27.2500");
        assertThat(queryRow("ledger", "SELECT count(*) AS n FROM t_paysim_co.journal_entries WHERE source = 'PAYSIM'").get("n"))
                .isEqualTo(5L);
        assertThat(queryRow("ledger", "SELECT * FROM t_paysim_co.ledger_verify_chain()")).isEmpty();

        PaySimRun second = ingestion.run("paysim_co", csv);
        assertThat(second.posted()).isZero();
        assertThat(second.replayed()).isEqualTo(5);
        assertThat(queryRow("ledger", "SELECT count(*) AS n FROM t_paysim_co.journal_entries WHERE source = 'PAYSIM'").get("n"))
                .isEqualTo(5L);
    }

    @Test
    void uploadReturnsAcceptedAndFinishes() throws Exception {
        ensureSharedTenant("paysim_http", "USD");
        MockMultipartFile file = new MockMultipartFile("file", "upload.csv", "text/csv", CSV.getBytes());
        var response = mvc.perform(multipart("/api/v1/admin/ingestion/paysim").file(file)
                        .param("tenant", "paysim_http").with(platformAdmin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobExecutionId").isNumber())
                .andReturn();
        long id = body(response).get("jobExecutionId").asLong();

        BatchStatus status = BatchStatus.UNKNOWN;
        for (int i = 0; i < 100 && (status.isRunning() || status == BatchStatus.UNKNOWN); i++) {
            Thread.sleep(50);
            var execution = jobExplorer.getJobExecution(id);
            if (execution != null) {
                status = execution.getStatus();
            }
        }
        assertThat(status).isEqualTo(BatchStatus.COMPLETED);

        mvc.perform(get("/api/v1/admin/ingestion/jobs/" + id).with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posted").value(5));
    }
}
