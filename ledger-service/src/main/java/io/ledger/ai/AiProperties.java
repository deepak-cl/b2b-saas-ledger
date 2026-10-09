package io.ledger.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Spend limits for the audit model. Paid providers are off unless a profile selects one. */
@ConfigurationProperties("ledger.ai")
public record AiProperties(
        @DefaultValue("200000") int monthlyTokenBudget,
        @DefaultValue("800") int maxOutputTokens,
        @DefaultValue("20") int requestsPerMinute,
        @DefaultValue("10m") Duration cacheTtl,
        @DefaultValue("3s") Duration statementTimeout,
        @DefaultValue("50") int sqlRowLimit,
        @DefaultValue("stub") String provider) {
}
