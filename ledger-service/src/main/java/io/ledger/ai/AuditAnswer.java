package io.ledger.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one whitelist tool result into a sentence about the question that was asked.
 * The stub model echoes this text, so a cash question cannot come back as a spike check.
 */
final class AuditAnswer {

    private static final Pattern BALANCE = Pattern.compile("balance=(\\S+) code=(\\S+) name=(.*) type=(\\S+)");
    private static final Pattern MONTH = Pattern.compile("amount=(\\S+) category=(.*?) code=(\\S+) name=(.*) period=(\\d{4}-\\d{2})");
    private static final Pattern SPIKE = Pattern.compile("(\\S+) (\\d{4}-\\d{2}) (\\S+) median (\\S+) (HIGH|MEDIUM)");

    private AuditAnswer() {
    }

    static String speak(String query, String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return "Nothing in these books answers that.";
        }
        String trimmed = evidence.trim();
        if (trimmed.startsWith("Rejected:")) {
            return trimmed;
        }
        if (trimmed.startsWith("No rows")) {
            return "Nothing in these books matches that.";
        }
        if (trimmed.startsWith("No spending anomalies")) {
            return "No month in that window stands out as a spending spike.";
        }
        List<String> spikes = spikes(trimmed);
        if (spikes != null) {
            return String.join(" ", spikes);
        }
        List<Balance> balances = balances(trimmed);
        if (balances != null) {
            return balances(query, balances);
        }
        List<Month> months = months(trimmed);
        if (months != null) {
            return months(months);
        }
        return trimmed;
    }

    private static String balances(String query, List<Balance> balances) {
        String lowered = query.toLowerCase(Locale.ROOT);
        if (contains(lowered, "cash", "bank")) {
            List<Balance> cash = balances.stream()
                    .filter(row -> row.name.toLowerCase(Locale.ROOT).contains("cash") || "1000".equals(row.code))
                    .toList();
            if (cash.isEmpty()) {
                return "There is no cash account in these books. " + list(balances);
            }
            return list(cash);
        }
        if (contains(lowered, "receiv", "owe us", "owed to us", "who owes")) {
            return matching(balances, "receiv", "Nobody is shown as owing this company. " + list(balances));
        }
        if (contains(lowered, "payable", "we owe", "vendor", "bill")) {
            return matching(balances, "payable", "These books do not list a payable. " + list(balances));
        }
        if (contains(lowered, "spend", "spent", "expense", "cost", "cloud")) {
            List<Balance> expenses = balances.stream().filter(row -> "EXPENSE".equals(row.type)).toList();
            if (contains(lowered, "cloud")) {
                List<Balance> cloud = expenses.stream()
                        .filter(row -> row.name.toLowerCase(Locale.ROOT).contains("cloud") || "6100".equals(row.code))
                        .toList();
                if (!cloud.isEmpty()) {
                    return list(cloud);
                }
            }
            if (expenses.isEmpty()) {
                return "These books do not list an expense account. " + list(balances);
            }
            return list(expenses);
        }
        return list(balances);
    }

    private static String matching(List<Balance> balances, String namePart, String empty) {
        List<Balance> found = balances.stream()
                .filter(row -> row.name.toLowerCase(Locale.ROOT).contains(namePart))
                .toList();
        return found.isEmpty() ? empty : list(found);
    }

    private static String list(List<Balance> balances) {
        int shown = Math.min(balances.size(), 6);
        List<String> sentences = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            Balance row = balances.get(i);
            sentences.add(row.name + " is " + money(row.balance) + ".");
        }
        if (balances.size() > shown) {
            sentences.add((balances.size() - shown) + " more accounts are on these books.");
        }
        return String.join(" ", sentences);
    }

    private static String months(List<Month> months) {
        int shown = Math.min(months.size(), 6);
        List<String> sentences = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            Month row = months.get(i);
            sentences.add(row.name + " was " + money(row.amount) + " in " + row.period + ".");
        }
        if (months.size() > shown) {
            sentences.add((months.size() - shown) + " more months are in that window.");
        }
        return String.join(" ", sentences);
    }

    private static List<String> spikes(String evidence) {
        String[] lines = evidence.split("\n");
        List<String> spoken = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = SPIKE.matcher(line.trim());
            if (!matcher.matches()) {
                return null;
            }
            spoken.add("Account " + matcher.group(1) + " spent " + money(matcher.group(3))
                    + " in " + matcher.group(2) + ", against a typical month of " + money(matcher.group(4)) + ".");
        }
        return spoken.isEmpty() ? null : spoken;
    }

    private static List<Balance> balances(String evidence) {
        String[] lines = evidence.split("\n");
        List<Balance> rows = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = BALANCE.matcher(line.trim());
            if (!matcher.matches()) {
                return null;
            }
            rows.add(new Balance(matcher.group(2), matcher.group(3).trim(), matcher.group(4), matcher.group(1)));
        }
        return rows.isEmpty() ? null : rows;
    }

    private static List<Month> months(String evidence) {
        String[] lines = evidence.split("\n");
        List<Month> rows = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = MONTH.matcher(line.trim());
            if (!matcher.matches()) {
                return null;
            }
            rows.add(new Month(matcher.group(4).trim(), matcher.group(5), matcher.group(1)));
        }
        return rows.isEmpty() ? null : rows;
    }

    private static boolean contains(String query, String... words) {
        for (String word : words) {
            if (query.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String money(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw).setScale(2, RoundingMode.HALF_UP);
            return String.format(Locale.US, "%,.2f", value);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private record Balance(String code, String name, String type, String balance) {
    }

    private record Month(String name, String period, String amount) {
    }
}
