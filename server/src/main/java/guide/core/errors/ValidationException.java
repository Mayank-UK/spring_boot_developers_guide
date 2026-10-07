package guide.core.errors;

import java.util.Map;

/** The caller's input is malformed (kind: validation → 400 validation_failed). */
public class ValidationException extends AppException {

    public ValidationException(String message) {
        this(message, null, null);
    }

    public ValidationException(String message, Map<String, Object> details) {
        this(message, details, null);
    }

    public ValidationException(String message, Map<String, Object> details, Throwable cause) {
        super(AppException.toFailure(FailureKind.VALIDATION, message, details), cause);
    }

    ValidationException(Failure failure) {
        super(failure);
    }
}
