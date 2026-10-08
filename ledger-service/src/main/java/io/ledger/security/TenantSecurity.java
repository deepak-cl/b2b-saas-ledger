package io.ledger.security;

import io.ledger.tenancy.TenantContext;
import io.ledger.tenancy.TenantRole.TenantPermission;
import org.springframework.stereotype.Component;

/** Method-security helper: {@code @PreAuthorize("@tenantSecurity.has('LEDGER_POST')")}. */
@Component("tenantSecurity")
public class TenantSecurity {

    public boolean has(String permission) {
        return TenantContext.current()
                .map(scope -> scope.role().has(TenantPermission.valueOf(permission)))
                .orElse(false);
    }
}
