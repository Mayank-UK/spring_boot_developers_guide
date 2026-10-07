package guide.data.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.jobs.JobRecord;
import guide.domain.jobs.JobType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The queue's atomicity under contention — the property the port's
 * claimNextDue promises and a SQL adapter would buy with a row lock.
 * Competing workers must partition the work: every job claimed by exactly
 * one caller, no enqueue lost, eviction shedding history before it ever
 * sheds runnable work.
 */
class InMemoryJobQueueTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void competingWorkersClaimEachJobExactlyOnce() throws Exception {
        InMemoryJobQueue queue = new InMemoryJobQueue();
        for (int i = 0; i < 40; i++) {
            queue.enqueue(newRecord("job_" + i));
        }

        int workers = 4;
        Set<String> claimed = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            for (int w = 0; w < workers; w++) {
                pool.submit(() -> {
                    start.await();
                    drainInto(queue, claimed);
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers must finish");
        }

        assertEquals(40, claimed.size(), "all work claimed, nothing twice");
        assertEquals(40, queue.snapshot().stream()
                .filter(job -> job.state() == JobRecord.JobState.RUNNING).count());
    }

    @Test
    void concurrentEnqueueAndClaimLosesNothing() throws Exception {
        InMemoryJobQueue queue = new InMemoryJobQueue();
        int producers = 8;
        int perProducer = 25;
        AtomicInteger stillToEnqueue = new AtomicInteger(producers * perProducer);

        Set<String> claimed = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(producers + 2)) {
            for (int p = 0; p < producers; p++) {
                int producer = p;
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        queue.enqueue(newRecord("job_" + producer + "_" + i));
                        stillToEnqueue.decrementAndGet(); // AFTER the enqueue: 0 ⇒ all done
                    }
                    return null;
                });
            }
            for (int w = 0; w < 2; w++) {
                pool.submit(() -> {
                    start.await();
                    while (stillToEnqueue.get() > 0) {
                        drainOnce(queue, claimed);
                    }
                    drainInto(queue, claimed); // producers finished: drain the remainder
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "the race must drain");
            assertEquals(producers * perProducer, claimed.size(),
                    "every enqueued job is claimed exactly once");
            assertEquals(producers * perProducer, queue.snapshot().size());
        }
    }

    @Test
    void evictionShedsHistoryBeforeRunnableWork() {
        InMemoryJobQueue queue = new InMemoryJobQueue();
        JobRecord survivor = newRecord("job_done");
        queue.save(survivor.succeeded(new JobRecord.Attempt(1, NOW,
                JobRecord.Attempt.Outcome.SUCCEEDED, null)));
        for (int i = 0; i < InMemoryJobQueue.MAX_RECORDS - 1; i++) {
            queue.enqueue(newRecord("job_live_" + i)); // exactly at the ceiling
        }
        assertEquals(InMemoryJobQueue.MAX_RECORDS, queue.snapshot().size());

        queue.enqueue(newRecord("job_newest")); // one over: the terminal record goes
        assertTrue(queue.snapshot().size() <= InMemoryJobQueue.MAX_RECORDS);
        assertFalse(queue.find("job_done").isPresent(),
                "finished history evicts first — its value decays fastest");
        assertTrue(queue.find("job_newest").isPresent(),
                "runnable work is never the first victim");
    }

    private static void drainInto(InMemoryJobQueue queue, Set<String> claimed) {
        while (true) {
            JobRecord job = queue.claimNextDue(NOW).orElse(null);
            if (job == null) {
                return;
            }
            assertTrue(claimed.add(job.id()), "a job was claimed twice: " + job.id());
        }
    }

    private static void drainOnce(InMemoryJobQueue queue, Set<String> claimed) {
        queue.claimNextDue(NOW).ifPresent(job -> claimed.add(job.id()));
    }

    private static JobRecord newRecord(String id) {
        return new JobRecord(id, JobType.CLEANUP_PII, "{}", JobRecord.JobState.QUEUED,
                3, 0, List.of(), null, NOW, null, null, NOW, 0);
    }
}
