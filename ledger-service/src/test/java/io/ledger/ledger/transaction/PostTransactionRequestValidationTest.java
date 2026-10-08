package io.ledger.ledger.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.ledger.ledger.Direction;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PostTransactionRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static PostTransactionRequest.Line line(String account, Direction direction, String amount, String currency) {
        return new PostTransactionRequest.Line(account, direction, new BigDecimal(amount), currency, null);
    }

    private static PostTransactionRequest request(PostTransactionRequest.Line... lines) {
        return new PostTransactionRequest(LocalDate.of(2026, 10, 8), "test", null, Map.of(), List.of(lines));
    }

    private Set<String> messages(PostTransactionRequest request) {
        return validator.validate(request).stream().map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void balancedEntryIsValid() {
        assertThat(messages(request(
                line("6100", Direction.DEBIT, "1250.00", "USD"),
                line("1000", Direction.CREDIT, "1250", "USD")))).isEmpty();
    }

    @Test
    void balanceIsCheckedPerCurrency() {
        // 100 USD debit vs 100 EUR credit: equal numbers, different currencies.
        assertThat(messages(request(
                line("1000", Direction.DEBIT, "100", "USD"),
                line("1001", Direction.CREDIT, "100", "EUR"))))
                .containsExactlyInAnyOrder("debits and credits differ by 100 USD", "debits and credits differ by -100 EUR");
    }

    @Test
    void multiLineSplitBalances() {
        assertThat(messages(request(
                line("6100", Direction.DEBIT, "700.10", "USD"),
                line("6300", Direction.DEBIT, "299.90", "USD"),
                line("1000", Direction.CREDIT, "1000.0000", "USD")))).isEmpty();
    }

    @Test
    void rejectsNonPositiveAndOverPreciseAmounts() {
        assertThat(messages(request(
                line("1000", Direction.DEBIT, "0", "USD"),
                line("1001", Direction.CREDIT, "0", "USD")))).contains("must be greater than 0");
        assertThat(messages(request(
                line("1000", Direction.DEBIT, "1.00001", "USD"),
                line("1001", Direction.CREDIT, "1.00001", "USD")))).anyMatch(m -> m.startsWith("numeric value out of bounds"));
    }

    @Test
    void requiresAtLeastTwoLines() {
        assertThat(validator.validate(request(line("1000", Direction.DEBIT, "1", "USD"))))
                .anyMatch(v -> v.getPropertyPath().toString().equals("lines"));
    }

    @Test
    void fingerprintIgnoresAmountScaleAndMetadataOrder() {
        JsonMapper mapper = JsonMapper.builder().build();
        var a = new PostTransactionRequest(LocalDate.of(2026, 10, 8), "x", null,
                Map.of("a", 1, "b", Map.of("y", 2, "x", 1)),
                List.of(line("1000", Direction.DEBIT, "10.00", "USD"), line("3000", Direction.CREDIT, "10", "USD")));
        var b = new PostTransactionRequest(LocalDate.of(2026, 10, 8), "x", null,
                new java.util.LinkedHashMap<>(Map.of("b", Map.of("x", 1, "y", 2), "a", 1)),
                List.of(line("1000", Direction.DEBIT, "10", "USD"), line("3000", Direction.CREDIT, "10.0000", "USD")));
        var c = new PostTransactionRequest(LocalDate.of(2026, 10, 8), "x", null, Map.of(),
                List.of(line("1000", Direction.DEBIT, "11", "USD"), line("3000", Direction.CREDIT, "11", "USD")));

        assertThat(RequestFingerprint.of(a, mapper)).isEqualTo(RequestFingerprint.of(b, mapper));
        assertThat(RequestFingerprint.of(a, mapper)).isNotEqualTo(RequestFingerprint.of(c, mapper));
    }
}
