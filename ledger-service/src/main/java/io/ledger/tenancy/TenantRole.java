package io.ledger.tenancy;

import java.util.EnumSet;
import java.util.Set;

/** Role of the caller inside one tenant. Ordered from most to least privileged. */
public enum TenantRole {
    OWNER(EnumSet.allOf(TenantPermission.class)),
    ADMIN(EnumSet.allOf(TenantPermission.class)),
    ACCOUNTANT(EnumSet.of(TenantPermission.LEDGER_READ, TenantPermission.LEDGER_POST,
            TenantPermission.ACCOUNTS_MANAGE, TenantPermission.AI_QUERY)),
    AUDITOR(EnumSet.of(TenantPermission.LEDGER_READ, TenantPermission.AI_QUERY)),
    VIEWER(EnumSet.of(TenantPermission.LEDGER_READ)),
    /** Background work (batch jobs, provisioning) acting on behalf of the platform. */
    SYSTEM(EnumSet.allOf(TenantPermission.class));

    private final Set<TenantPermission> permissions;

    TenantRole(Set<TenantPermission> permissions) {
        this.permissions = permissions;
    }

    public boolean has(TenantPermission permission) {
        return permissions.contains(permission);
    }

    public enum TenantPermission { LEDGER_READ, LEDGER_POST, ACCOUNTS_MANAGE, AI_QUERY }
}
