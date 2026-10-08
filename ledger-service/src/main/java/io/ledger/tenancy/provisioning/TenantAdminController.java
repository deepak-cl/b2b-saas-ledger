package io.ledger.tenancy.provisioning;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.ledger.common.error.LedgerErrorCode;
import io.ledger.common.error.LedgerException;
import io.ledger.tenancy.TenantInfo;
import io.ledger.tenancy.TenantInfo.TenantStatus;
import io.ledger.tenancy.TenantRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform operations. Requires the PLATFORM_ADMIN realm role (see SecurityConfiguration). */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@Tag(name = "Platform admin", description = "Tenant lifecycle (PLATFORM_ADMIN only, no X-Tenant-ID)")
public class TenantAdminController {

    private final TenantProvisioningService provisioning;
    private final TenantRegistry registry;

    public TenantAdminController(TenantProvisioningService provisioning, TenantRegistry registry) {
        this.provisioning = provisioning;
        this.registry = registry;
    }

    public record TenantResponse(UUID id, String slug, String displayName, String tier, String status,
                                 String schemaName, String baseCurrency) {
        static TenantResponse of(TenantInfo t) {
            return new TenantResponse(t.id(), t.slug(), t.displayName(), t.tier().name(), t.status().name(),
                    t.schemaName(), t.baseCurrency());
        }
    }

    @PostMapping
    @Operation(summary = "Provision a tenant (schema on the shared cluster, or a dedicated database)")
    public ResponseEntity<TenantResponse> provision(@Valid @RequestBody ProvisionTenantRequest request) {
        TenantInfo tenant = provisioning.provision(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/tenants/" + tenant.slug())).body(TenantResponse.of(tenant));
    }

    @GetMapping
    public List<TenantResponse> list() {
        return registry.findAll().stream().map(TenantResponse::of).toList();
    }

    @GetMapping("/{slug}")
    public TenantResponse get(@PathVariable String slug) {
        return registry.findBySlug(slug).map(TenantResponse::of)
                .orElseThrow(() -> new LedgerException(LedgerErrorCode.TENANT_NOT_FOUND, "Tenant " + slug + " not found"));
    }

    @PostMapping("/{slug}/suspend")
    public TenantResponse suspend(@PathVariable String slug) {
        return TenantResponse.of(provisioning.setStatus(slug, TenantStatus.SUSPENDED));
    }

    @PostMapping("/{slug}/activate")
    public TenantResponse activate(@PathVariable String slug) {
        return TenantResponse.of(provisioning.setStatus(slug, TenantStatus.ACTIVE));
    }
}
