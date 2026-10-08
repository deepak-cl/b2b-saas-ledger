package io.ledger.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Gateway edge behaviour with mocked JWTs and an in-process stub standing in for
 * ledger-service, which records what actually got forwarded.
 */
@SpringBootTest(properties = {
        "gateway.rate-limit.api.capacity=5",
        "gateway.rate-limit.api.refill-tokens=5",
        "gateway.rate-limit.api.refill-period=1h",
        "gateway.rate-limit.ai.capacity=2",
        "gateway.rate-limit.ai.refill-tokens=2",
        "gateway.rate-limit.ai.refill-period=1h",
})
class GatewayFiltersTest {

    private static final HttpServer BACKEND;
    private static final AtomicInteger BACKEND_CALLS = new AtomicInteger();
    private static final Map<String, String> LAST_HEADERS = new ConcurrentHashMap<>();

    static {
        try {
            BACKEND = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        BACKEND.createContext("/", exchange -> {
            BACKEND_CALLS.incrementAndGet();
            LAST_HEADERS.clear();
            exchange.getRequestHeaders().forEach((k, v) -> LAST_HEADERS.put(k.toLowerCase(), v.getFirst()));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
            if (requestId != null) {
                exchange.getResponseHeaders().add("X-Request-Id", requestId); // ledger-service echoes it too
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        BACKEND.start();
    }

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("LEDGER_SERVICE_URL", () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient()
                .baseUrl("http://gateway.test") // CORS processing needs an absolute request URI
                .build();
        BACKEND_CALLS.set(0);
    }

    private static JwtMutator member(String... groups) {
        return mockJwt().jwt(j -> j.subject("alice").claim("tenants", List.of(groups)));
    }

    @Test
    void unauthenticatedRequestsGetProblemJson401() {
        client.get().uri("/api/v1/ledger/accounts").header("X-Tenant-ID", "acme").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.requestId").exists();
        assertThat(BACKEND_CALLS).hasValue(0);
    }

    @Test
    void tenantHeaderIsCheckedAgainstTokenClaimBeforeProxying() {
        JwtMutator alice = member("/tenants/acme/ACCOUNTANT");
        client.mutateWith(alice).get().uri("/api/v1/ledger/accounts").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("TENANT_HEADER_MISSING");
        client.mutateWith(alice).get().uri("/api/v1/ledger/accounts").header("X-Tenant-ID", "t_acme;drop").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("TENANT_HEADER_INVALID");
        client.mutateWith(alice).get().uri("/api/v1/ledger/accounts").header("X-Tenant-ID", "globex").exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("TENANT_ACCESS_DENIED")
                .jsonPath("$.detail").isEqualTo("You are not a member of tenant globex");
        // A look-alike group outside /tenants grants nothing.
        client.mutateWith(member("/other/globex/OWNER")).get().uri("/api/v1/ledger/accounts")
                .header("X-Tenant-ID", "globex").exchange()
                .expectStatus().isForbidden();
        assertThat(BACKEND_CALLS).hasValue(0);
    }

    @Test
    void memberRequestIsProxiedWithRequestIdAndTenantHeader() {
        client.mutateWith(member("/tenants/proxy_ok/VIEWER")).get().uri("/api/v1/ledger/accounts")
                .header("X-Tenant-ID", "proxy_ok")
                .header("X-Request-Id", "client-trace-0001")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "client-trace-0001") // exactly one value
                .expectHeader().valueEquals("X-RateLimit-Limit", "5")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "4")
                .expectBody().jsonPath("$.ok").isEqualTo(true);
        assertThat(BACKEND_CALLS).hasValue(1);
        assertThat(LAST_HEADERS).containsEntry("x-request-id", "client-trace-0001").containsEntry("x-tenant-id", "proxy_ok");
    }

    @Test
    void invalidClientRequestIdIsReplaced() {
        client.mutateWith(member("/tenants/rid_tenant/VIEWER")).get().uri("/api/v1/ledger/accounts")
                .header("X-Tenant-ID", "rid_tenant")
                .header("X-Request-Id", "bad id\r\ninjected")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value("X-Request-Id", id -> assertThat(id).matches("[0-9a-f-]{36}"));
    }

    @Test
    void adminPathsNeedPlatformAdminButNoTenant() {
        client.mutateWith(member("/tenants/acme/OWNER")).get().uri("/api/v1/admin/tenants").exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("PERMISSION_DENIED");
        client.mutateWith(mockJwt().jwt(j -> j.subject("root")).authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))
                .get().uri("/api/v1/admin/tenants").exchange()
                .expectStatus().isOk();
        assertThat(BACKEND_CALLS).hasValue(1);
    }

    @Test
    void perTenantRateLimitReturns429WithRetryAfter() {
        JwtMutator user = member("/tenants/limited/ACCOUNTANT", "/tenants/neighbour/ACCOUNTANT");
        for (int i = 0; i < 5; i++) {
            client.mutateWith(user).get().uri("/api/v1/ledger/accounts").header("X-Tenant-ID", "limited").exchange()
                    .expectStatus().isOk();
        }
        client.mutateWith(user).get().uri("/api/v1/ledger/accounts").header("X-Tenant-ID", "limited").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().value(HttpHeaders.RETRY_AFTER, v -> assertThat(Long.parseLong(v)).isPositive())
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMITED")
                .jsonPath("$.retryable").isEqualTo(true);
        assertThat(BACKEND_CALLS).hasValue(5);

        // Buckets are per tenant: the neighbour is unaffected.
        client.mutateWith(user).get().uri("/api/v1/ledger/accounts").header("X-Tenant-ID", "neighbour").exchange()
                .expectStatus().isOk();
    }

    @Test
    void aiEndpointsHaveTheirOwnStricterBucket() {
        JwtMutator user = member("/tenants/ai_tenant/AUDITOR");
        for (int i = 0; i < 2; i++) {
            client.mutateWith(user).post().uri("/api/v1/ai/audit/query").header("X-Tenant-ID", "ai_tenant")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"query\":\"q\"}").exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals("X-RateLimit-Limit", "2");
        }
        client.mutateWith(user).post().uri("/api/v1/ai/audit/query").header("X-Tenant-ID", "ai_tenant")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"query\":\"q\"}").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // Regular API calls of the same tenant still pass.
        client.mutateWith(user).get().uri("/api/v1/ledger/accounts").header("X-Tenant-ID", "ai_tenant").exchange()
                .expectStatus().isOk();
    }

    @Test
    void corsAllowsOnlyTheConfiguredFrontend() {
        client.options().uri("/api/v1/ledger/transaction")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,x-tenant-id,idempotency-key,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
        client.options().uri("/api/v1/ledger/transaction")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void pathsOutsideTheApiAreDenied() {
        client.mutateWith(member("/tenants/acme/OWNER")).get().uri("/internal/anything").exchange()
                .expectStatus().isForbidden();
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
