package io.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditAnswerTest {

    @Test
    void cashQuestionStatesTheCashBalance() {
        String evidence = """
                balance=4625.0000 code=1000 name=Operating Cash type=ASSET
                balance=1500.0000 code=1100 name=Accounts Receivable type=ASSET
                balance=5125.0000 code=6100 name=Cloud Infrastructure type=EXPENSE
                """;
        assertThat(AuditAnswer.speak("How much cash is left?", evidence)).isEqualTo("Operating Cash is 4,625.00.");
    }

    @Test
    void cashQuestionWithoutACashAccountDoesNotTalkAboutSpikes() {
        String evidence = """
                balance=0.0000 code=9998 name=Smoke Debit type=EXPENSE
                balance=0.0000 code=9999 name=Smoke Credit type=LIABILITY
                """;
        String answer = AuditAnswer.speak("How much cash is left?", evidence);
        assertThat(answer).contains("no cash account");
        assertThat(answer).contains("Smoke Debit is 0.00.");
        assertThat(answer).doesNotContain("anomal");
    }

    @Test
    void spikeQuestionKeepsTheSpikeResult() {
        assertThat(AuditAnswer.speak("Did cloud spending spike?", "No spending anomalies in 2026-05 to 2026-10."))
                .isEqualTo("No month in that window stands out as a spending spike.");
        assertThat(AuditAnswer.speak("Find cloud spending anomalies", "6100 2026-02 1000.0000 median 100.0000 HIGH"))
                .contains("Account 6100")
                .contains("1,000.00")
                .contains("2026-02");
    }

    @Test
    void monthlyQuestionStatesEachMonth() {
        String evidence = "amount=100.0000 category=cloud code=6100 name=Cloud Infrastructure period=2026-01";
        assertThat(AuditAnswer.speak("Show spending each month", evidence))
                .isEqualTo("Cloud Infrastructure was 100.00 in 2026-01.");
    }

    @Test
    void readOnlySqlIsLeftIntact() {
        assertThat(AuditAnswer.speak("readonly: SELECT code FROM accounts", "code=6100")).isEqualTo("code=6100");
        assertThat(AuditAnswer.speak("readonly: DROP TABLE accounts", "Rejected: not a select")).startsWith("Rejected:");
    }
}
