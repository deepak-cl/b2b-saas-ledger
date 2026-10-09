package io.ledger.ingestion.paysim;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.ledger.ingestion.IngestionAccounts;
import io.ledger.ledger.Direction;
import io.ledger.ledger.transaction.JournalSource;
import io.ledger.ledger.transaction.LedgerPostingService;
import io.ledger.ledger.transaction.LedgerPostingService.PostingResult;
import io.ledger.ledger.transaction.PostTransactionRequest;
import io.ledger.ledger.transaction.PostTransactionRequest.Line;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * One PaySim row becomes one balanced journal. The idempotency key is the file fingerprint
 * plus the line number, so loading the same file again replays and a different file does not
 * collide with it. Customer names stay in metadata; they do not become accounts.
 */
public class PaySimWriter implements ItemWriter<PaySimCsv>, ItemStream {

    private final LedgerPostingService posting;
    private final String currency;
    private final String fileHash;
    private final LocalDate epoch;
    private long posted;
    private long replayed;

    public PaySimWriter(LedgerPostingService posting, String currency, String fileHash, LocalDate epoch) {
        this.posting = posting;
        this.currency = currency;
        this.fileHash = fileHash;
        this.epoch = epoch;
    }

    @Override
    public void write(Chunk<? extends PaySimCsv> chunk) {
        for (PaySimCsv row : chunk) {
            if (row.skipped()) {
                continue;
            }
            PostingResult result = posting.post(key(row), request(row), null, JournalSource.PAYSIM);
            if (result.replayed()) {
                replayed++;
                tally("replayed");
            } else {
                posted++;
                tally("posted");
            }
        }
    }

    private String key(PaySimCsv row) {
        return "ps-" + fileHash + "-" + row.lineNumber();
    }

    private PostTransactionRequest request(PaySimCsv row) {
        boolean inbound = "CASH_IN".equals(row.type());
        String counter = switch (row.type()) {
            case "CASH_IN" -> "4800";
            case "CASH_OUT" -> "6820";
            case "PAYMENT" -> "6810";
            case "DEBIT" -> "6830";
            default -> "6800";
        };
        Line cash = new Line(IngestionAccounts.PAYSIM_CASH, inbound ? Direction.DEBIT : Direction.CREDIT,
                row.amount(), currency, row.type());
        Line other = new Line(counter, inbound ? Direction.CREDIT : Direction.DEBIT, row.amount(), currency, row.type());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("step", row.step());
        metadata.put("type", row.type());
        metadata.put("nameOrig", row.nameOrig());
        metadata.put("nameDest", row.nameDest());
        metadata.put("fraud", row.fraud());
        metadata.put("file", fileHash);
        LocalDate date = epoch.plusDays((row.step() - 1L) / 24);
        String description = "PaySim " + row.type() + " step " + row.step();
        return new PostTransactionRequest(date, description, "paysim:" + row.lineNumber(), metadata, List.of(cash, other));
    }

    /** The step scope can hand {@code update} a different instance, so the count lives on the step itself. */
    private static void tally(String key) {
        var step = PaySimTenantListener.CURRENT.get();
        if (step == null) {
            return;
        }
        ExecutionContext executionContext = step.getExecutionContext();
        executionContext.putLong(key, executionContext.getLong(key, 0) + 1);
    }

    @Override
    public void update(ExecutionContext executionContext) {
        // Counts are written onto the step context in write(); a later update on a fresh
        // step-scoped instance must not replace them with zero.
        executionContext.putLong("posted", Math.max(posted, executionContext.getLong("posted", 0)));
        executionContext.putLong("replayed", Math.max(replayed, executionContext.getLong("replayed", 0)));
    }
}
