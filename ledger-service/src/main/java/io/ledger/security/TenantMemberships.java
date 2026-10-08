package io.ledger.security;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.ledger.tenancy.TenantRole;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads tenant memberships from the access token. Keycloak models them as groups
 * {@code /tenants/<slug>/<ROLE>}, emitted with full path in the {@code tenants} claim.
 */
public final class TenantMemberships {

    public static final String CLAIM = "tenants";
    private static final Pattern GROUP = Pattern.compile("^/tenants/([a-z][a-z0-9_]{2,47})/([A-Z]+)$");

    private TenantMemberships() {
    }

    /** Highest role per tenant slug; unknown roles and malformed entries are ignored. */
    public static Map<String, TenantRole> from(Jwt jwt) {
        Collection<String> groups = Optional.ofNullable(jwt.getClaimAsStringList(CLAIM)).orElse(List.of());
        Map<String, TenantRole> roles = new HashMap<>();
        for (String group : groups) {
            Matcher m = GROUP.matcher(group);
            if (!m.matches()) {
                continue;
            }
            TenantRole role = parseRole(m.group(2));
            if (role == null || role == TenantRole.SYSTEM) {
                continue;
            }
            roles.merge(m.group(1), role, (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
        }
        return roles;
    }

    private static TenantRole parseRole(String name) {
        try {
            return TenantRole.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
