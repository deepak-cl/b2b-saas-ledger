package io.ledger.ingestion;

import java.nio.file.Path;
import java.time.LocalDate;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code ledger.ingestion.*}. SEC calls are capped at the published 10 requests/second. */
@ConfigurationProperties("ledger.ingestion")
public record IngestionProperties(Sec sec, PaySim paysim, Reconciliation reconciliation) {

    public IngestionProperties {
        sec = sec == null ? new Sec(null, null, 0, 0) : sec;
        paysim = paysim == null ? new PaySim(null, 0, 0, null) : paysim;
        reconciliation = reconciliation == null ? new Reconciliation(true, null) : reconciliation;
    }

    /**
     * @param userAgent required by the SEC, shaped like {@code Sample Company Name admin@example.com}
     * @param maxYears  how many of the most recent 10-K fiscal years one import posts
     */
    public record Sec(String baseUrl, String userAgent, int rateLimitPerSecond, int maxYears) {
        public Sec {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://data.sec.gov" : baseUrl.replaceAll("/$", "");
            userAgent = userAgent == null ? "" : userAgent.trim();
            rateLimitPerSecond = rateLimitPerSecond <= 0 ? 10 : Math.min(rateLimitPerSecond, 10);
            maxYears = maxYears <= 0 ? 5 : Math.min(maxYears, 30);
        }
    }

    public record PaySim(String importDirectory, int chunkSize, int partitions, LocalDate epoch) {
        public PaySim {
            importDirectory = importDirectory == null || importDirectory.isBlank() ? "var/imports" : importDirectory;
            chunkSize = chunkSize <= 0 ? 200 : Math.min(chunkSize, 1_000);
            partitions = partitions <= 0 ? 4 : Math.min(partitions, 16);
            if (epoch == null) {
                epoch = LocalDate.now().withDayOfMonth(1);
            }
        }

        public Path directory() {
            return Path.of(importDirectory).toAbsolutePath().normalize();
        }
    }

    public record Reconciliation(boolean enabled, String cron) {
        public Reconciliation {
            cron = cron == null || cron.isBlank() ? "0 15 2 * * *" : cron;
        }
    }
}
