package io.ledger.ingestion.sec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.ledger.ingestion.IngestionAccounts;
import io.ledger.ingestion.sec.CompanyFactsParser.AnnualSnapshot;
import io.ledger.ledger.Direction;
import io.ledger.ledger.transaction.PostTransactionRequest;
import io.ledger.ledger.transaction.PostTransactionRequest.Line;

/**
 * Turns yearly reported balances into balanced journals. The oldest year in the window is the
 * opening snapshot; every later year is the change since the previous imported year. A plug
 * line (equity) absorbs any gap in the accounting equation so the database balance check holds.
 * Re-importing the same window replays, because each journal's idempotency key is the CIK plus
 * the period end.
 */
public final class SecJournalMapper {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);

    private SecJournalMapper() {
    }

    public record Mapped(String idempotencyKey, PostTransactionRequest request) {
    }

    public static List<Mapped> journals(String cik10, String entityName, List<AnnualSnapshot> years, String currency) {
        List<Mapped> mapped = new ArrayList<>();
        AnnualSnapshot previous = null;
        for (AnnualSnapshot year : years) {
            Mapped journal = journal(cik10, entityName, year, previous, currency);
            if (journal != null) {
                mapped.add(journal);
            }
            previous = year;
        }
        return List.copyOf(mapped);
    }

    private static Mapped journal(String cik10, String entity, AnnualSnapshot year, AnnualSnapshot previous, String currency) {
        BigDecimal assets = delta(year.assets(), previous == null ? null : previous.assets());
        BigDecimal liabilities = delta(year.liabilities(), previous == null ? null : previous.liabilities());
        BigDecimal equity = delta(year.equity(), previous == null ? null : previous.equity());

        List<Line> lines = new ArrayList<>();
        add(lines, IngestionAccounts.SEC_ASSETS, assets, true, currency, "us-gaap:Assets");
        add(lines, IngestionAccounts.SEC_LIABILITIES, liabilities, false, currency, "us-gaap:Liabilities");
        add(lines, IngestionAccounts.SEC_EQUITY, equity, false, currency, "us-gaap:StockholdersEquity");
        plug(lines, currency);
        if (lines.size() < 2) {
            return null;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("cik", cik10);
        metadata.put("form", "10-K");
        metadata.put("periodEnd", year.periodEnd().toString());
        metadata.put("opening", previous == null);
        metadata.put("assets", year.assets().toPlainString());
        metadata.put("liabilities", year.liabilities().toPlainString());
        metadata.put("equity", year.equity().toPlainString());

        String description = ("SEC 10-K " + entity + " FY " + year.periodEnd()).trim();
        if (description.length() > 500) {
            description = description.substring(0, 500);
        }
        String accession = year.accession();
        if (accession != null && accession.length() > 128) {
            accession = accession.substring(0, 128);
        }
        return new Mapped(
                "sec-" + cik10 + "-bs-" + year.periodEnd(),
                new PostTransactionRequest(year.periodEnd(), description, accession, metadata, List.copyOf(lines)));
    }

    private static BigDecimal delta(BigDecimal current, BigDecimal baseline) {
        BigDecimal value = current.subtract(baseline == null ? BigDecimal.ZERO : baseline);
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    /** Debit-positive accounts (assets) debit when the delta is positive; credit-positive accounts do the opposite. */
    private static void add(List<Line> lines, String account, BigDecimal delta, boolean debitPositive, String currency, String memo) {
        if (delta.signum() == 0) {
            return;
        }
        boolean debit = debitPositive == (delta.signum() > 0);
        lines.add(new Line(account, debit ? Direction.DEBIT : Direction.CREDIT, delta.abs(), currency, memo));
    }

    private static void plug(List<Line> lines, String currency) {
        BigDecimal debits = ZERO;
        BigDecimal credits = ZERO;
        for (Line line : lines) {
            if (line.direction() == Direction.DEBIT) {
                debits = debits.add(line.amount());
            } else {
                credits = credits.add(line.amount());
            }
        }
        BigDecimal gap = debits.subtract(credits);
        if (gap.signum() == 0) {
            return;
        }
        // Positive gap: debits outweigh credits, so the plug is a credit.
        Direction direction = gap.signum() > 0 ? Direction.CREDIT : Direction.DEBIT;
        lines.add(new Line(IngestionAccounts.SEC_PLUG, direction, gap.abs(), currency, "SEC accounting-equation plug"));
    }
}
