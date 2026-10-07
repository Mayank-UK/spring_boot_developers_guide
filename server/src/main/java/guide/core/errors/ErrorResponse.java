package guide.core.errors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE error envelope — the one shape every failure leaves the app in:
 *
 *   { "error": { "code", "message", "requestId", ...details } }
 *
 * The failure's details are merged in first so the canonical keys win on
 * collision. Wire-compatible with the express guide's envelope (the Phase 2
 * client parses these keys).
 */
public record ErrorResponse(Map<String, Object> error) {

    public ErrorResponse {
        error = Collections.unmodifiableMap(new LinkedHashMap<>(error));
    }

    public static ErrorResponse of(String code, String message, String requestId, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (details != null) {
            body.putAll(details);
        }
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", requestId);
        return new ErrorResponse(body);
    }
}
