package guide.data.obs;

import guide.domain.obs.SpanTimeline;
import guide.domain.obs.TraceSpan;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanReporter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The teaching-grade span reporter: implements the SAME extension point a
 * Zipkin or OTLP exporter would ({@link SpanReporter} — Boot's tracing
 * autoconfiguration collects every such bean into the reporter chain), but
 * keeps a bounded in-process ring instead of shipping spans anywhere. The
 * trace demo reads it back through the {@link SpanTimeline} port.
 *
 * Teaching-grade is the honest label: a real backend stores and indexes
 * spans for days and can answer "show me the slow ones"; this ring answers
 * exactly one question — "which named, timed units made up the trace that
 * just happened, and how long did each take?" Bounded like the log ring:
 * observability must never be the thing that runs out of memory.
 */
public final class RecordingSpanReporter implements SpanReporter, SpanTimeline {

    /** How many finished spans the timeline keeps before evicting the oldest. */
    static final int CAPACITY = 256;

    private final ArrayDeque<TraceSpan> ring = new ArrayDeque<>();

    @Override
    public void report(FinishedSpan span) {
        synchronized (ring) {
            ring.addLast(new TraceSpan(
                    span.getName(),
                    span.getTraceId(),
                    span.getSpanId(),
                    span.getParentId(),
                    span.getStartTimestamp().toEpochMilli(),
                    span.getDuration().toNanos() / 1_000_000.0));
            while (ring.size() > CAPACITY) {
                ring.removeFirst();
            }
        }
    }

    @Override
    public List<TraceSpan> recentFor(String traceId) {
        List<TraceSpan> matches = new ArrayList<>();
        synchronized (ring) {
            for (TraceSpan span : ring) {
                if (span.traceId().equals(traceId)) {
                    matches.add(span);
                }
            }
        }
        matches.sort(Comparator.comparingLong(TraceSpan::startMs));
        return List.copyOf(matches);
    }

    @Override
    public int size() {
        synchronized (ring) {
            return ring.size();
        }
    }

    @Override
    public void close() {
        // a reporter bean lives as long as the context; clearing on close
        // keeps repeated test contexts from inheriting stale spans
        synchronized (ring) {
            ring.clear();
        }
    }
}
