package io.ledger.ai;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Placeholder for Shot 4 (RAG + tool calling + SSE). Exists so the OpenAPI contract, gateway
 * AI rate-limit path and role check ({@code AI_QUERY}) are already wired.
 */
@RestController
@RequestMapping("/api/v1/ai/audit")
@Tag(name = "AI Audit")
public class AiAuditController {

    public record AuditQueryRequest(@NotBlank String query, String from, String to) {
    }

    @PostMapping("/query")
    @PreAuthorize("@tenantSecurity.has('AI_QUERY')")
    @Operation(summary = "Ask a natural-language question about the tenant's finances (Shot 4)")
    public Map<String, Object> query(@RequestBody AuditQueryRequest request) {
        return Map.of(
                "implemented", false,
                "shot", 4,
                "query", request.query(),
                "message", "AI audit is implemented in Shot 4");
    }
}
