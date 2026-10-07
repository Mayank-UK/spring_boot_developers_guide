package guide.core.errors;

import java.util.Map;

/**
 * Typed runtime exceptions carrying a {@link Failure}. Use cases return
 * Result&lt;_, Failure&gt;; the presentation edge converts a Failed result into
 * the matching exception ({@link #of}) so the global handler advice can render
 * the one error envelope. Guards and demo endpoints may also throw these
 * directly.
 */
public class AppException extends RuntimeException {

    private final Failure failure;

    public AppException(Failure failure) {
        super(failure.message());
        this.failure = failure;
    }

    public AppException(Failure failure, Throwable cause) {
        super(failure.message(), cause);
        this.failure = failure;
    }

    public final Failure failure() {
        return failure;
    }

    public final FailureKind kind() {
        return failure.kind();
    }

    public final int statusCode() {
        return failure.kind().httpStatus();
    }

    public final String code() {
        return failure.kind().errorCode();
    }

    /** Converts any Failure into the exception class matching its kind, for throwing at the HTTP edge. */
    public static AppException of(Failure failure) {
        return switch (failure.kind()) {
            case VALIDATION -> new ValidationException(failure);
            case UNAUTHORIZED -> new UnauthorizedException(failure);
            case FORBIDDEN -> new ForbiddenException(failure);
            case NOT_FOUND -> new NotFoundException(failure);
            case CONFLICT -> new ConflictException(failure);
            case RATE_LIMITED -> new RateLimitException(failure);
            case UNAVAILABLE -> new UnavailableException(failure);
            case INTERNAL -> new InternalException(failure);
        };
    }

    /** Convenience for subclasses: kind + message + optional details, as a Failure. */
    static Failure toFailure(FailureKind kind, String message, Map<String, Object> details) {
        return Failure.of(kind, message, details);
    }
}
