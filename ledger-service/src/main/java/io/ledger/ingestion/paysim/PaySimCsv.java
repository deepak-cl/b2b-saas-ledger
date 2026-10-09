package io.ledger.ingestion.paysim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/**
 * One data line of a PaySim CSV ({@code step,type,amount,nameOrig,...,nameDest,...,isFraud}).
 * Fields are unquoted in the Kaggle file, so a split is enough and the file is never buffered
 * whole. Rows that cannot become a journal (unknown type, non-positive amount) are skipped.
 */
public record PaySimCsv(long lineNumber, int step, String type, BigDecimal amount, String nameOrig, String nameDest,
                        boolean fraud, boolean skipped) {

    static final Set<String> TYPES = Set.of("CASH_IN", "CASH_OUT", "DEBIT", "PAYMENT", "TRANSFER");

    /** {@code null} when {@code line} is blank. Skipped rows still carry their line number. */
    public static PaySimCsv parse(String line, long lineNumber) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] fields = line.split(",", -1);
        if (fields.length < 7) {
            return skipped(lineNumber);
        }
        try {
            int step = Integer.parseInt(fields[0].trim());
            String type = fields[1].trim().toUpperCase(Locale.ROOT);
            BigDecimal amount = new BigDecimal(fields[2].trim()).setScale(4, RoundingMode.HALF_UP);
            String nameOrig = fields[3].trim();
            String nameDest = fields[6].trim();
            boolean fraud = fields.length > 9 && "1".equals(fields[9].trim());
            if (step < 1 || !TYPES.contains(type) || amount.signum() <= 0 || amount.precision() - amount.scale() > 16) {
                return skipped(lineNumber);
            }
            return new PaySimCsv(lineNumber, step, type, amount, nameOrig, nameDest, fraud, false);
        } catch (NumberFormatException e) {
            return skipped(lineNumber);
        }
    }

    private static PaySimCsv skipped(long lineNumber) {
        return new PaySimCsv(lineNumber, 0, "", BigDecimal.ZERO, "", "", false, true);
    }
}
