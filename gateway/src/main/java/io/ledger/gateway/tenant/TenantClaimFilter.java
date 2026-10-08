package io.ledger.gateway.tenant;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.ledger.gateway.web.Problems;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rejects tenant-scoped API calls at the edge unless {@code X-Tenant-ID} names a tenant the
 * caller belongs to (Keycloak group {@code /tenants/<slug>/<ROLE>} in the {@code tenants}
 * claim). ledger-service repeats the check and also verifies the tenant exists and is active;
 * the gateway only stops obviously foreign traffic before it costs a backend round-trip.
 */
public class TenantClaimFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Tenant-ID";
    public static final String ATTRIBUTE = TenantClaimFilter.class.getName() + ".tenant";
    public static final int ORDER = -200;

    private static final String CLAIM = "tenants";
    private static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9_]{2,47}$");
    private static final Pattern GROUP = Pattern.compile("^/tenants/([a-z][a-z0-9_]{2,47})/[A-Z]+$");

    private final Problems problems;

    public TenantClaimFilter(Problems problems) {
        this.problems = problems;
    }

    public static boolean isTenantScoped(String path) {
        return path.startsWith("/api/") && !path.startsWith("/api/v1/admin/");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS || !isTenantScoped(path)) {
            return chain.filter(exchange);
        }
        String tenant = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (tenant == null || tenant.isBlank()) {
            return problems.write(exchange, Problems.Code.TENANT_HEADER_MISSING, null);
        }
        if (!SLUG.matcher(tenant).matches()) {
            return problems.write(exchange, Problems.Code.TENANT_HEADER_INVALID, null);
        }
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(p -> memberships(((JwtAuthenticationToken) p).getToken()))
                .defaultIfEmpty(Set.of())
                .flatMap(memberships -> {
                    if (!memberships.contains(tenant)) {
                        return problems.write(exchange, Problems.Code.TENANT_ACCESS_DENIED,
                                "You are not a member of tenant " + tenant);
                    }
                    exchange.getAttributes().put(ATTRIBUTE, tenant);
                    return chain.filter(exchange);
                });
    }

    static Set<String> memberships(Jwt jwt) {
        List<String> groups = Optional.ofNullable(jwt.getClaimAsStringList(CLAIM)).orElse(List.of());
        return groups.stream()
                .map(GROUP::matcher)
                .filter(Matcher::matches)
                .map(m -> m.group(1))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
