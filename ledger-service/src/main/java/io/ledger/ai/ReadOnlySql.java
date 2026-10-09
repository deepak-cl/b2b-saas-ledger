package io.ledger.ai;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import io.ledger.common.error.LedgerErrorCode;
import io.ledger.common.error.LedgerException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Accepts a single read-only SELECT against the tenant's ledger tables. Anything else is
 * rejected before it can reach the database. The caller still runs it as {@code ledger_ai_reader}
 * with {@code statement_timeout}, so a parser miss cannot write.
 */
final class ReadOnlySql {

    static final Set<String> TABLES = Set.of(
            "accounts", "account_balances", "account_period_balances", "journal_entries",
            "ledger_entries", "trial_balance", "accounting_periods");

    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)\\b(pg_sleep|pg_read_file|pg_read_binary_file|pg_ls_dir|lo_import|lo_export|dblink|dblink_exec|"
                    + "set_config|query_to_xml|pg_terminate_backend|pg_cancel_backend)\\s*\\(");

    private ReadOnlySql() {
    }

    /** The statement to run, or a {@link LedgerException} the tool reports back to the model. */
    static String validate(String sql, String tenantSchema) {
        if (sql == null || sql.isBlank() || sql.length() > 2_000) {
            reject("SQL must be a single SELECT of at most 2000 characters");
        }
        String trimmed = sql.trim();
        if (FORBIDDEN.matcher(trimmed).find()) {
            reject("That function is not available to the audit query");
        }
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(trimmed);
        } catch (JSQLParserException e) {
            reject("SQL could not be parsed");
            return trimmed;
        }
        if (statements.size() != 1) {
            reject("Only one statement is allowed");
        }
        Statement statement = statements.get(0);
        if (!(statement instanceof Select)) {
            reject("Only SELECT is allowed");
        }
        if (statement.toString().toLowerCase(Locale.ROOT).contains("for update")) {
            reject("Locking reads are not allowed");
        }
        try {
            for (String name : TablesNamesFinder.findTables(statement.toString())) {
                checkTable(name, tenantSchema);
            }
        } catch (JSQLParserException e) {
            reject("SQL could not be parsed");
        }
        return statement.toString();
    }

    private static void checkTable(String name, String tenantSchema) {
        String[] parts = name.replace("\"", "").split("\\.");
        String table = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        if (!TABLES.contains(table)) {
            reject("Table " + table + " is not available to the audit query");
        }
        if (parts.length > 1) {
            String schema = parts[parts.length - 2].toLowerCase(Locale.ROOT);
            if (!schema.equals(tenantSchema)) {
                reject("Queries must stay in the current tenant schema");
            }
        }
    }

    private static void reject(String detail) {
        throw new LedgerException(LedgerErrorCode.VALIDATION_FAILED, detail);
    }
}
