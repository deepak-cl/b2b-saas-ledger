package io.ledger.gateway.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token-bucket limits per tenant. {@code api} covers ledger and analytics calls; {@code ai}
 * covers {@code /api/v1/ai/**}, which is much stricter because each call can spend LLM tokens.
 * {@code admin} applies per platform admin (admin calls carry no tenant).
 */
@ConfigurationProperties("gateway.rate-limit")
public record RateLimitProperties(
        Limit api,
        Limit ai,
        Limit admin,
        Duration idleEviction,
        long maxTrackedKeys) {

    public RateLimitProperties {
        api = api != null ? api : new Limit(200, 100, Duration.ofSeconds(1));
        ai = ai != null ? ai : new Limit(10, 10, Duration.ofMinutes(1));
        admin = admin != null ? admin : new Limit(30, 30, Duration.ofMinutes(1));
        idleEviction = idleEviction != null ? idleEviction : Duration.ofMinutes(15);
        maxTrackedKeys = maxTrackedKeys > 0 ? maxTrackedKeys : 100_000;
    }

    /** Burst {@code capacity}, refilled greedily by {@code refillTokens} every {@code refillPeriod}. */
    public record Limit(long capacity, long refillTokens, Duration refillPeriod) {
    }
}
