package io.ledger.common.error;

import java.util.Map;

public class LedgerException extends RuntimeException {

    private final LedgerErrorCode code;
    private final transient Map<String, Object> properties;

    public LedgerException(LedgerErrorCode code, String detail) {
        this(code, detail, Map.of(), null);
    }

    public LedgerException(LedgerErrorCode code, String detail, Map<String, Object> properties) {
        this(code, detail, properties, null);
    }

    public LedgerException(LedgerErrorCode code, String detail, Map<String, Object> properties, Throwable cause) {
        super(detail, cause);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public LedgerErrorCode code() {
        return code;
    }

    /** Extra problem members, e.g. the offending account codes. */
    public Map<String, Object> properties() {
        return properties;
    }
}
