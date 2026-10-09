package io.ledger.ingestion.sec;

import java.time.Duration;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.util.Map;

import io.ledger.common.error.LedgerErrorCode;
import io.ledger.common.error.LedgerException;
import io.ledger.ingestion.IngestionProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One call per CIK to {@code https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json}.
 * The SEC rejects clients that omit a contact User-Agent or exceed 10 requests/second.
 */
@Component
public class SecCompanyFactsClient {

    private final WebClient http;
    private final IngestionProperties.Sec sec;
    private final RateLimiter rateLimiter;
    private final JsonMapper json;

    public SecCompanyFactsClient(WebClient.Builder builder, IngestionProperties properties, RateLimiter secEdgarRateLimiter,
                                 JsonMapper json) {
        this.sec = properties.sec();
        this.rateLimiter = secEdgarRateLimiter;
        this.json = json;
        this.http = builder.baseUrl(sec.baseUrl()).build();
    }

    public JsonNode fetch(String cik) {
        requireUserAgent();
        String padded = pad(cik);
        try {
            String body = rateLimiter.executeSupplier(() -> http.get()
                    .uri("/api/xbrl/companyfacts/CIK{cik}.json", padded)
                    .header(HttpHeaders.USER_AGENT, sec.userAgent())
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30)));
            if (body == null || body.isBlank()) {
                throw new LedgerException(LedgerErrorCode.MALFORMED_REQUEST, "SEC EDGAR returned an empty body for CIK " + padded);
            }
            return json.readTree(body);
        } catch (RequestNotPermitted e) {
            throw new LedgerException(LedgerErrorCode.RATE_LIMITED, "SEC EDGAR rate limit reached (10 requests/second)", Map.of(), e);
        } catch (WebClientResponseException e) {
            throw map(padded, e);
        }
    }

    private void requireUserAgent() {
        String agent = sec.userAgent();
        if (agent.length() < 8 || !agent.contains("@") || !agent.contains(" ")) {
            throw new LedgerException(LedgerErrorCode.VALIDATION_FAILED,
                    "Set ledger.ingestion.sec.user-agent to a contact string the SEC will accept, "
                            + "for example \"Ledger Local dev@example.com\"");
        }
    }

    static String pad(String cik) {
        if (cik == null || !cik.matches("[0-9]{1,10}")) {
            throw new LedgerException(LedgerErrorCode.VALIDATION_FAILED, "CIK must be 1 to 10 digits");
        }
        return "0".repeat(10 - cik.length()) + cik;
    }

    private static LedgerException map(String cik, WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 404) {
            return new LedgerException(LedgerErrorCode.RESOURCE_NOT_FOUND, "No company facts for CIK " + cik, Map.of(), e);
        }
        if (status == 429) {
            return new LedgerException(LedgerErrorCode.RATE_LIMITED, "SEC EDGAR returned 429", Map.of(), e);
        }
        return new LedgerException(LedgerErrorCode.INTERNAL_ERROR, "SEC EDGAR returned " + status, Map.of(), e);
    }
}
