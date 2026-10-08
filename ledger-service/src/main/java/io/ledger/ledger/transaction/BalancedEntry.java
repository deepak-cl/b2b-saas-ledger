package io.ledger.ledger.transaction;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

import io.ledger.ledger.Direction;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Fail-fast check that debits equal credits per currency. The database enforces the same
 * rule at commit (deferred trigger); this gives the client a precise 422 without a round
 * trip, the trigger guarantees it for every other write path.
 */
@Documented
@Constraint(validatedBy = BalancedEntry.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface BalancedEntry {

    String message() default "debits must equal credits per currency";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<BalancedEntry, PostTransactionRequest> {
        @Override
        public boolean isValid(PostTransactionRequest request, ConstraintValidatorContext context) {
            if (request == null || request.lines() == null) {
                return true;
            }
            Map<String, BigDecimal> net = new TreeMap<>();
            for (PostTransactionRequest.Line line : request.lines()) {
                if (line == null || line.amount() == null || line.direction() == null || line.currency() == null) {
                    return true; // field-level constraints report these
                }
                BigDecimal signed = line.direction() == Direction.DEBIT ? line.amount() : line.amount().negate();
                net.merge(line.currency(), signed, BigDecimal::add);
            }
            boolean valid = true;
            context.disableDefaultConstraintViolation();
            for (Map.Entry<String, BigDecimal> e : net.entrySet()) {
                if (e.getValue().signum() != 0) {
                    valid = false;
                    context.buildConstraintViolationWithTemplate(
                                    "debits and credits differ by " + e.getValue().toPlainString() + " " + e.getKey())
                            .addConstraintViolation();
                }
            }
            return valid;
        }
    }
}
