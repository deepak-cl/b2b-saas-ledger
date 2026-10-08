package io.ledger.common.web;

import io.ledger.security.TenantFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Multi-Tenant Ledger API")
                        .version("v1")
                        .description("Double-entry ledger, financial analytics and AI audit for B2B tenants. "
                                + "Every tenant-scoped call needs a Keycloak access token and an X-Tenant-ID header "
                                + "naming a tenant the token is a member of. Errors are RFC 9457 problem+json with a "
                                + "stable `code` member."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    /** Documents the tenant header on every tenant-scoped operation (all of /api/v1 except admin). */
    @Bean
    OpenApiCustomizer tenantHeaderCustomizer() {
        return openApi -> openApi.getPaths().forEach((path, item) -> {
            if (!path.startsWith("/api/v1/") || path.startsWith("/api/v1/admin/")) {
                return;
            }
            item.readOperations().forEach(op -> op.addParametersItem(new HeaderParameter()
                    .name(TenantFilter.HEADER)
                    .required(true)
                    .description("Tenant slug; must be one of the tenants in the token's `tenants` claim")
                    .schema(new StringSchema().pattern("^[a-z][a-z0-9_]{2,47}$").example("acme"))));
        });
    }
}
