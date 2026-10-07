package guide.core.errors;

import java.util.Map;

/**
 * One discriminated failure value: the kind, the human message, and optional
 * structured details (kept null when absent, so a failure describes exactly
 * what it carries). The presentation edge merges these details into the error
 * envelope under the canonical keys.
 *
 * The TS canon also carries a cause on Failure; in Java the cause travels on
 * the exception (AppException), which is the only place a cause is useful.
 */
public record Failure(FailureKind kind, String message, Map<String, Object> details) {

    public Failure {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(message, "message");
        details = details == null ? null : Map.copyOf(details);
    }

    public static Failure of(FailureKind kind, String message) {
        return new Failure(kind, message, null);
    }

    public static Failure of(FailureKind kind, String message, Map<String, Object> details) {
        return new Failure(kind, message, details);
    }
}
