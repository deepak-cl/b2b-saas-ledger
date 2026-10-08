package io.ledger.ledger;

import jakarta.persistence.AttributeConverter;

/** Side of a ledger line; stored as {@code D}/{@code C}, exposed as DEBIT/CREDIT. */
public enum Direction {
    DEBIT("D"), CREDIT("C");

    private final String code;

    Direction(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Direction fromCode(String code) {
        return switch (code) {
            case "D" -> DEBIT;
            case "C" -> CREDIT;
            default -> throw new IllegalArgumentException("Unknown direction code " + code);
        };
    }

    @jakarta.persistence.Converter
    public static class Converter implements AttributeConverter<Direction, String> {
        @Override
        public String convertToDatabaseColumn(Direction direction) {
            return direction == null ? null : direction.code;
        }

        @Override
        public Direction convertToEntityAttribute(String code) {
            return code == null ? null : fromCode(code);
        }
    }
}
