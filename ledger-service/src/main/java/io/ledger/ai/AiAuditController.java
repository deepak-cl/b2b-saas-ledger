package io.ledger.ai;

import io.ledger.ai.FinancialAiAuditService.AuditInsight;
import io.ledger.ai.FinancialAiAuditService.AuditQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Natural-language audit for the tenant in {@code X-Tenant-ID}. Role {@code AI_QUERY}. */
@RestController
@RequestMapping("/api/v1/ai/audit")
@Tag(name = "AI Audit")
public class AiAuditController {

    public record AuditQueryRequest(
            @NotBlank @Size(min = 3, max = 2000) String query,
            @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String from,
            @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String to,
            @Pattern(regexp = "^[A-Z]{3}$") String currency) {
    }

    private final FinancialAiAuditService audit;

    public AiAuditController(FinancialAiAuditService audit) {
        this.audit = audit;
    }

    @PostMapping(value = "/query", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@tenantSecurity.has('AI_QUERY')")
    @Operation(summary = "Ask a natural-language question about the tenant's finances")
    public AuditInsight query(@Valid @RequestBody AuditQueryRequest request) {
        return audit.answer(toQuery(request));
    }

    @PostMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@tenantSecurity.has('AI_QUERY')")
    @Operation(summary = "Stream the same audit as Server-Sent Events (token, finding, done)")
    public SseEmitter stream(@Valid @RequestBody AuditQueryRequest request) {
        return audit.stream(toQuery(request));
    }

    private static AuditQuery toQuery(AuditQueryRequest request) {
        return new AuditQuery(request.query(), request.from(), request.to(), request.currency());
    }
}
