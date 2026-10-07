package guide.domain.obs;

import java.util.List;

/**
 * Port: a bounded, newest-last in-process record of what the logging system
 * emitted. The adapter of record is a Logback appender ({@code data/obs/
 * LogRingAppender}) attached in {@code logback-spring.xml} — the ring sees
 * EXACTLY what the configured appenders see, which is what makes it honest:
 * it is not a parallel logging API, it is a second destination for the same
 * events.
 */
public interface LogRing {

    /**
     * The most recent events, newest first. {@code level} filters by exact
     * level name (case-insensitive; null or blank means all levels);
     * {@code limit} caps the count (callers apply their own ceiling).
     */
    List<LoggedEvent> recent(String level, int limit);

    /** How many events the ring currently holds (the gauge behind guide.obs.ring.size). */
    int size();

    /** The fixed capacity — how many events the ring keeps before evicting the oldest. */
    int capacity();
}
