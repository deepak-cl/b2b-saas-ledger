package io.ledger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots the full ledger-service against one real PostgreSQL 17 + pgvector container shared by
 * all integration tests (started once per JVM; the Spring context is cached across classes).
 * JWTs are mocked with spring-security-test, so no Keycloak and no network are needed, and no
 * AI provider is ever called.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestSupport {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ledger")
            .withUsername("ledger")
            .withPassword("ledger"); // matches ledger.secrets.LEDGER_DB_PASSWORD (env:LEDGER_DB_PASSWORD fallback)

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    // ---------------------------------------------------------------- identities

    /** A Keycloak-style user token carrying {@code /tenants/<slug>/<ROLE>} groups. */
    protected static RequestPostProcessor user(String username, String... groups) {
        return jwt().jwt(j -> j.subject(username)
                .claim("preferred_username", username)
                .claim("tenants", List.of(groups)));
    }

    protected static RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject("platform-admin").claim("preferred_username", "platform-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    // ---------------------------------------------------------------- tenants

    protected void ensureSharedTenant(String slug, String currency) throws Exception {
        ensureTenant(slug, """
                {"slug":"%s","displayName":"%s","tier":"SHARED","baseCurrency":"%s"}
                """.formatted(slug, slug, currency));
    }

    /** ISOLATED tenant in its own database on the same server (stands in for a dedicated cluster). */
    protected void ensureIsolatedTenant(String slug, String currency) throws Exception {
        String database = "ledger_" + slug;
        if (!databaseExists(database)) {
            exec("ledger", "CREATE DATABASE " + database);
        }
        ensureTenant(slug, """
                {"slug":"%s","displayName":"%s","tier":"ISOLATED","baseCurrency":"%s",
                 "isolatedDatabase":{"jdbcUrl":"%s","username":"ledger","secretRef":"env:LEDGER_DB_PASSWORD","poolMaxSize":4}}
                """.formatted(slug, slug, currency, jdbcUrl(database)));
    }

    private void ensureTenant(String slug, String body) throws Exception {
        int status = mvc.perform(get("/api/v1/admin/tenants/" + slug).with(platformAdmin()))
                .andReturn().getResponse().getStatus();
        if (status == 404) {
            MvcResult result = mvc.perform(post("/api/v1/admin/tenants").with(platformAdmin())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
            if (result.getResponse().getStatus() != 201) {
                throw new IllegalStateException("Provisioning " + slug + " failed: " + result.getResponse().getContentAsString());
            }
        }
    }

    // ---------------------------------------------------------------- requests

    protected static String line(String account, String direction, String amount, String currency) {
        return """
                {"accountCode":"%s","direction":"%s","amount":"%s","currency":"%s"}""".formatted(account, direction, amount, currency);
    }

    protected static String transaction(String description, String... lines) {
        return """
                {"effectiveDate":"%s","description":"%s","lines":[%s]}
                """.formatted(java.time.LocalDate.now(), description, String.join(",", lines));
    }

    protected static MockHttpServletRequestBuilder postTransaction(String tenant, String idempotencyKey, String body) {
        MockHttpServletRequestBuilder request = post("/api/v1/ledger/transaction")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (tenant != null) {
            request.header("X-Tenant-ID", tenant);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request;
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    // ---------------------------------------------------------------- direct SQL (assertions only)

    protected static String jdbcUrl(String database) {
        return "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), database);
    }

    protected static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    protected static void exec(String database, String sql) throws SQLException {
        try (Connection c = connect(database); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    protected static Map<String, Object> queryRow(String database, String sql) throws SQLException {
        try (Connection c = connect(database); Statement s = c.createStatement(); var rs = s.executeQuery(sql)) {
            if (!rs.next()) {
                return Map.of();
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
            }
            return row;
        }
    }

    private static boolean databaseExists(String database) throws SQLException {
        return !queryRow("ledger", "SELECT 1 AS x FROM pg_database WHERE datname = '" + database + "'").isEmpty();
    }
}
