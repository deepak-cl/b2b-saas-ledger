package io.ledger.ingestion.sec;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.ledger.ingestion.IngestionAccounts;
import io.ledger.ingestion.sec.CompanyFactsParser.AnnualSnapshot;
import io.ledger.ingestion.sec.SecJournalMapper.Mapped;
import io.ledger.ledger.Direction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SecMappingTest {

    private static final String FACTS = """
            {
              "cik": "320193",
              "entityName": "Apple Inc.",
              "facts": {
                "us-gaap": {
                  "Assets": {"units": {"USD": [
                    {"end":"2020-12-31","val":1000,"accn":"0001-20","fy":2020,"fp":"FY","form":"10-K","filed":"2021-02-01"},
                    {"end":"2020-12-31","val":1,"accn":"old","fy":2020,"fp":"FY","form":"10-K","filed":"2021-01-01"},
                    {"end":"2024-09-30","val":9,"accn":"q","fy":2024,"fp":"Q3","form":"10-Q","filed":"2024-08-01"},
                    {"end":"2024-12-31","val":1500,"accn":"0001-24","fy":2024,"fp":"FY","form":"10-K","filed":"2025-02-01"}
                  ]}},
                  "Liabilities": {"units": {"USD": [
                    {"end":"2020-12-31","val":400,"accn":"0001-20","fy":2020,"fp":"FY","form":"10-K","filed":"2021-02-01"},
                    {"end":"2024-12-31","val":500,"accn":"0001-24","fy":2024,"fp":"FY","form":"10-K","filed":"2025-02-01"}
                  ]}},
                  "StockholdersEquity": {"units": {"USD": [
                    {"end":"2020-12-31","val":600,"accn":"0001-20","fy":2020,"fp":"FY","form":"10-K","filed":"2021-02-01"},
                    {"end":"2024-12-31","val":900,"accn":"0001-24","fy":2024,"fp":"FY","form":"10-K","filed":"2025-02-01"}
                  ]}}
                }
              }
            }
            """;

    @Test
    void keepsLatest10kAndDropsQuarterlies() {
        var parsed = CompanyFactsParser.parse(JsonMapper.builder().build().readTree(FACTS), 5);

        assertThat(parsed.entityName()).isEqualTo("Apple Inc.");
        assertThat(parsed.years()).extracting(AnnualSnapshot::periodEnd).extracting(Object::toString)
                .containsExactly("2020-12-31", "2024-12-31");
        assertThat(parsed.years().getFirst().assets()).isEqualByComparingTo("1000");
    }

    @Test
    void openingYearIsTheFullSnapshotAndTheNextYearIsTheDeltaPlusPlug() {
        var parsed = CompanyFactsParser.parse(JsonMapper.builder().build().readTree(FACTS), 5);
        List<Mapped> journals = SecJournalMapper.journals("0000320193", parsed.entityName(), parsed.years(), "USD");

        assertThat(journals).hasSize(2);
        Mapped opening = journals.getFirst();
        assertThat(opening.idempotencyKey()).isEqualTo("sec-0000320193-bs-2020-12-31");
        assertThat(opening.request().lines()).extracting(l -> l.accountCode() + " " + l.direction() + " " + l.amount().toPlainString())
                .containsExactly("1600 DEBIT 1000.0000", "2600 CREDIT 400.0000", "3200 CREDIT 600.0000");

        Mapped next = journals.get(1);
        // Assets +500, liabilities +100, equity +300; the 100 gap is a credit plug.
        assertThat(next.request().lines()).anySatisfy(line -> {
            assertThat(line.accountCode()).isEqualTo(IngestionAccounts.SEC_PLUG);
            assertThat(line.direction()).isEqualTo(Direction.CREDIT);
            assertThat(line.amount()).isEqualByComparingTo(new BigDecimal("100.0000"));
        });
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (var line : next.request().lines()) {
            if (line.direction() == Direction.DEBIT) {
                debits = debits.add(line.amount());
            } else {
                credits = credits.add(line.amount());
            }
        }
        assertThat(debits).isEqualByComparingTo(credits);
    }

    @Test
    void rateLimiterRejectsTheBurstBeyondTenPerSecond() {
        var limiter = SecRateLimits.create(2, Duration.ZERO);
        limiter.executeSupplier(() -> "ok");
        limiter.executeSupplier(() -> "ok");
        org.junit.jupiter.api.Assertions.assertThrows(RequestNotPermitted.class, () -> limiter.executeSupplier(() -> "no"));
        assertThat(SecRateLimits.create(50, Duration.ofSeconds(1)).getRateLimiterConfig().getLimitForPeriod()).isEqualTo(10);
    }
}
