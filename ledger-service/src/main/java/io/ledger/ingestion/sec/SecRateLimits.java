package io.ledger.ingestion.sec;

import java.time.Duration;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;

/** SEC fair-access policy: at most 10 requests per second, from one shared bucket. */
public final class SecRateLimits {

    private SecRateLimits() {
    }

    public static RateLimiter create(int permitsPerSecond, Duration timeout) {
        int permits = Math.min(Math.max(permitsPerSecond, 1), 10);
        return RateLimiter.of("sec-edgar", RateLimiterConfig.custom()
                .limitForPeriod(permits)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(timeout)
                .build());
    }
}
