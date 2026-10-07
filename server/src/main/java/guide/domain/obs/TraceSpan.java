package guide.domain.obs;

/**
 * One finished span rendered for the trace demo: the name the tracer gave
 * it, its identity inside the trace (span id, parent span id — null for the
 * trace's root), and its timing. {@code durationMs} is the wall time the
 * named unit was open, exactly what a tracing waterfall draws.
 */
public record TraceSpan(
        String name,
        String traceId,
        String spanId,
        String parentId,
        long startMs,
        double durationMs) {
}
