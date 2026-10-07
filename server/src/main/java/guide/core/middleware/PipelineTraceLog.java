package guide.core.middleware;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded ring of the most recently COMPLETED request traces — the "previous
 * request" half of the middleware-pipeline demo. Every access synchronizes
 * on the deque because servlet threads, virtual threads, and async hand-offs
 * all file here. Bounded on purpose: a per-request evidence buffer that grows
 * with traffic is a memory leak wearing a feature's clothes; when the ring is
 * full the OLDEST trace is evicted, so the log holds the freshest
 * {@code capacity} completed requests and nothing else.
 */
public final class PipelineTraceLog {

    private final ArrayDeque<CompletedTrace> traces;
    private final int capacity;

    public PipelineTraceLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        this.capacity = capacity;
        this.traces = new ArrayDeque<>(capacity);
    }

    /** Appends one completed trace, evicting the oldest when the ring is full. */
    public void record(CompletedTrace trace) {
        Objects.requireNonNull(trace, "trace");
        synchronized (traces) {
            if (traces.size() == capacity) {
                traces.pollFirst();
            }
            traces.addLast(trace);
        }
    }

    /** The most recent completed trace, or null when the ring is empty. */
    public CompletedTrace latest() {
        synchronized (traces) {
            return traces.peekLast();
        }
    }

    /** The last {@code limit} traces, oldest first; fewer if the ring is shorter. */
    public List<CompletedTrace> tail(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0: " + limit);
        }
        synchronized (traces) {
            int skip = Math.max(0, traces.size() - limit);
            return traces.stream().skip(skip).toList();
        }
    }

    /** Current number of held traces (never above the capacity). */
    public int size() {
        synchronized (traces) {
            return traces.size();
        }
    }
}
