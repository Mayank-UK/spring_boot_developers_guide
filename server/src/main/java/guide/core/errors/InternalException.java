package guide.core.errors;

import java.util.Map;

/** An unexpected fault on our side (kind: internal → 500 internal_error). */
public class InternalException extends AppException {

    public InternalException(String message) {
        this(message, null, null);
    }

    public InternalException(String message, Map<String, Object> details) {
        this(message, details, null);
    }

    public InternalException(String message, Map<String, Object> details, Throwable cause) {
        super(AppException.toFailure(FailureKind.INTERNAL, message, details), cause);
    }

    InternalException(Failure failure) {
        super(failure);
    }
}
