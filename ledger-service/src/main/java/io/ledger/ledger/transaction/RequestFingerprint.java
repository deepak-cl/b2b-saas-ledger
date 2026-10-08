package io.ledger.ledger.transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.json.JsonMapper;

/**
 * SHA-256 over a canonical form of the request, so semantically identical retries match
 * (amount scale, metadata key order and whitespace do not matter).
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static byte[] of(PostTransactionRequest request, JsonMapper jsonMapper) {
        StringBuilder sb = new StringBuilder()
                .append(request.effectiveDate()).append('\n')
                .append(request.description()).append('\n')
                .append(nullToEmpty(request.externalRef())).append('\n')
                .append(jsonMapper.writeValueAsString(canonical(request.metadata() == null ? Map.of() : request.metadata())))
                .append('\n');
        for (PostTransactionRequest.Line line : request.lines()) {
            sb.append(line.accountCode()).append('|')
                    .append(line.direction().code()).append('|')
                    .append(line.amount().stripTrailingZeros().toPlainString()).append('|')
                    .append(line.currency()).append('|')
                    .append(nullToEmpty(line.memo())).append('\n');
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(RequestFingerprint::canonical).toList();
        }
        return value;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
