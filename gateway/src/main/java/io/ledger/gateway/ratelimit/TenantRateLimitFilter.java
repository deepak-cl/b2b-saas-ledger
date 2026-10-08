package io.ledger.gateway.ratelimit;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.ledger.gateway.tenant.TenantClaimFilter;
import io.ledger.gateway.web.Problems;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Per-tenant token buckets (Bucket4j) held in a bounded Caffeine cache, so idle tenants cost
 * nothing and a flood of distinct keys cannot exhaust memory. Buckets are local to each
 * gateway instance; with N instances the effective limit is N x the configured one (swap in
 * Bucket4j's Redis/JCache proxy manager when that matters).
 */
public class TenantRateLimitFilter implements GlobalFilter, Ordered {

    public static final String REMAINING = "X-RateLimit-Remaining";
    public static final String LIMIT = "X-RateLimit-Limit";

    private final RateLimitProperties properties;
    private final Problems problems;
    private final Cache<String, Bucket> buckets;

    public TenantRateLimitFilter(RateLimitProperties properties, Problems problems) {
        this.properties = properties;
        this.problems = problems;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(properties.idleEviction())
                .maximumSize(properties.maxTrackedKeys())
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String tenant = exchange.getAttribute(TenantClaimFilter.ATTRIBUTE);
        if (tenant != null) {
            boolean ai = path.startsWith("/api/v1/ai/");
            return limit(exchange, chain, (ai ? "ai:" : "api:") + tenant, ai ? properties.ai() : properties.api());
        }
        if (path.startsWith("/api/v1/admin/")) {
            return exchange.getPrincipal()
                    .map(p -> "admin:" + p.getName())
                    .defaultIfEmpty("admin:anonymous")
                    .flatMap(key -> limit(exchange, chain, key, properties.admin()));
        }
        return chain.filter(exchange);
    }

    private Mono<Void> limit(ServerWebExchange exchange, GatewayFilterChain chain, String key,
                             RateLimitProperties.Limit limit) {
        Bucket bucket = buckets.get(key, k -> newBucket(limit));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(LIMIT, Long.toString(limit.capacity()));
        headers.set(REMAINING, Long.toString(probe.getRemainingTokens()));
        if (probe.isConsumed()) {
            return chain.filter(exchange);
        }
        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999L));
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return problems.write(exchange, Problems.Code.RATE_LIMITED,
                "Too many requests; retry after " + retryAfterSeconds + "s",
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }

    private static Bucket newBucket(RateLimitProperties.Limit limit) {
        return Bucket.builder()
                .addLimit(b -> b.capacity(limit.capacity()).refillGreedy(limit.refillTokens(), limit.refillPeriod()))
                .build();
    }

    @Override
    public int getOrder() {
        return TenantClaimFilter.ORDER + 100;
    }
}
