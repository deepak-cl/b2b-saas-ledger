package io.ledger.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import io.ledger.tenancy.TenantContext;
import io.ledger.tenancy.TenantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The shared {@code ai.vector_store}, always filtered to the tenant bound on this thread.
 * Re-adding the same text is a no-op (content hash). Similarity search enables pgvector's
 * iterative HNSW scan so a small tenant is not starved by the post-filter.
 */
public class TenantVectorStore {

    private static final Logger log = LoggerFactory.getLogger(TenantVectorStore.class);

    private final PgVectorStore store;
    private final JdbcTemplate jdbc;

    public TenantVectorStore(DataSource controlPlane, EmbeddingModel embeddingModel, int dimensions) {
        JdbcTemplate template = new JdbcTemplate(controlPlane);
        this.jdbc = template;
        enableIterativeScan(controlPlane);
        this.store = PgVectorStore.builder(template, embeddingModel)
                .schemaName("ai")
                .vectorTableName("vector_store")
                .idType(PgVectorStore.PgIdType.UUID)
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false)
                .vectorTableValidationsEnabled(false)
                .build();
    }

    /** Stores a summary for the current tenant. Raw ledger rows are never embedded. */
    public void addSummary(String content) {
        TenantInfo tenant = TenantContext.require().tenant();
        String hash = sha256(content);
        Integer existing = jdbc.queryForObject("""
                SELECT count(*) FROM ai.vector_store
                WHERE tenant_id = ? AND metadata->>'content_hash' = ?
                """, Integer.class, tenant.id(), hash);
        if (existing != null && existing > 0) {
            return;
        }
        Document document = new Document(content, Map.of(
                "tenant_id", tenant.id().toString(),
                "content_hash", hash,
                "kind", "summary"));
        store.add(List.of(document));
    }

    public List<Document> search(String query, int topK) {
        TenantInfo tenant = TenantContext.require().tenant();
        var filter = new FilterExpressionBuilder().eq("tenant_id", tenant.id().toString()).build();
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.5)
                .filterExpression(filter)
                .build();
        return store.similaritySearch(request);
    }

    /** Applies to later connections of this role. A missing GUC is logged and ignored. */
    private static void enableIterativeScan(DataSource dataSource) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            String database = connection.getCatalog().replace("\"", "");
            statement.execute("ALTER ROLE CURRENT_USER IN DATABASE \"" + database + "\" SET hnsw.iterative_scan = relaxed_order");
        } catch (Exception e) {
            log.info("pgvector iterative HNSW scan was not enabled: {}", e.getMessage());
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
