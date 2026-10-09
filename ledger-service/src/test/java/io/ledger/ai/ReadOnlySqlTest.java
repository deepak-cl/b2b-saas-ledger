package io.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ledger.common.error.LedgerErrorCode;
import io.ledger.common.error.LedgerException;
import org.junit.jupiter.api.Test;

class ReadOnlySqlTest {

    @Test
    void acceptsASingleSelectOnLedgerTables() {
        String sql = ReadOnlySql.validate("SELECT code, name FROM accounts WHERE code = '6100'", "t_acme");
        assertThat(sql).containsIgnoringCase("accounts");
    }

    @Test
    void acceptsTheTenantSchemaQualifier() {
        assertThat(ReadOnlySql.validate("SELECT code FROM t_acme.accounts", "t_acme")).contains("accounts");
    }

    @Test
    void rejectsWritesOtherSchemasAndStackedStatements() {
        assertRejected("UPDATE accounts SET name = 'x'");
        assertRejected("SELECT code FROM accounts; DROP TABLE accounts");
        assertRejected("SELECT pg_sleep(10)");
        assertRejected("SELECT * FROM public.tenants");
        assertRejected("SELECT * FROM t_globex.accounts");
        assertRejected("SELECT code FROM accounts FOR UPDATE");
    }

    private static void assertRejected(String sql) {
        assertThatThrownBy(() -> ReadOnlySql.validate(sql, "t_acme"))
                .isInstanceOf(LedgerException.class)
                .extracting(ex -> ((LedgerException) ex).code())
                .isEqualTo(LedgerErrorCode.VALIDATION_FAILED);
    }
}
