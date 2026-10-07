package guide.core.errors;

/**
 * The canonical Failure taxonomy: one enum for every error kind the app can
 * produce, and the single place mapping each kind to its HTTP status and
 * machine-readable error code. Layers below HTTP speak ONLY in Failure values;
 * nothing else in the codebase invents status codes or error codes.
 */
public enum FailureKind {
    VALIDATION(400, "validation_failed"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "not_found"),
    CONFLICT(409, "conflict"),
    RATE_LIMITED(429, "rate_limited"),
    UNAVAILABLE(503, "service_unavailable"),
    INTERNAL(500, "internal_error");

    private final int httpStatus;
    private final String errorCode;

    FailureKind(int httpStatus, String errorCode) {
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }
}
