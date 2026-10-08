package io.ledger.common.error;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/**
 * Maps the ledger schema's custom SQLSTATEs (raised by triggers, see tenant V1 migration)
 * and contention states to API error codes. Works on any exception chain, so it also
 * catches errors raised at COMMIT by the deferred seal trigger.
 */
public final class SqlStateTranslator {

    private static final Map<String, LedgerErrorCode> CODES = Map.ofEntries(
            Map.entry("LG001", LedgerErrorCode.LEDGER_UNBALANCED),
            Map.entry("LG002", LedgerErrorCode.INSUFFICIENT_LINES),
            Map.entry("LG003", LedgerErrorCode.LEDGER_IMMUTABLE),
            Map.entry("LG004", LedgerErrorCode.JOURNAL_SEALED),
            Map.entry("LG005", LedgerErrorCode.PERIOD_CLOSED),
            Map.entry("LG006", LedgerErrorCode.INSUFFICIENT_FUNDS),
            Map.entry("LG007", LedgerErrorCode.PERIOD_NOT_OPEN),
            Map.entry("55P03", LedgerErrorCode.LEDGER_CONTENTION), // lock_timeout
            Map.entry("40P01", LedgerErrorCode.LEDGER_CONTENTION), // deadlock
            Map.entry("40001", LedgerErrorCode.LEDGER_CONTENTION), // serialization failure
            Map.entry("57014", LedgerErrorCode.LEDGER_CONTENTION)  // statement_timeout
    );

    private SqlStateTranslator() {
    }

    public static Optional<LedgerException> translate(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    LedgerErrorCode code = CODES.get(s.getSQLState());
                    if (code != null) {
                        return Optional.of(new LedgerException(code, firstLine(s.getMessage()), Map.of(), error));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return null;
        }
        String m = message.startsWith("ERROR: ") ? message.substring(7) : message;
        int nl = m.indexOf('\n');
        return nl < 0 ? m : m.substring(0, nl);
    }
}
