package guide.core.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * InvocationLog contract: bounded (oldest evicted), oldest-first tail and
 * snapshot order, clear/size behavior, argument validation, and safe
 * concurrent recording — the aspect writes from any thread, so every access
 * must be guarded.
 */
class InvocationLogTest {

    private static InvocationRecord record(String method) {
        return new InvocationRecord(method, "()", InvocationTraceAspect.OUTCOME_OK, 42L, null);
    }

    @Test
    void staysBoundedAndEvictsTheOldestFirst() {
        InvocationLog log = new InvocationLog(3);
        log.record(record("m1"));
        log.record(record("m2"));
        log.record(record("m3"));
        log.record(record("m4"));

        assertEquals(3, log.size());
        assertEquals(List.of("m2", "m3", "m4"),
                log.snapshot().stream().map(InvocationRecord::method).toList());
    }

    @Test
    void tailReturnsTheLastRecordsOldestFirst() {
        InvocationLog log = new InvocationLog(8);
        for (int index = 1; index <= 5; index++) {
            log.record(record("m" + index));
        }

        assertEquals(List.of("m4", "m5"), log.tail(2).stream().map(InvocationRecord::method).toList());
        assertEquals(List.of("m1", "m2", "m3", "m4", "m5"),
                log.tail(99).stream().map(InvocationRecord::method).toList());
        assertEquals(0, log.tail(0).size());
    }

    @Test
    void clearDropsEverythingForAFreshDemoRun() {
        InvocationLog log = new InvocationLog(4);
        log.record(record("m1"));
        log.clear();

        assertEquals(0, log.size());
        assertEquals(List.of(), log.snapshot());
    }

    @Test
    void rejectsInvalidConstructionAndArguments() {
        assertThrows(IllegalArgumentException.class, () -> new InvocationLog(0));
        assertThrows(NullPointerException.class, () -> new InvocationLog(4).record(null));
        assertThrows(IllegalArgumentException.class, () -> new InvocationLog(4).tail(-1));
    }

    @Test
    void concurrentRecordingNeverCorruptsTheRing() throws Exception {
        InvocationLog log = new InvocationLog(64);
        int threads = 4;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> done = new java.util.ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                int id = thread;
                done.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int index = 0; index < perThread; index++) {
                        log.record(record("t" + id + "-m" + index));
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : done) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        assertEquals(64, log.size());
        List<String> methods = log.snapshot().stream().map(InvocationRecord::method).toList();
        assertTrue(methods.size() == methods.stream().distinct().count(), "no duplicated records");
    }
}
