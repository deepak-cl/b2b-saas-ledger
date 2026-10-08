package io.ledger.security;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

import io.ledger.common.error.LedgerErrorCode;
import io.ledger.tenancy.TenantContext;
import io.ledger.tenancy.TenantContext.TenantScope;
import io.ledger.tenancy.TenantInfo;
import io.ledger.tenancy.TenantRegistry;
import io.ledger.tenancy.TenantRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the request to a tenant. Runs after bearer-token authentication and accepts the
 * {@code X-Tenant-ID} header only if the signed token lists the caller as a member of that
 * tenant. The gateway enforces the same rule; this is the service's own check, so the
 * service is safe even if reached without the gateway.
 */
public class TenantFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Tenant-ID";
    private static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9_]{2,47}$");

    private final TenantRegistry registry;
    private final ProblemResponseWriter problems;

    public TenantFilter(TenantRegistry registry, ProblemResponseWriter problems) {
        this.registry = registry;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.startsWith("/api/v1/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response); // unauthenticated: authorization rejects it with 401
            return;
        }

        String slug = request.getHeader(HEADER);
        if (slug == null || slug.isBlank()) {
            problems.write(response, LedgerErrorCode.TENANT_HEADER_MISSING, null);
            return;
        }
        if (!SLUG.matcher(slug).matches()) {
            problems.write(response, LedgerErrorCode.TENANT_HEADER_INVALID, null);
            return;
        }

        Map<String, TenantRole> memberships = TenantMemberships.from(token.getToken());
        TenantRole role = memberships.get(slug);
        if (role == null) {
            problems.write(response, LedgerErrorCode.TENANT_ACCESS_DENIED, "You are not a member of tenant " + slug);
            return;
        }

        TenantInfo tenant = registry.findBySlug(slug).orElse(null);
        if (tenant == null) {
            problems.write(response, LedgerErrorCode.TENANT_NOT_FOUND, "Tenant " + slug + " does not exist");
            return;
        }
        if (!tenant.isActive()) {
            problems.write(response, LedgerErrorCode.TENANT_UNAVAILABLE, "Tenant " + slug + " is " + tenant.status());
            return;
        }

        MDC.put("tenant", slug);
        try (TenantContext.Binding ignored = TenantContext.open(new TenantScope(tenant, role, token.getName()))) {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("tenant");
        }
    }
}
