package guide.core.errors;

import java.util.Map;

/** The request conflicts with current state (kind: conflict → 409 conflict). */
public class ConflictException extends AppException {

    public ConflictException(String message) {
        this(message, null, null);
    }

    public ConflictException(String message, Map<String, Object> details) {
        this(message, details, null);
    }

    public ConflictException(String message, Map<String, Object> details, Throwable cause) {
        super(AppException.toFailure(FailureKind.CONFLICT, message, details), cause);
    }

    ConflictException(Failure failure) {
        super(failure);
    }
}
