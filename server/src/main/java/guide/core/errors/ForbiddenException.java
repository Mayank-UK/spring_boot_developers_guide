package guide.core.errors;

import java.util.Map;

/** The caller is authenticated but not allowed (kind: forbidden → 403 forbidden). */
public class ForbiddenException extends AppException {

    public ForbiddenException(String message) {
        this(message, null, null);
    }

    public ForbiddenException(String message, Map<String, Object> details) {
        this(message, details, null);
    }

    public ForbiddenException(String message, Map<String, Object> details, Throwable cause) {
        super(AppException.toFailure(FailureKind.FORBIDDEN, message, details), cause);
    }

    ForbiddenException(Failure failure) {
        super(failure);
    }
}
