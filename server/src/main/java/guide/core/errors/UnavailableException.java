package guide.core.errors;

import java.util.Map;

/** A dependency is down or failure is injected (kind: unavailable → 503 service_unavailable). */
public class UnavailableException extends AppException {

    public UnavailableException(String message) {
        this(message, null, null);
    }

    public UnavailableException(String message, Map<String, Object> details) {
        this(message, details, null);
    }

    public UnavailableException(String message, Map<String, Object> details, Throwable cause) {
        super(AppException.toFailure(FailureKind.UNAVAILABLE, message, details), cause);
    }

    UnavailableException(Failure failure) {
        super(failure);
    }
}
