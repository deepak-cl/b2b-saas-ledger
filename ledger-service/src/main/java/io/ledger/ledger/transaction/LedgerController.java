package io.ledger.ledger.transaction;

import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

import io.ledger.common.error.LedgerErrorCode;
import io.ledger.common.error.LedgerException;
import io.ledger.ledger.transaction.LedgerPostingService.PostingResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Double-entry posting. Stateless and safe for concurrent use: all state lives in the
 * request, the bound TenantContext and the database; per-tenant concurrency is capped by
 * {@link TenantBulkheads}.
 */
@RestController
@RequestMapping("/api/v1/ledger")
@Tag(name = "Ledger", description = "Double-entry transaction posting")
public class LedgerController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final LedgerPostingService posting;

    public LedgerController(LedgerPostingService posting) {
        this.posting = posting;
    }

    @PostMapping("/transaction")
    @PreAuthorize("@tenantSecurity.has('LEDGER_POST')")
    @Operation(summary = "Post a double-entry transaction",
            description = "Idempotent per Idempotency-Key: a retry with the same body returns the original entry (200, "
                    + "Idempotent-Replayed: true); the same key with a different body is rejected with 409.")
    @ApiResponse(responseCode = "201", description = "Posted")
    @ApiResponse(responseCode = "200", description = "Replay of an earlier identical request")
    public ResponseEntity<TransactionResponse> post(
            @Parameter(in = ParameterIn.HEADER, required = true, description = "Client-generated unique key, 8-128 chars [A-Za-z0-9._:-]")
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody PostTransactionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new LedgerException(LedgerErrorCode.IDEMPOTENCY_KEY_INVALID, "Idempotency-Key must match " + IDEMPOTENCY_KEY);
        }
        PostingResult result = posting.post(idempotencyKey, request, subjectUuid(jwt));
        TransactionResponse tx = result.transaction();
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(tx);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/ledger/transaction/" + tx.id()))
                .body(tx);
    }

    @GetMapping("/transaction/{id}")
    @PreAuthorize("@tenantSecurity.has('LEDGER_READ')")
    @Operation(summary = "Get a posted transaction with its hash-chain link")
    public TransactionResponse get(@PathVariable UUID id) {
        return posting.find(id).orElseThrow(() ->
                new LedgerException(LedgerErrorCode.TRANSACTION_NOT_FOUND, "Transaction " + id + " not found"));
    }

    private static UUID subjectUuid(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            return null;
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
