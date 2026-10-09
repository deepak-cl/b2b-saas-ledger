package io.ledger.ingestion.sec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.ledger.IntegrationTestSupport;
import io.ledger.ingestion.sec.SecIngestionService.SecIngestResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class SecIngestionIT extends IntegrationTestSupport {

    private static final String FACTS = """
            {"cik":"320193","entityName":"Apple Inc.","facts":{"us-gaap":{
              "Assets":{"units":{"USD":[
                {"end":"2020-12-31","val":1000,"accn":"0001-20","fy":2020,"fp":"FY","form":"10-K","filed":"2021-02-01"},
                {"end":"2024-12-31","val":1500,"accn":"0001-24","fy":2024,"fp":"FY","form":"10-K","filed":"2025-02-01"}]}},
              "Liabilities":{"units":{"USD":[
                {"end":"2020-12-31","val":400,"accn":"0001-20","fy":2020,"fp":"FY","form":"10-K","filed":"2021-02-01"},
                {"end":"2024-12-31","val":500,"accn":"0001-24","fy":2024,"fp":"FY","form":"10-K","filed":"2025-02-01"}]}},
              "StockholdersEquity":{"units":{"USD":[
                {"end":"2020-12-31","val":600,"accn":"0001-20","fy":2020,"fp":"FY","form":"10-K","filed":"2021-02-01"},
                {"end":"2024-12-31","val":900,"accn":"0001-24","fy":2024,"fp":"FY","form":"10-K","filed":"2025-02-01"}]}}
            }}}
            """;

    private static final HttpServer SEC;
    private static final AtomicReference<String> USER_AGENT = new AtomicReference<>();

    static {
        try {
            SEC = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SEC.createContext("/api/xbrl/companyfacts/CIK0000320193.json", exchange -> {
            USER_AGENT.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = FACTS.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        SEC.start();
    }

    @DynamicPropertySource
    static void sec(DynamicPropertyRegistry registry) {
        registry.add("ledger.ingestion.sec.base-url", () -> "http://127.0.0.1:" + SEC.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SEC.stop(0);
    }

    @Autowired
    private SecIngestionService ingestion;

    @Test
    void importsBalanceSheetsIdempotentlyAndSendsTheUserAgent() throws Exception {
        ensureSharedTenant("sec_co", "USD");

        SecIngestResult first = ingestion.ingest("sec_co", "320193", 5);
        assertThat(first.posted()).isEqualTo(2);
        assertThat(first.replayed()).isZero();
        assertThat(USER_AGENT.get()).contains("ledger-test@example.com");
        assertThat(queryRow("ledger", "SELECT to_regclass('t_sec_co.ledger_entries_202012') AS t").get("t")).isNotNull();

        Map<String, Object> assets = queryRow("ledger", """
                SELECT b.debit_total - b.credit_total AS balance
                FROM t_sec_co.account_balances b JOIN t_sec_co.accounts a ON a.id = b.account_id
                WHERE a.code = '1600'
                """);
        assertThat((java.math.BigDecimal) assets.get("balance")).isEqualByComparingTo("1500.0000");
        assertThat(queryRow("ledger", "SELECT source FROM t_sec_co.journal_entries ORDER BY entry_no LIMIT 1").get("source"))
                .isEqualTo("SEC_EDGAR");

        SecIngestResult second = ingestion.ingest("sec_co", "320193", 5);
        assertThat(second.posted()).isZero();
        assertThat(second.replayed()).isEqualTo(2);

        mvc.perform(post("/api/v1/admin/ingestion/sec").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant\":\"sec_co\",\"cik\":\"320193\",\"years\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posted").value(0))
                .andExpect(jsonPath("$.replayed").value(2));
    }
}
