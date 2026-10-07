package guide.data.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.mongo.VisitDocument;
import guide.domain.mongo.VisitDraft;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fake's contract: deterministic seed, the aggregate oracle
 * (VisitAggregate) answering the port's questions, the bounded window
 * evicting oldest-first, TTL-shaped expiry as a testable method, and
 * thread safety under concurrent writers (one monitor for mutations,
 * snapshots for readers).
 */
class InMemoryVisitStoreTest {

    private InMemoryVisitStore store;

    @BeforeEach
    void reset() {
        store = new InMemoryVisitStore();
    }

    @Test
    void theSeedIsDeterministicAndAggregatesReadItExactly() {
        assertEquals(24, store.countAll());
        assertEquals(10, store.countByTopic("jdbc-sql"));
        assertEquals(7, store.countByTopic("spring-core"));
        assertEquals(List.of(
                        new guide.domain.mongo.TopicTally("jdbc-sql", 10),
                        new guide.domain.mongo.TopicTally("spring-core", 7),
                        new guide.domain.mongo.TopicTally("validation", 3),
                        new guide.domain.mongo.TopicTally("aop", 2),
                        new guide.domain.mongo.TopicTally("routing", 2)),
                store.topTopics(5),
                "the routing/aop tie at 2 breaks on topic id ascending");
        assertEquals(List.of(
                        new guide.domain.mongo.LocaleTally("de-DE", 4),
                        new guide.domain.mongo.LocaleTally("en", 14),
                        new guide.domain.mongo.LocaleTally("fr", 3),
                        new guide.domain.mongo.LocaleTally("ja", 3)),
                store.byLocaleHistogram(),
                "buckets locale-ascending, counts summed");

        store.reset();
        assertEquals(24, store.countAll(), "reset restores the seed exactly");
    }

    @Test
    void theWindowIsBoundedOldestFirstEviction() {
        InMemoryVisitStore tiny = new InMemoryVisitStore(List.of());
        for (int i = 0; i < InMemoryVisitStore.CAPACITY + 40; i++) {
            tiny.record(new VisitDraft("aop", "/topics/aop", "tok-" + i, 1_000, "en"));
        }
        assertEquals(InMemoryVisitStore.CAPACITY, tiny.countAll(),
                "the fake is a window, not a leak");
        assertEquals(InMemoryVisitStore.CAPACITY, tiny.recent(InMemoryVisitStore.CAPACITY).size());
        assertTrue(tiny.recent(1).getFirst().occurredAt()
                        .isAfter(Instant.parse("2026-10-01T00:00:00Z")),
                "the newest survived; the oldest were evicted");
    }

    @Test
    void expiryDropsOldVisitsLikeTheTtlIndexWould() {
        int removed = store.expireOlderThan(Instant.parse("2026-09-25T00:00:00Z"));
        assertEquals(15, removed, "every seed visit before the cutoff, none after");
        assertEquals(9, store.countAll());
        assertEquals(1, store.countByTopic("aop"),
                "only aop's old visit (v10) expired; its 09-25 visit survives");
        assertEquals(4, store.countByTopic("jdbc-sql"));
    }

    @Test
    void concurrentRecordingNeverLosesItsInvariants() throws Exception {
        InMemoryVisitStore concurrent = new InMemoryVisitStore(List.of());
        int writers = 8;
        int each = 60; // 480 total, under the 500 window so nothing evicts
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int w = 0; w < writers; w++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < each; i++) {
                    VisitDocument stored = concurrent.record(new VisitDraft(
                            "jdbc-sql", "/topics/jdbc-sql", "tok-" + i, 5_000, "en"));
                    if (stored.visitorHash().length() != 64) {
                        return false;
                    }
                }
                return true;
            }));
        }
        start.countDown();
        for (Future<Boolean> future : futures) {
            assertEquals(Boolean.TRUE, future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(writers * each, concurrent.countAll(),
                "every concurrent record landed exactly once");
        assertEquals(writers * each, concurrent.countByTopic("jdbc-sql"));
    }
}
