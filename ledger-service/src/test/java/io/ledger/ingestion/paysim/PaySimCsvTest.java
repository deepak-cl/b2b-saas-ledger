package io.ledger.ingestion.paysim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaySimCsvTest {

    @Test
    void parsesATransferAndSkipsGarbage() {
        PaySimCsv row = PaySimCsv.parse("12,TRANSFER,10.5,C1,20,9.5,M9,0,10.5,1,0", 4);
        assertThat(row.skipped()).isFalse();
        assertThat(row.step()).isEqualTo(12);
        assertThat(row.type()).isEqualTo("TRANSFER");
        assertThat(row.amount()).isEqualByComparingTo("10.5000");
        assertThat(row.fraud()).isTrue();

        assertThat(PaySimCsv.parse("1,UNKNOWN,10,C1,0,0,M1,0,0,0,0", 1).skipped()).isTrue();
        assertThat(PaySimCsv.parse("1,PAYMENT,0,C1,0,0,M1,0,0,0,0", 2).skipped()).isTrue();
        assertThat(PaySimCsv.parse("", 3)).isNull();
    }
}
