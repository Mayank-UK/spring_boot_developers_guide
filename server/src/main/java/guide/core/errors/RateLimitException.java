package guide.core.errors;

import java.util.Map;

/** The caller exceeded a rate budget (kind: rate_limited → 429 rate_limited). */
public class RateLimitException extends AppException {

    public RateLimitException(String message) {
        this(message, null, null);
    }

    public RateLimitException(String message, Map<String, Object> details) {
        this(message, details, null);
    }

    public RateLimitException(String message, Map<String, Object> details, Throwable cause) {
        super(AppException.toFailure(FailureKind.RATE_LIMITED, message, details), cause);
    }

    RateLimitException(Failure failure) {
        super(failure);
    }
}
