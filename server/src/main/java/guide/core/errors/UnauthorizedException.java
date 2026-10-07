package guide.core.errors;

import java.util.Map;

/** The caller is not authenticated (kind: unauthorized → 401 unauthorized). */
public class UnauthorizedException extends AppException {

    public UnauthorizedException(String message) {
        this(message, null, null);
    }

    public UnauthorizedException(String message, Map<String, Object> details) {
        this(message, details, null);
    }

    public UnauthorizedException(String message, Map<String, Object> details, Throwable cause) {
        super(AppException.toFailure(FailureKind.UNAUTHORIZED, message, details), cause);
    }

    UnauthorizedException(Failure failure) {
        super(failure);
    }
}
