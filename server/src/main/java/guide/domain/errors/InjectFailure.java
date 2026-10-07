package guide.domain.errors;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * Use case behind the failure-injection demo (topic 30): build the CANONICAL
 * demo failure for one {@link FailureKind} and hand it back as a Failed
 * Result — a value, exactly like every other use case seam. The demo
 * controller converts the Failed arm into the matching typed exception at
 * the HTTP edge, so what the client observes is the real advice rendering a
 * real typed exception, never a fabricated envelope.
 *
 * This class is also the single source of the catalog's example messages
 * (see {@link ReadFailureCatalog}): what the catalog PROMISES for a kind is
 * literally what injecting that kind RENDERS, because both read the same
 * descriptors below.
 */
public final class InjectFailure {

    /** One canonical demo failure per kind: the message (and optional details) the demo renders. */
    public record Descriptor(String whenItFires, String message, Map<String, Object> details) {
    }

    /** Returns a Failed Result carrying the canonical failure of the requested kind. */
    public Result<Void> execute(FailureKind kind, boolean includeDetails) {
        return Result.failed(failureOf(kind, includeDetails));
    }

    /** The canonical failure for a kind — shared with the catalog so demo and docs cannot drift. */
    public Failure failureOf(FailureKind kind, boolean includeDetails) {
        Descriptor descriptor = descriptorFor(kind);
        Map<String, Object> details = includeDetails ? descriptor.details() : null;
        return Failure.of(kind, descriptor.message(), details);
    }

    /** When this kind fires, in one sentence — the catalog's third column. */
    public Descriptor descriptorFor(FailureKind kind) {
        return switch (kind) {
            case VALIDATION -> new Descriptor(
                    "input failed a boundary guard or a @Valid constraint",
                    "size must be between 1 and 50",
                    Map.of("field", "size", "reason", "below the floor of 1"));
            case UNAUTHORIZED -> new Descriptor(
                    "missing or bad credentials on a protected route",
                    "this endpoint requires authentication",
                    Map.of("scheme", "Bearer"));
            case FORBIDDEN -> new Descriptor(
                    "authenticated, but the role or ownership check said no",
                    "this account may read posts but not moderate them",
                    Map.of("requiredRole", "MODERATOR"));
            case NOT_FOUND -> new Descriptor(
                    "a ROUTED lookup found nothing — unknown id, unknown topic",
                    "no post with id \"no-such-post\"",
                    Map.of("postId", "no-such-post"));
            case CONFLICT -> new Descriptor(
                    "the request lost a race — duplicate unique key, stale version",
                    "a post with slug \"first-post\" already exists",
                    Map.of("slug", "first-post"));
            case RATE_LIMITED -> new Descriptor(
                    "the caller blew through its quota (Bucket4j in the security topic)",
                    "too many requests — retry after the cool-down",
                    Map.of("retryAfterSeconds", 42));
            case UNAVAILABLE -> new Descriptor(
                    "an optional dependency is down — the typed 503, not a crash",
                    "the live posts area is unreachable (still in fake mode)",
                    Map.of("area", "posts"));
            case INTERNAL -> new Descriptor(
                    "a genuine bug — an unhandled fault, generic on the wire",
                    "an injected bug — deliberate, for this demo",
                    // INTERNAL deliberately carries NO details even when asked:
                    // the 500 envelope must not leak internals — leak discipline
                    // is part of the kind's contract, not an oversight.
                    null);
        };
    }
}
