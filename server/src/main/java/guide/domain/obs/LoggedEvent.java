package guide.domain.obs;

/**
 * One structured log event as the viewer serves it: the fields a JSON log
 * line carries (or a console line renders), already parsed — no regex
 * archaeology. {@code requestId} and {@code traceId} are the two join keys
 * the whole topic is about: null exactly when the event was logged outside
 * any request or trace scope.
 */
public record LoggedEvent(
        long timestampMs,
        String level,
        String logger,
        String message,
        String requestId,
        String traceId) {
}
