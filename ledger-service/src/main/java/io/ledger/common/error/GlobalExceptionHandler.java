package io.ledger.common.error;

import java.util.List;
import java.util.Map;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps every failure to a problem+json body carrying a stable {@link LedgerErrorCode}. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<ProblemDetail> ledger(LedgerException e) {
        if (e.code().status().is5xxServerError()) {
            log.error("Request failed: {}", e.getMessage(), e);
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.code().status());
        if (e.code().retryable()) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(Problems.of(e.code(), e.getMessage(), e.properties()));
    }

    @ExceptionHandler(BulkheadFullException.class)
    ResponseEntity<ProblemDetail> bulkheadFull(BulkheadFullException e) {
        return ledger(new LedgerException(LedgerErrorCode.TENANT_BUSY, null));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ProblemDetail> accessDenied(RuntimeException e) {
        return ledger(new LedgerException(LedgerErrorCode.PERMISSION_DENIED, null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException e) {
        return ledger(SqlStateTranslator.translate(e).orElseGet(() -> {
            log.warn("Unmapped integrity violation", e);
            return new LedgerException(LedgerErrorCode.VALIDATION_FAILED, "Request violates a data constraint");
        }));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        return SqlStateTranslator.translate(e)
                .map(this::ledger)
                .orElseGet(() -> {
                    log.error("Unhandled error", e);
                    return ledger(new LedgerException(LedgerErrorCode.INTERNAL_ERROR, "Unexpected error"));
                });
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<Map<String, Object>> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> Map.<String, Object>of(
                        "field", error instanceof FieldError fe ? fe.getField() : error.getObjectName(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        boolean unbalanced = ex.getBindingResult().getGlobalErrors().stream()
                .anyMatch(e -> "BalancedEntry".equals(e.getCode()));
        LedgerErrorCode code = unbalanced && ex.getBindingResult().getFieldErrorCount() == 0
                ? LedgerErrorCode.LEDGER_UNBALANCED : LedgerErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status()).body(Problems.of(code, null, Map.of("errors", errors)));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<Map<String, Object>> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(err -> Map.<String, Object>of(
                        "field", String.valueOf(r.getMethodParameter().getParameterName()),
                        "message", String.valueOf(err.getDefaultMessage()))))
                .toList();
        boolean idempotencyKey = ex.getParameterValidationResults().stream()
                .anyMatch(r -> "idempotencyKey".equals(r.getMethodParameter().getParameterName()));
        LedgerErrorCode code = idempotencyKey ? LedgerErrorCode.IDEMPOTENCY_KEY_INVALID : LedgerErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status()).body(Problems.of(code, null, Map.of("errors", errors)));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(Problems.of(LedgerErrorCode.MALFORMED_REQUEST, "Request body is not valid JSON for this operation"));
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            org.springframework.web.bind.ServletRequestBindingException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing && "Idempotency-Key".equalsIgnoreCase(missing.getHeaderName())) {
            return ResponseEntity.badRequest().body(Problems.of(LedgerErrorCode.IDEMPOTENCY_KEY_MISSING, null));
        }
        return ResponseEntity.badRequest().body(Problems.of(LedgerErrorCode.VALIDATION_FAILED, ex.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getProperties() == null) {
            problem.setProperty("code", statusCode.value() == 404 ? LedgerErrorCode.RESOURCE_NOT_FOUND.name()
                    : statusCode.is4xxClientError() ? LedgerErrorCode.MALFORMED_REQUEST.name()
                    : LedgerErrorCode.INTERNAL_ERROR.name());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }
}
