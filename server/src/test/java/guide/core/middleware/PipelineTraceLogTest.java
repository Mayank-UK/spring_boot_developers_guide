package guide.core.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * PipelineTraceLog contract: bounded (oldest evicted), latest() = most recent
 * completed trace, oldest-first tail, argument validation, and safe
 * concurrent filing — the outermost filter writes from whatever thread the
 * request rode in on, so every access must be guarded.
 */
class PipelineTraceLogTest {

    private static CompletedTrace trace(String path) {
        return new CompletedTrace("req-" + path, "GET", path, 200,
                List.of(MiddlewareOrderingFilter.BEFORE_MARKER, MiddlewareOrderingFilter.AFTER_MARKER));
    }

    @Test
    void staysBoundedAndEvictsTheOldestFirst() {
        PipelineTraceLog log = new PipelineTraceLog(3);
        log.record(trace("/one"));
        log.record(trace("/two"));
        log.record(trace("/three"));
        log.record(trace("/four"));

        assertEquals(3, log.size());
        assertEquals(List.of("/two", "/three", "/four"),
                log.tail(9).stream().map(CompletedTrace::path).toList());
    }

    @Test
    void latestIsTheMostRecentCompletedTrace() {
        PipelineTraceLog log = new PipelineTraceLog(4);
        assertNull(log.latest());

        log.record(trace("/first"));
        log.record(trace("/second"));

        assertEquals("/second", log.latest().path());
    }

    @Test
    void markersAreDefensivelyCopied() {
        PipelineTraceLog log = new PipelineTraceLog(2);
        List<String> markers = new java.util.ArrayList<>(List.of("a", "b"));
        log.record(new CompletedTrace("req-1", "GET", "/x", 200, markers));
        markers.add("mutated-after-the-fact");

        assertEquals(List.of("a", "b"), log.latest().markers());
    }

    @Test
    void rejectsInvalidConstructionAndArguments() {
        assertThrows(IllegalArgumentException.class, () -> new PipelineTraceLog(0));
        assertThrows(NullPointerException.class, () -> new PipelineTraceLog(2).record(null));
        assertThrows(IllegalArgumentException.class, () -> new PipelineTraceLog(2).tail(-1));
    }

    @Test
    void concurrentFilingNeverLosesTheBoundedInvariant() throws Exception {
        PipelineTraceLog log = new PipelineTraceLog(8);
        int writers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> done = new java.util.ArrayList<>();
            for (int index = 0; index < writers; index++) {
                String path = "/race/" + index;
                done.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < 50; round++) {
                        log.record(trace(path));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : done) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(log.size() <= 8, "ring must never exceed capacity, saw " + log.size());
        assertEquals(log.size(), log.tail(8).size());
    }
}
