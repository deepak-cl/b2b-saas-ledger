package io.ledger.common.error;

import java.net.URI;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/** Builds the RFC 9457 problem documents returned for every error. */
public final class Problems {

    private static final String TYPE_PREFIX = "https://ledger.dev/problems/";

    private Problems() {
    }

    public static ProblemDetail of(LedgerErrorCode code, String detail) {
        return of(code, detail, Map.of());
    }

    public static ProblemDetail of(LedgerErrorCode code, String detail, Map<String, Object> properties) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail != null ? detail : code.title());
        problem.setType(URI.create(TYPE_PREFIX + code.name().toLowerCase().replace('_', '-')));
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        problem.setProperty("retryable", code.retryable());
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        String tenant = MDC.get("tenant");
        if (tenant != null) {
            problem.setProperty("tenant", tenant);
        }
        properties.forEach(problem::setProperty);
        return problem;
    }
}
