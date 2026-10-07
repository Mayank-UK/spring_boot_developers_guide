package guide.domain.obs;

import java.util.List;

/**
 * Port: the finished spans the tracer reported, kept in-process for the
 * teaching surface. The adapter of record is the tracing {@code SpanReporter}
 * ({@code data/obs/RecordingSpanReporter}) — the SAME extension point a
 * Zipkin exporter would implement, pointed at memory instead of a collector.
 * Teaching-grade by design: a real deployment ships spans to a backend that
 * stores and indexes them; this ring answers one question the tour needs —
 * "which named, timed units made up THIS trace, and how long did each take?"
 */
public interface SpanTimeline {

    /** Finished spans carrying the given trace id, ordered by start time. */
    List<TraceSpan> recentFor(String traceId);

    /** How many finished spans the timeline currently holds. */
    int size();
}
