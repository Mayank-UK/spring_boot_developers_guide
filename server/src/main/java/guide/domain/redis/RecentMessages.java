package guide.domain.redis;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * The pub/sub demo's bounded ring of recently received messages. The
 * subscription listener (live mode) and the fake's short-circuit publish
 * (fake mode) both append here; the demo's /recent endpoint snapshots it.
 *
 * Bounded and thread-safe on purpose: a subscription can out-deliver any
 * reader (a channel under load is a firehose), so the ring keeps the newest
 * {@code capacity} messages and silently drops the rest — the demo shows a
 * WINDOW, never an unbounded queue. The monitor is a plain synchronized:
 * append and snapshot are both O(capacity) at worst, contention is a demo
 * audience, not a workload.
 */
public final class RecentMessages {

    /** One delivered message: the full (namespaced) channel it arrived on, the body, and when. */
    public record RecentMessage(String channel, String message, Instant receivedAt) {
    }

    public static final int DEFAULT_CAPACITY = 20;

    private final int capacity;
    private final Deque<RecentMessage> ring = new ArrayDeque<>();

    public RecentMessages() {
        this(DEFAULT_CAPACITY);
    }

    public RecentMessages(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        this.capacity = capacity;
    }

    /** Appends newest-last, evicting the oldest beyond capacity. */
    public synchronized void append(String channel, String message) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(message, "message");
        ring.addLast(new RecentMessage(channel, message, Instant.now()));
        while (ring.size() > capacity) {
            ring.removeFirst();
        }
    }

    /** A copy of the window, NEWEST FIRST (what a "recent" list should read like). */
    public synchronized List<RecentMessage> snapshot() {
        List<RecentMessage> newestFirst = new ArrayList<>(ring.size());
        var iterator = ring.descendingIterator();
        while (iterator.hasNext()) {
            newestFirst.add(iterator.next());
        }
        return List.copyOf(newestFirst);
    }

    /** Test/demo reset — the deterministic empty window. */
    public synchronized void clear() {
        ring.clear();
    }
}
