package io.ledger.ledger.transaction;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.ledger.common.error.LedgerErrorCode;
import io.ledger.common.error.LedgerException;
import io.ledger.common.error.SqlStateTranslator;
import io.ledger.ledger.transaction.PostTransactionRequest.Line;
import io.ledger.ledger.transaction.TransactionQueries.StoredRequest;
import io.ledger.tenancy.TenantContext;
import io.ledger.tenancy.TenantContext.TenantScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Posts double-entry transactions.
 *
 * <p>Concurrency model (see docs/ARCHITECTURE.md, "Isolation levels and locking"):
 * READ COMMITTED, the touched {@code account_balances} rows locked FOR UPDATE in ascending
 * account id order (deadlock-free), all lines inserted by one statement so the balance
 * trigger runs once, and the outbox event written in the same transaction. Invariants
 * (balanced entry, overdraft, open period) are enforced again by the database; violations
 * surface here, possibly at COMMIT, and are translated to API error codes.
 */
@Service
public class LedgerPostingService {

    private static final String IDEMPOTENCY_CONSTRAINT = "journal_entries_idempotency_key_key";

    public record PostingResult(TransactionResponse transaction, boolean replayed) {
    }

    private record AccountRef(long id, String code, String currency, boolean active) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;
    private final TransactionQueries queries;
    private final TenantBulkheads bulkheads;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate postingTx;
    private final TransactionTemplate readTx;

    public LedgerPostingService(JdbcTemplate jdbcTemplate, JdbcClient jdbc, TransactionQueries queries,
                                TenantBulkheads bulkheads, JsonMapper jsonMapper,
                                PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
        this.queries = queries;
        this.bulkheads = bulkheads;
        this.jsonMapper = jsonMapper;
        this.postingTx = new TransactionTemplate(transactionManager);
        this.postingTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.postingTx.setTimeout(15);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    public PostingResult post(String idempotencyKey, PostTransactionRequest request, UUID createdBy) {
        TenantScope scope = TenantContext.require();
        byte[] fingerprint = RequestFingerprint.of(request, jsonMapper);

        Optional<PostingResult> replay = findReplay(idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        UUID journalId;
        try {
            journalId = bulkheads.execute(scope.tenant().slug(),
                    () -> postingTx.execute(status -> insert(idempotencyKey, request, fingerprint, createdBy, scope)));
        } catch (DuplicateKeyException e) {
            if (!String.valueOf(e.getMessage()).contains(IDEMPOTENCY_CONSTRAINT)) {
                throw e;
            }
            // Lost a race with a concurrent request carrying the same key.
            return findReplay(idempotencyKey, fingerprint).orElseThrow(() -> e);
        } catch (LedgerException e) {
            throw e;
        } catch (RuntimeException e) {
            throw SqlStateTranslator.translate(e).<RuntimeException>map(x -> x).orElse(e);
        }
        return new PostingResult(readTx.execute(status -> queries.findById(journalId).orElseThrow()), false);
    }

    public Optional<TransactionResponse> find(UUID id) {
        return readTx.execute(status -> queries.findById(id));
    }

    private Optional<PostingResult> findReplay(String idempotencyKey, byte[] fingerprint) {
        return readTx.execute(status -> queries.findByIdempotencyKey(idempotencyKey).map(stored -> replay(stored, fingerprint)));
    }

    private PostingResult replay(StoredRequest stored, byte[] fingerprint) {
        if (!Arrays.equals(stored.requestHash(), fingerprint)) {
            throw new LedgerException(LedgerErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used for a different transaction",
                    Map.of("transactionId", stored.id().toString()));
        }
        return new PostingResult(queries.findById(stored.id()).orElseThrow(), true);
    }

    private UUID insert(String idempotencyKey, PostTransactionRequest request, byte[] fingerprint, UUID createdBy,
                        TenantScope scope) {
        // Fail fast instead of queueing behind a long-running lock holder.
        jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");

        Map<String, AccountRef> accounts = resolveAccounts(request.lines());

        List<Long> accountIds = accounts.values().stream().map(AccountRef::id).sorted().toList();
        jdbc.sql("SELECT account_id FROM account_balances WHERE account_id IN (:ids) ORDER BY account_id FOR UPDATE")
                .param("ids", accountIds)
                .query(Long.class)
                .list();

        UUID journalId = jdbc.sql("""
                        INSERT INTO journal_entries (idempotency_key, effective_date, description, source, external_ref,
                                                     created_by, metadata, request_hash)
                        VALUES (:key, :date, :description, 'API', :externalRef, :createdBy, CAST(:metadata AS jsonb), :hash)
                        RETURNING id
                        """)
                .param("key", idempotencyKey)
                .param("date", request.effectiveDate())
                .param("description", request.description())
                .param("externalRef", request.externalRef())
                .param("createdBy", createdBy)
                .param("metadata", jsonMapper.writeValueAsString(request.metadata() == null ? Map.of() : request.metadata()))
                .param("hash", fingerprint)
                .query(UUID.class)
                .single();

        insertLines(journalId, request, accounts);

        jdbc.sql("""
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                        VALUES ('JournalEntry', :id, 'ledger.transaction.posted.v1', CAST(:payload AS jsonb))
                        """)
                .param("id", journalId.toString())
                .param("payload", jsonMapper.writeValueAsString(eventPayload(journalId, request, scope)))
                .update();
        return journalId;
    }

    private Map<String, AccountRef> resolveAccounts(List<Line> lines) {
        List<String> codes = lines.stream().map(Line::accountCode).distinct().toList();
        Map<String, AccountRef> accounts = new HashMap<>();
        jdbc.sql("SELECT id, code, currency, is_active FROM accounts WHERE code IN (:codes)")
                .param("codes", codes)
                .query((rs, i) -> new AccountRef(rs.getLong("id"), rs.getString("code"), rs.getString("currency"),
                        rs.getBoolean("is_active")))
                .list()
                .forEach(a -> accounts.put(a.code(), a));

        List<String> missing = codes.stream().filter(c -> !accounts.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new LedgerException(LedgerErrorCode.ACCOUNT_NOT_FOUND, "Unknown account(s): " + String.join(", ", missing),
                    Map.of("accountCodes", missing));
        }
        List<String> inactive = accounts.values().stream().filter(a -> !a.active()).map(AccountRef::code).sorted().toList();
        if (!inactive.isEmpty()) {
            throw new LedgerException(LedgerErrorCode.ACCOUNT_INACTIVE, "Inactive account(s): " + String.join(", ", inactive),
                    Map.of("accountCodes", inactive));
        }
        List<Integer> mismatched = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!accounts.get(lines.get(i).accountCode()).currency().equals(lines.get(i).currency())) {
                mismatched.add(i + 1);
            }
        }
        if (!mismatched.isEmpty()) {
            throw new LedgerException(LedgerErrorCode.CURRENCY_MISMATCH,
                    "Line currency differs from account currency on line(s) " + mismatched, Map.of("lines", mismatched));
        }
        return accounts;
    }

    /** One INSERT for all lines: the statement-level balance trigger then runs exactly once. */
    private void insertLines(UUID journalId, PostTransactionRequest request, Map<String, AccountRef> accounts) {
        List<Line> lines = request.lines();
        int n = lines.size();
        Integer[] lineNos = new Integer[n];
        Long[] accountIds = new Long[n];
        String[] directions = new String[n];
        BigDecimal[] amounts = new BigDecimal[n];
        String[] currencies = new String[n];
        String[] memos = new String[n];
        for (int i = 0; i < n; i++) {
            Line line = lines.get(i);
            lineNos[i] = i + 1;
            accountIds[i] = accounts.get(line.accountCode()).id();
            directions[i] = line.direction().code();
            amounts[i] = line.amount();
            currencies[i] = line.currency();
            memos[i] = line.memo();
        }
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO ledger_entries (journal_entry_id, line_no, effective_date, account_id, direction, amount, currency, memo)
                    SELECT ?, t.line_no, ?, t.account_id, t.direction, t.amount, t.currency, t.memo
                    FROM unnest(?::int[], ?::bigint[], ?::text[], ?::numeric[], ?::text[], ?::text[])
                         AS t(line_no, account_id, direction, amount, currency, memo)
                    """);
            ps.setObject(1, journalId);
            ps.setObject(2, request.effectiveDate());
            ps.setArray(3, con.createArrayOf("int4", lineNos));
            ps.setArray(4, con.createArrayOf("int8", accountIds));
            ps.setArray(5, con.createArrayOf("text", directions));
            ps.setArray(6, con.createArrayOf("numeric", amounts));
            ps.setArray(7, con.createArrayOf("text", currencies));
            ps.setArray(8, con.createArrayOf("text", memos));
            return ps;
        });
    }

    private static Map<String, Object> eventPayload(UUID journalId, PostTransactionRequest request, TenantScope scope) {
        Map<String, BigDecimal> debits = new LinkedHashMap<>();
        for (Line line : request.lines()) {
            if (line.direction() == io.ledger.ledger.Direction.DEBIT) {
                debits.merge(line.currency(), line.amount(), BigDecimal::add);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("journalEntryId", journalId.toString());
        payload.put("tenant", scope.tenant().slug());
        payload.put("effectiveDate", request.effectiveDate().toString());
        payload.put("description", request.description());
        payload.put("lineCount", request.lines().size());
        payload.put("totalsByCurrency", debits);
        payload.put("postedBy", scope.principal());
        return payload;
    }
}
