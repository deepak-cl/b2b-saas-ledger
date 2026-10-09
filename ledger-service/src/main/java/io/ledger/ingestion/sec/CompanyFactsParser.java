package io.ledger.ingestion.sec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;

/**
 * Reads a company-facts document ({@code /api/xbrl/companyfacts/CIK##########.json}) down to
 * one balance-sheet snapshot per fiscal year. Only {@code 10-K} / {@code FY} facts in USD are
 * used; when the same period is filed more than once, the latest {@code filed} date wins.
 */
public final class CompanyFactsParser {

    private static final List<String> EQUITY_CONCEPTS = List.of(
            "StockholdersEquity",
            "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest");

    private CompanyFactsParser() {
    }

    public record AnnualSnapshot(
            LocalDate periodEnd,
            String accession,
            BigDecimal assets,
            BigDecimal liabilities,
            BigDecimal equity) {
    }

    public record Parsed(String entityName, List<AnnualSnapshot> years) {
    }

    public static Parsed parse(JsonNode root, int maxYears) {
        String entity = text(root, "entityName");
        JsonNode gaap = root.path("facts").path("us-gaap");
        Map<LocalDate, BigDecimal> assets = facts(gaap.path("Assets"));
        Map<LocalDate, BigDecimal> liabilities = facts(gaap.path("Liabilities"));
        Map<LocalDate, BigDecimal> equity = Map.of();
        for (String concept : EQUITY_CONCEPTS) {
            equity = facts(gaap.path(concept));
            if (!equity.isEmpty()) {
                break;
            }
        }
        Map<LocalDate, String> accessions = accessions(gaap.path("Assets"));

        List<AnnualSnapshot> years = new ArrayList<>();
        for (LocalDate end : assets.keySet()) {
            if (!liabilities.containsKey(end) || !equity.containsKey(end)) {
                continue;
            }
            years.add(new AnnualSnapshot(end, accessions.get(end), assets.get(end), liabilities.get(end), equity.get(end)));
        }
        years.sort(Comparator.comparing(AnnualSnapshot::periodEnd));
        if (years.size() > maxYears) {
            years = new ArrayList<>(years.subList(years.size() - maxYears, years.size()));
        }
        return new Parsed(entity == null ? "" : entity, List.copyOf(years));
    }

    /** Latest filing per period end. */
    private static Map<LocalDate, BigDecimal> facts(JsonNode concept) {
        Map<LocalDate, BigDecimal> values = new TreeMap<>();
        Map<LocalDate, LocalDate> filed = new TreeMap<>();
        for (JsonNode fact : usdFacts(concept)) {
            LocalDate end = date(fact, "end");
            BigDecimal val = decimal(fact);
            if (end == null || val == null) {
                continue;
            }
            LocalDate when = date(fact, "filed");
            if (when == null) {
                when = end;
            }
            LocalDate previous = filed.get(end);
            if (previous == null || when.isAfter(previous)) {
                filed.put(end, when);
                values.put(end, val);
            }
        }
        return values;
    }

    private static Map<LocalDate, String> accessions(JsonNode concept) {
        Map<LocalDate, String> values = new TreeMap<>();
        Map<LocalDate, LocalDate> filed = new TreeMap<>();
        for (JsonNode fact : usdFacts(concept)) {
            LocalDate end = date(fact, "end");
            if (end == null) {
                continue;
            }
            LocalDate when = date(fact, "filed");
            if (when == null) {
                when = end;
            }
            LocalDate previous = filed.get(end);
            if (previous == null || when.isAfter(previous)) {
                filed.put(end, when);
                values.put(end, text(fact, "accn"));
            }
        }
        return values;
    }

    private static List<JsonNode> usdFacts(JsonNode concept) {
        JsonNode units = concept.path("units").path("USD");
        if (!units.isArray()) {
            return List.of();
        }
        List<JsonNode> facts = new ArrayList<>();
        for (JsonNode fact : units) {
            if ("10-K".equals(text(fact, "form")) && "FY".equals(text(fact, "fp"))) {
                facts.add(fact);
            }
        }
        return facts;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isBlank() ? null : text;
    }

    private static LocalDate date(JsonNode node, String field) {
        String text = text(node, field);
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode fact) {
        JsonNode val = fact.get("val");
        if (val == null || val.isNull() || !val.isNumber()) {
            return null;
        }
        return val.decimalValue();
    }
}
