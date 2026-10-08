package io.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.ledger.tenancy.TenantRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class TenantMembershipsTest {

    private static Jwt jwt(Object tenants) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (tenants != null) {
            builder.claim(TenantMemberships.CLAIM, tenants);
        }
        return builder.build();
    }

    @Test
    void parsesGroupPathsIntoRolesPerTenant() {
        Map<String, TenantRole> roles = TenantMemberships.from(jwt(List.of(
                "/tenants/acme/ACCOUNTANT", "/tenants/globex/VIEWER")));

        assertThat(roles).containsExactlyInAnyOrderEntriesOf(Map.of(
                "acme", TenantRole.ACCOUNTANT, "globex", TenantRole.VIEWER));
    }

    @Test
    void keepsHighestRoleWhenUserIsInSeveralGroupsOfOneTenant() {
        Map<String, TenantRole> roles = TenantMemberships.from(jwt(List.of(
                "/tenants/acme/VIEWER", "/tenants/acme/ADMIN", "/tenants/acme/AUDITOR")));

        assertThat(roles).containsExactly(Map.entry("acme", TenantRole.ADMIN));
    }

    @Test
    void ignoresMalformedUnknownAndSystemEntries() {
        Map<String, TenantRole> roles = TenantMemberships.from(jwt(List.of(
                "/tenants/acme/SUPERUSER",      // unknown role
                "/tenants/acme/SYSTEM",         // internal role, never granted by tokens
                "/tenants/Acme/OWNER",          // slug must be lowercase
                "/tenants/acme",                // no role
                "/other/acme/OWNER",            // wrong root group
                "/tenants/acme/OWNER/extra",    // too deep
                "/tenants/ok_tenant/AUDITOR")));

        assertThat(roles).containsExactly(Map.entry("ok_tenant", TenantRole.AUDITOR));
    }

    @Test
    void missingClaimMeansNoMemberships() {
        assertThat(TenantMemberships.from(jwt(null))).isEmpty();
    }
}
