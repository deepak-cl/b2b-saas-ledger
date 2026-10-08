package io.ledger.ledger.transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.ledger.ledger.Direction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Read model for journal entries (tenant schema, via the routing DataSource). */
@Repository
public class TransactionQueries {

    record StoredRequest(UUID id, byte[] requestHash) {
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public TransactionQueries(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    Optional<StoredRequest> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT id, request_hash FROM journal_entries WHERE idempotency_key = :key")
                .param("key", key)
                .query((rs, i) -> new StoredRequest(rs.getObject("id", UUID.class), rs.getBytes("request_hash")))
                .optional();
    }

    public Optional<TransactionResponse> findById(UUID id) {
        return jdbc.sql("""
                        SELECT j.id, j.entry_no, j.idempotency_key, j.effective_date, j.posted_at, j.description,
                               j.source, j.external_ref, j.metadata::text AS metadata, c.seq, c.entry_hash
                        FROM journal_entries j
                        LEFT JOIN ledger_hash_chain c ON c.journal_entry_id = j.id
                        WHERE j.id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> {
                    List<TransactionResponse.Line> lines = lines(id, rs.getObject("effective_date", java.time.LocalDate.class));
                    byte[] hash = rs.getBytes("entry_hash");
                    Long seq = rs.getObject("seq", Long.class);
                    return new TransactionResponse(
                            id,
                            rs.getLong("entry_no"),
                            rs.getString("idempotency_key"),
                            rs.getObject("effective_date", java.time.LocalDate.class),
                            rs.getObject("posted_at", OffsetDateTime.class),
                            rs.getString("description"),
                            rs.getString("source"),
                            rs.getString("external_ref"),
                            jsonMapper.readValue(rs.getString("metadata"), MAP),
                            lines,
                            totals(lines),
                            seq,
                            hash == null ? null : HexFormat.of().formatHex(hash));
                })
                .optional();
    }

    private List<TransactionResponse.Line> lines(UUID journalId, java.time.LocalDate effectiveDate) {
        return jdbc.sql("""
                        SELECT l.line_no, a.code, a.name, l.direction, l.amount, l.currency, l.memo
                        FROM ledger_entries l JOIN accounts a ON a.id = l.account_id
                        WHERE l.journal_entry_id = :id AND l.effective_date = :date
                        ORDER BY l.line_no
                        """)
                .param("id", journalId)
                .param("date", effectiveDate)
                .query((rs, i) -> new TransactionResponse.Line(
                        rs.getInt("line_no"),
                        rs.getString("code"),
                        rs.getString("name"),
                        Direction.fromCode(rs.getString("direction")),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getString("memo")))
                .list();
    }

    private static List<TransactionResponse.CurrencyTotal> totals(List<TransactionResponse.Line> lines) {
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        for (TransactionResponse.Line l : lines) {
            BigDecimal[] s = sums.computeIfAbsent(l.currency(), c -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            int idx = l.direction() == Direction.DEBIT ? 0 : 1;
            s[idx] = s[idx].add(l.amount());
        }
        return sums.entrySet().stream()
                .map(e -> new TransactionResponse.CurrencyTotal(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }
}
