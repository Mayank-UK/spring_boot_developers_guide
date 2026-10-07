package guide.data.obs;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import guide.core.logging.RequestIdFilter;
import guide.domain.obs.LogRing;
import guide.domain.obs.LoggedEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The teaching appender: a BOUNDED, THREAD-SAFE in-process ring of the
 * events the logging system emitted, so the viewer can serve recent
 * structured events without shipping them anywhere. It is attached in
 * {@code logback-spring.xml} BESIDE the JSON console appender — a second
 * destination for the same events, never a parallel logging API.
 *
 * Bounded: a fixed capacity; when full, the OLDEST event is evicted. A log
 * viewer must never be the thing that OOMs the process it observes.
 *
 * Thread-safe: {@link AppenderBase#doAppend} serializes event delivery per
 * appender, and every ring access shares the same lock, so a snapshot can
 * never observe a half-appended state — the price is negligible at the
 * rates a teaching app logs at, and the honesty (exact same events the
 * other appenders see, in order) is worth it.
 *
 * Instantiated either by Logback from the XML (the documented path) or
 * programmatically by {@code ObservabilityDemoConfig} as a fallback for
 * contexts that never processed {@code logback-spring.xml} (test slices).
 */
public final class LogRingAppender extends AppenderBase<ILoggingEvent> implements LogRing {

    /** The default capacity when the appender is constructed programmatically. */
    public static final int DEFAULT_CAPACITY = 500;

    /** The MDC key micrometer-tracing writes the current trace id under. */
    static final String TRACE_MDC_KEY = "traceId";

    private final ArrayDeque<LoggedEvent> ring = new ArrayDeque<>();

    private int capacity = DEFAULT_CAPACITY;

    /** Logback XML property: {@code <capacity>…</capacity>} (defaults to 500). */
    public void setCapacity(int capacity) {
        if (capacity < 1) {
            addWarn("capacity must be >= 1, keeping " + this.capacity);
            return;
        }
        this.capacity = capacity;
    }

    @Override
    protected void append(ILoggingEvent event) {
        synchronized (ring) {
            ring.addLast(new LoggedEvent(
                    event.getTimeStamp(),
                    event.getLevel() == null ? "null" : event.getLevel().toString(),
                    event.getLoggerName(),
                    event.getFormattedMessage(),
                    event.getMDCPropertyMap().get(RequestIdFilter.MDC_KEY),
                    event.getMDCPropertyMap().get(TRACE_MDC_KEY)));
            while (ring.size() > capacity) {
                ring.removeFirst();
            }
        }
    }

    @Override
    public List<LoggedEvent> recent(String level, int limit) {
        String wanted = level == null || level.isBlank()
                ? null
                : level.trim().toUpperCase(Locale.ROOT);
        List<LoggedEvent> matches = new ArrayList<>();
        synchronized (ring) {
            var newestFirst = ring.descendingIterator();
            while (newestFirst.hasNext() && matches.size() < Math.max(limit, 0)) {
                LoggedEvent event = newestFirst.next();
                if (wanted == null || event.level().toUpperCase(Locale.ROOT).equals(wanted)) {
                    matches.add(event);
                }
            }
        }
        return List.copyOf(matches);
    }

    @Override
    public int size() {
        synchronized (ring) {
            return ring.size();
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }
}
