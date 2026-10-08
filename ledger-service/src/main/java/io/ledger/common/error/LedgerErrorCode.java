package io.ledger.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, client-facing error codes. Returned as the {@code code} member of every
 * problem+json response; clients branch on the code, never on the message.
 */
public enum LedgerErrorCode {
    // 400 - request shape
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed", false),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request", false),
    TENANT_HEADER_MISSING(HttpStatus.BAD_REQUEST, "X-Tenant-ID header is required", false),
    TENANT_HEADER_INVALID(HttpStatus.BAD_REQUEST, "X-Tenant-ID header is invalid", false),
    IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required", false),
    IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "Idempotency-Key header is invalid", false),

    // 401 / 403 / 404 - identity and access
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required", false),
    TENANT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "No access to this tenant", false),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "Role does not permit this operation", false),
    TENANT_NOT_FOUND(HttpStatus.NOT_FOUND, "Tenant not found", false),
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Transaction not found", false),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found", false),

    // 409 - state conflicts
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "Idempotency-Key was already used for a different request", false),
    ACCOUNT_CODE_EXISTS(HttpStatus.CONFLICT, "Account code already exists", false),
    TENANT_EXISTS(HttpStatus.CONFLICT, "Tenant already exists", false),
    JOURNAL_SEALED(HttpStatus.CONFLICT, "Journal entry is sealed; post a reversal instead", false),
    LEDGER_IMMUTABLE(HttpStatus.CONFLICT, "Posted ledger records cannot be changed", false),
    PERIOD_CLOSED(HttpStatus.CONFLICT, "Accounting period is closed", false),

    // 422 - business rule violations
    LEDGER_UNBALANCED(HttpStatus.UNPROCESSABLE_CONTENT, "Debits and credits do not balance", false),
    INSUFFICIENT_LINES(HttpStatus.UNPROCESSABLE_CONTENT, "A journal entry needs at least two lines", false),
    ACCOUNT_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown account", false),
    ACCOUNT_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "Account is inactive", false),
    CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "Line currency does not match account currency", false),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds", false),
    PERIOD_NOT_OPEN(HttpStatus.UNPROCESSABLE_CONTENT, "Accounting period is not open", false),

    // 423 / 429 / 5xx - availability
    TENANT_UNAVAILABLE(HttpStatus.LOCKED, "Tenant is not active", false),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded", true),
    AI_BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Monthly AI token budget exhausted", false),
    AI_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI provider unavailable", true),
    TENANT_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Too many concurrent postings for this tenant", true),
    LEDGER_CONTENTION(HttpStatus.SERVICE_UNAVAILABLE, "Ledger is busy; retry the request", true),
    TENANT_PROVISIONING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Tenant provisioning failed", true),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", true);

    private final HttpStatus status;
    private final String title;
    private final boolean retryable;

    LedgerErrorCode(HttpStatus status, String title, boolean retryable) {
        this.status = status;
        this.title = title;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public boolean retryable() {
        return retryable;
    }
}
