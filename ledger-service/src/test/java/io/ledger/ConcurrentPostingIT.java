package io.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Races many postings against one non-negative cash account. Row locks taken in account order
 * plus the overdraft trigger must let exactly the affordable payments through: no double
 * spend, no deadlock, balances and hash chain consistent afterwards.
 */
class ConcurrentPostingIT extends IntegrationTestSupport {

    private static final RequestPostProcessor OWNER = user("race-owner", "/tenants/race/OWNER");

    @Test
    void concurrentPaymentsNeverOverdrawCash() throws Exception {
        ensureSharedTenant("race", "USD");
        mvc.perform(postTransaction("race", "race-seed-0001", transaction("Seed",
                        line("1000", "DEBIT", "100", "USD"), line("3000", "CREDIT", "100", "USD"))).with(OWNER))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(200, 201));
        BigDecimal startCash = cash();

        int payments = 30; // 30 x 5 = 150 requested against 100 available
        Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> codes = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(payments)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < payments; i++) {
                String key = "race-pay-" + System.nanoTime() + "-" + i;
                // Alternate expense accounts so lock sets overlap only on cash.
                String expense = i % 2 == 0 ? "6100" : "6300";
                futures.add(pool.submit(() -> {
                    start.await();
                    var result = mvc.perform(postTransaction("race", key, transaction("Payment",
                            line(expense, "DEBIT", "5", "USD"), line("1000", "CREDIT", "5", "USD"))).with(OWNER)).andReturn();
                    int status = result.getResponse().getStatus();
                    statuses.computeIfAbsent(status, s -> new AtomicInteger()).incrementAndGet();
                    if (status != 201) {
                        codes.computeIfAbsent(body(result).get("code").asString(), c -> new AtomicInteger()).incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }

        int succeeded = statuses.getOrDefault(201, new AtomicInteger()).get();
        int expectedSuccesses = startCash.divide(new BigDecimal("5")).intValue();
        // TENANT_BUSY (503) is the bulkhead shedding load and is acceptable; anything else is a bug.
        assertThat(codes.keySet()).isSubsetOf("INSUFFICIENT_FUNDS", "TENANT_BUSY");
        int busy = codes.getOrDefault("TENANT_BUSY", new AtomicInteger()).get();
        assertThat(succeeded).isEqualTo(Math.min(expectedSuccesses, payments - busy));
        assertThat(cash()).isEqualByComparingTo(startCash.subtract(new BigDecimal(5L * succeeded)));
        assertThat(cash()).isNotNegative();

        // Rollups agree with the raw lines, and the hash chain is intact.
        assertThat(queryRow("ledger", """
                SELECT count(*) AS n
                FROM t_race.account_balances b
                LEFT JOIN (SELECT account_id,
                                  sum(amount) FILTER (WHERE direction = 'D') AS d,
                                  sum(amount) FILTER (WHERE direction = 'C') AS c
                           FROM t_race.ledger_entries GROUP BY account_id) e ON e.account_id = b.account_id
                WHERE b.debit_total <> COALESCE(e.d, 0) OR b.credit_total <> COALESCE(e.c, 0)
                """).get("n")).isEqualTo(0L);
        assertThat(queryRow("ledger", "SELECT * FROM t_race.ledger_verify_chain()")).isEmpty();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/analytics/balance-sheet")
                        .header("X-Tenant-ID", "race").with(OWNER))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.balanced[2]").value(true));
    }

    private static BigDecimal cash() throws Exception {
        return (BigDecimal) queryRow("ledger", """
                SELECT b.debit_total - b.credit_total AS cash
                FROM t_race.account_balances b JOIN t_race.accounts a ON a.id = b.account_id
                WHERE a.code = '1000'
                """).get("cash");
    }
}
