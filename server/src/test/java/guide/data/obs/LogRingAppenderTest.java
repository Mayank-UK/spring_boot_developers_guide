package guide.data.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import guide.domain.obs.LoggedEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The ring appender's own contract, without Spring: bounded (oldest evicted
 * at capacity), newest-first reads, exact level filtering, MDC fields
 * carried through — and thread-safety under concurrent appends proven by
 * hammering it from many threads and losing zero events.
 */
class LogRingAppenderTest {

    private static LoggingEvent event(Level level, String message, Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setLoggerName("guide.test");
        event.setMessage(message);
        event.setMDCPropertyMap(mdc);
        return event;
    }

    @Test
    void keepsOnlyTheNewestEventsAtCapacity() {
        LogRingAppender ring = new LogRingAppender();
        ring.setCapacity(3);
        ring.start();
        for (int i = 1; i <= 5; i++) {
            ring.doAppend(event(Level.INFO, "event-" + i, Map.of()));
        }
        assertEquals(3, ring.size(), "capacity is the ceiling — oldest evicted");
        assertEquals(List.of("event-5", "event-4", "event-3"),
                ring.recent(null, 10).stream().map(LoggedEvent::message).toList(),
                "newest first, oldest gone");
    }

    @Test
    void filtersByExactLevelCaseInsensitivelyAndCarriesMdcFields() {
        LogRingAppender ring = new LogRingAppender();
        ring.start();
        ring.doAppend(event(Level.INFO, "beat", Map.of("requestId", "r-1", "traceId", "t-1")));
        ring.doAppend(event(Level.WARN, "wobble", Map.of("requestId", "r-2")));
        ring.doAppend(event(Level.ERROR, "boom", Map.of()));

        assertEquals(1, ring.recent("warn", 10).size(), "exact level match, case-insensitive");
        assertEquals("wobble", ring.recent("WARN", 10).getFirst().message());
        assertEquals("r-2", ring.recent("WARN", 10).getFirst().requestId());
        assertEquals(3, ring.recent(null, 10).size(), "null level means all levels");

        LoggedEvent info = ring.recent("info", 1).getFirst();
        assertEquals("r-1", info.requestId(), "request id travels as a field");
        assertEquals("t-1", info.traceId(), "trace id travels as a field");
        assertNull(ring.recent("error", 1).getFirst().requestId(),
                "absent MDC keys stay null, never invented");
    }

    @Test
    void concurrentAppendsLoseNoEvents() throws Exception {
        LogRingAppender ring = new LogRingAppender();
        ring.setCapacity(10_000);
        ring.start();
        int threads = 8;
        int perThread = 500;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean(false);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        for (int i = 0; i < perThread; i++) {
                            ring.doAppend(event(Level.INFO, "m", Map.of("requestId", "r")));
                        }
                    } catch (InterruptedException e) {
                        failed.set(true);
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await();
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "all appenders finish");
        }
        assertTrue(!failed.get(), "no thread was interrupted");
        assertEquals(threads * perThread, ring.size(),
                "every concurrent append landed — the ring is thread-safe");
        assertEquals(threads * perThread, ring.recent(null, threads * perThread).size(),
                "the snapshot saw a consistent state, not a torn one");
    }
}
