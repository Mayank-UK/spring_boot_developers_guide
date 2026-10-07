package guide.core.aop;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/**
 * Where the {@link InvocationTraceAspect} files its {@link InvocationRecord}s:
 * a bounded ring of the most recent invocations, safe to read and write from
 * any thread (servlet threads, async executors, virtual threads) because
 * every access synchronizes on the deque. Bounded on purpose — an
 * instrumentation buffer that grows with traffic is a memory leak wearing a
 * feature's clothes; when the ring is full the OLDEST record is evicted, so
 * the log always holds the freshest {@code capacity} invocations and nothing
 * else. The aop demos clear() before running their steps so the visible tail
 * is exactly the demonstration's own evidence.
 */
public final class InvocationLog {

    private final ArrayDeque<InvocationRecord> records;
    private final int capacity;

    public InvocationLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        this.capacity = capacity;
        this.records = new ArrayDeque<>(capacity);
    }

    /** Appends one record, evicting the oldest when the ring is full. */
    public void record(InvocationRecord record) {
        Objects.requireNonNull(record, "record");
        synchronized (records) {
            if (records.size() == capacity) {
                records.pollFirst();
            }
            records.addLast(record);
        }
    }

    /** The last {@code limit} records, oldest first; fewer if the ring is shorter. */
    public List<InvocationRecord> tail(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0: " + limit);
        }
        synchronized (records) {
            int skip = Math.max(0, records.size() - limit);
            return records.stream().skip(skip).toList();
        }
    }

    /** Copy of the whole ring, oldest first — snapshot semantics, safe to hold. */
    public List<InvocationRecord> snapshot() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    /** Drops every record. Demo/reset path; production readers never need it. */
    public void clear() {
        synchronized (records) {
            records.clear();
        }
    }

    /** Current number of held records (never above the capacity). */
    public int size() {
        synchronized (records) {
            return records.size();
        }
    }
}
