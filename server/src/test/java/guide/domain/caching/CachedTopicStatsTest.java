package guide.domain.caching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import guide.data.caching.InMemoryCacheBackend;
import guide.data.caching.JacksonReportCodec;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The explicit cache-aside wrapper against a FAKE backend — the N+1-style
 * proof pattern: every HIT/MISS claim is asserted on the COMPUTE COUNT
 * (never a wall clock), and TTL behavior is clock ARITHMETIC (advance a
 * mutable clock past the deadline, observe the miss — no sleeping). Also
 * pins the key namespace ("stats:" — the reason the annotation path can
 * never serve this path's entries) and negative caching (the empty answer
 * is cached like any other).
 */
class CachedTopicStatsTest {

    /** A controllable clock: now is data, expiry is arithmetic. */
    private static final class MutableClock extends AtomicLong {
        MutableClock() {
            super(0);
        }

        void advanceSeconds(long seconds) {
            addAndGet(seconds * 1_000);
        }
    }

    /** The compute seam with a counter — the ground truth every assertion diffs. */
    private static final class CountingStats implements TopicStats {
        private final AtomicLong computes = new AtomicLong();
        private TopicStatsReport next =
                new TopicStatsReport("rest", 3, 90, 10, 50, 30.0, "the longest title");

        @Override
        public TopicStatsReport compute(String topicId) {
            computes.incrementAndGet();
            return next;
        }

        @Override
        public long computeCount() {
            return computes.get();
        }
    }

    private final MutableClock clock = new MutableClock();
    private final InMemoryCacheBackend backend = new InMemoryCacheBackend(clock::get);
    private final CountingStats slow = new CountingStats();
    private final CachedTopicStats cached = new CachedTopicStats(backend, slow,
            new JacksonReportCodec(JsonMapper.builder().build()));

    @Test
    void missThenHitThenEvictThenMissIsProvenByComputeCount() {
        assertEquals(CacheVerdict.MISS, cached.stats("rest").cache());
        assertEquals(1, slow.computeCount());

        assertEquals(CacheVerdict.HIT, cached.stats("rest").cache());
        assertEquals(1, slow.computeCount(), "a HIT must not compute — the count is the proof");

        cached.evict("rest");
        assertEquals(CacheVerdict.MISS, cached.stats("rest").cache());
        assertEquals(2, slow.computeCount(), "after eviction the next read recomputes");
    }

    @Test
    void manyReadsCostOneComputeUntilTheTtlExpires() {
        for (int read = 1; read <= 10; read++) {
            cached.stats("rest");
        }
        assertEquals(1, slow.computeCount(), "ten reads, one compute — the N+1-shaped proof");

        clock.advanceSeconds(61);
        assertEquals(CacheVerdict.MISS, cached.stats("rest").cache(),
                "past the 60 s TTL the entry is lazily expired — a miss is a miss");
        assertEquals(2, slow.computeCount());
    }

    @Test
    void topicsAreCachedUnderTheirOwnKeys() {
        cached.stats("rest");
        cached.stats("http");
        assertEquals(2, slow.computeCount());

        assertEquals(CacheVerdict.HIT, cached.stats("rest").cache());
        assertEquals(CacheVerdict.HIT, cached.stats("http").cache());
        assertEquals(2, slow.computeCount());

        assertEquals("stats:rest", cached.stats("rest").key());
        assertEquals("stats:http", cached.stats("http").key());
    }

    @Test
    void theEmptyAnswerIsCachedTooNegativeCaching() {
        slow.next = TopicStatsReport.empty("ghost");
        StatsOutcome first = cached.stats("ghost");
        assertEquals(CacheVerdict.MISS, first.cache());
        assertEquals(0, first.result().postCount());

        assertEquals(CacheVerdict.HIT, cached.stats("ghost").cache());
        assertEquals(1, slow.computeCount(), "the zero-post answer is served from the cache");
    }
}
