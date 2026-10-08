package io.ledger.gateway.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes RFC 9457 problem+json responses with the same shape as ledger-service
 * ({@code type}, {@code title}, {@code status}, {@code detail}, {@code code},
 * {@code retryable}, {@code requestId}), so clients handle one error format.
 */
public class Problems {

    public enum Code {
        UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required", false),
        PERMISSION_DENIED(HttpStatus.FORBIDDEN, "Role does not permit this operation", false),
        TENANT_HEADER_MISSING(HttpStatus.BAD_REQUEST, "X-Tenant-ID header is required", false),
        TENANT_HEADER_INVALID(HttpStatus.BAD_REQUEST, "X-Tenant-ID header is malformed", false),
        TENANT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "No access to this tenant", false),
        RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded", true);

        final HttpStatus status;
        final String title;
        final boolean retryable;

        Code(HttpStatus status, String title, boolean retryable) {
            this.status = status;
            this.title = title;
            this.retryable = retryable;
        }

        String slug() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    private final JsonMapper jsonMapper;

    public Problems(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, Code code, String detail) {
        return write(exchange, code, detail, Map.of());
    }

    public Mono<Void> write(ServerWebExchange exchange, Code code, String detail, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://ledger.dev/problems/" + code.slug());
        body.put("title", code.title);
        body.put("status", code.status.value());
        body.put("detail", detail != null ? detail : code.title);
        body.put("instance", exchange.getRequest().getPath().value());
        body.put("code", code.name());
        body.put("retryable", code.retryable);
        String requestId = exchange.getAttribute(RequestIdWebFilter.ATTRIBUTE);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        body.putAll(extra);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(code.status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(jsonMapper.writeValueAsBytes(body));
        return response.writeWith(Mono.just(buffer));
    }
}
