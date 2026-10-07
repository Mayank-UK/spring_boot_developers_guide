package guide.domain.obs;

import java.util.List;

/**
 * Use case: the viewer's window over the ring. Pure policy — level filter,
 * limit with a server-side ceiling (a hostile {@code ?limit=100000} gets
 * capped, not obeyed) — over the LogRing port.
 */
public final class ReadLogRing {

    /** Server-side ceiling on how many events one response will carry. */
    static final int MAX_LIMIT = 200;

    private static final int DEFAULT_LIMIT = 50;

    private final LogRing ring;

    public ReadLogRing(LogRing ring) {
        this.ring = ring;
    }

    /** Newest-first events; null/blank level means all levels; limit clamped to [1, 200]. */
    public LogRingView execute(String level, Integer limit) {
        String normalizedLevel = level == null || level.isBlank() ? null : level.trim();
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        return new LogRingView(ring.capacity(), ring.size(),
                ring.recent(normalizedLevel, effectiveLimit));
    }

    /** The response shape: the ring's bounds plus the events in the window. */
    public record LogRingView(int capacity, int size, List<LoggedEvent> events) {
    }
}
