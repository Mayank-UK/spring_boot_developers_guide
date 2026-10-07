package guide.core.errors;

import java.util.Map;

/** The addressed resource does not exist (kind: not_found → 404 not_found). */
public class NotFoundException extends AppException {

    public NotFoundException(String message) {
        this(message, null, null);
    }

    public NotFoundException(String message, Map<String, Object> details) {
        this(message, details, null);
    }

    public NotFoundException(String message, Map<String, Object> details, Throwable cause) {
        super(AppException.toFailure(FailureKind.NOT_FOUND, message, details), cause);
    }

    NotFoundException(Failure failure) {
        super(failure);
    }
}
