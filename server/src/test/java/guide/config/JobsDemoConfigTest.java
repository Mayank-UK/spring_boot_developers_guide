package guide.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import guide.domain.jobs.AsyncWorkBoard;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor's knobs as BEHAVIOR, not configuration trivia: under
 * saturation (more simultaneous submissions than core + queue + max can
 * absorb) the CallerRunsPolicy executes overflow on the SUBMITTING thread
 * — observable as work done by threads whose names do not carry the pool
 * prefix. That is the degradation the topic teaches: backpressure that
 * slows the caller down instead of dropping work or throwing at it.
 */
class JobsDemoConfigTest {

    private static final String POOL_PREFIX = "jobs-demo-";

    @Test
    void saturationSpillsOntoCallerThreadsInsteadOfRejecting() throws Exception {
        ThreadPoolTaskExecutor executor = new JobsDemoConfig().jobsDemoExecutor();
        executor.initialize();
        try {
            int submissions = 16; // core 2 + queue 8 + max 4 = 14 absorbed, ≥2 spill
            Set<String> threadNames = ConcurrentHashMap.newKeySet();
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService submitters = Executors.newFixedThreadPool(submissions)) {
                List<Future<?>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < submissions; i++) {
                    futures.add(submitters.submit(() -> {
                        start.await();
                        executor.getThreadPoolExecutor().execute(() -> {
                            threadNames.add(Thread.currentThread().getName());
                            try {
                                Thread.sleep(400); // hold pool threads so the queue fills
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        });
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(15, TimeUnit.SECONDS); // caller-runs blocks its submitter — fine
                }
            }

            long onPool = threadNames.stream().filter(name -> name.startsWith(POOL_PREFIX)).count();
            long onCaller = threadNames.size() - onPool;
            assertTrue(onPool >= 1, "the pool must carry the admitted work: " + threadNames);
            assertTrue(onCaller >= 1,
                    "saturation must spill onto caller threads (CallerRunsPolicy): " + threadNames);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void theBoardRecordsTheAsyncLifecycleForTheConsole() {
        AsyncWorkBoard board = new AsyncWorkBoard();
        Instant now = Instant.parse("2026-10-06T12:00:00Z");

        AsyncWorkBoard.AsyncTicket ticket = board.open("work_1", 250, now);
        assertEquals("pending", ticket.state());
        assertEquals(1, board.tickets().size());

        board.markRunning("work_1", POOL_PREFIX + "1", now.plusMillis(10));
        board.markDone("work_1", now.plusMillis(260));

        AsyncWorkBoard.AsyncTicket done = board.find("work_1").orElseThrow();
        assertEquals("done", done.state());
        assertEquals(POOL_PREFIX + "1", done.threadName());
        assertEquals(now, done.requestedAt());
        assertEquals(now.plusMillis(260), done.finishedAt());
    }

    @Test
    void theBoardIsBoundedSoAConsoleCannotGrowForever() {
        AsyncWorkBoard board = new AsyncWorkBoard();
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);
        for (int i = 0; i < AsyncWorkBoard.MAX_TICKETS + 20; i++) {
            board.open("work_" + i, 1, clock.instant());
        }
        assertEquals(AsyncWorkBoard.MAX_TICKETS, board.tickets().size());
        assertTrue(board.find("work_0").isEmpty(), "the OLDEST tickets evict");
        assertTrue(board.find("work_" + (AsyncWorkBoard.MAX_TICKETS + 19)).isPresent());
    }
}
