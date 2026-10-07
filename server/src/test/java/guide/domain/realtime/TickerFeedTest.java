package guide.domain.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

/**
 * TickerFeed unit tests — the pause invariant first: generation requires
 * listeners, and the whole demo's honesty (the state endpoint's ticksSent
 * with activeEmitters=0 would be a bug) rests on it. The feed is driven
 * with constructed instants and constant noise — no scheduler, no sleeps,
 * no randomness.
 */
class TickerFeedTest {

    private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

    /** Constant noise in [0,1): 0.75 drifts up, 0.25 drifts down — the walk is predictable. */
    private static TickerFeed feed(DoubleSupplier noise) {
        return new TickerFeed(noise, List.of("SDG"));
    }

    @Test
    void pausesWhenNobodyListens() {
        TickerFeed feed = feed(() -> 0.75);
        feed.tick(T0);
        feed.tick(T0.plusSeconds(1));
        feed.tick(T0.plusSeconds(2));
        assertEquals(0, feed.ticksGenerated(), "no listeners → no generation at all");
        assertEquals(0, feed.listenerCount());
        assertTrue(feed.replayAfter(0).isEmpty(), "no listeners → the ring never grew");
        assertEquals(0, feed.latestSeq());
    }

    @Test
    void generatesOnlyWhileAListenerHoldsTheFeedAwake() {
        TickerFeed feed = feed(() -> 0.75);
        List<TickerFeed.Tick> received = new CopyOnWriteArrayList<>();
        Runnable unsubscribe = feed.subscribe(received::add);

        feed.tick(T0);
        feed.tick(T0.plusMillis(500));
        assertEquals(2, feed.ticksGenerated(), "a listener exists → both beats generate");
        assertEquals(2, received.size());

        unsubscribe.run();
        feed.tick(T0.plusMillis(1000));
        assertEquals(2, feed.ticksGenerated(), "last listener left → the feed pauses again");
        assertEquals(0, feed.listenerCount(), "listenerCount dropped to zero");
    }

    @Test
    void driftFollowsTheInjectedNoiseDeterministically() {
        TickerFeed up = feed(() -> 0.75);
        TickerFeed down = feed(() -> 0.25);
        List<TickerFeed.Tick> ups = new CopyOnWriteArrayList<>();
        List<TickerFeed.Tick> downs = new CopyOnWriteArrayList<>();
        up.subscribe(ups::add);
        down.subscribe(downs::add);

        up.tick(T0);
        down.tick(T0);
        assertTrue(ups.getFirst().price() > 100.0, "noise above the midpoint walks the price up");
        assertTrue(ups.getFirst().delta() > 0);
        assertTrue(downs.getFirst().price() < 100.0, "noise below the midpoint walks it down");
        assertTrue(downs.getFirst().delta() < 0);
        assertEquals(1, ups.getFirst().seq());
        assertEquals("SDG", ups.getFirst().symbol());
        assertEquals(T0, ups.getFirst().at(), "time is a parameter — the test owns the clock");
    }

    @Test
    void replayWindowIsBoundedAndHonest() {
        TickerFeed feed = feed(() -> 0.5);
        feed.subscribe(tick -> { });
        for (int step = 1; step <= TickerFeed.RING_CAPACITY + 10; step++) {
            feed.tick(T0.plusMillis(step * 500L));
        }
        List<TickerFeed.Tick> replay = feed.replayAfter(0);
        assertEquals(TickerFeed.RING_CAPACITY, replay.size(), "the ring holds at most its capacity");
        assertEquals(11, replay.getFirst().seq(), "the oldest 10 ticks were evicted, honestly");
        assertEquals(TickerFeed.RING_CAPACITY + 10, replay.getLast().seq());
        assertEquals(TickerFeed.RING_CAPACITY + 10, feed.latestSeq());
    }

    @Test
    void replayAfterTheNewestSeqIsEmptyAndAfterAMiddleSeqResumesExactly() {
        TickerFeed feed = feed(() -> 0.5);
        feed.subscribe(tick -> { });
        for (int step = 1; step <= 5; step++) {
            feed.tick(T0.plusMillis(step * 500L));
        }
        assertTrue(feed.replayAfter(5).isEmpty(), "nothing missed after the newest id");
        List<TickerFeed.Tick> resumed = feed.replayAfter(3);
        assertEquals(2, resumed.size());
        assertEquals(4, resumed.getFirst().seq());
        assertEquals(5, resumed.getLast().seq());
        assertFalse(resumed.getFirst().seq() <= 3);
    }

    @Test
    void aThrowingListenerIsDroppedAndNeverBlocksTheHealthyOnes() {
        TickerFeed feed = feed(() -> 0.5);
        List<TickerFeed.Tick> received = new CopyOnWriteArrayList<>();
        List<TickerFeed.Tick> throwCounts = new CopyOnWriteArrayList<>();
        feed.subscribe(tick -> {
            throwCounts.add(tick);                 // record, then die like a broken emitter would
            throw new IllegalStateException("consumer is gone");
        });
        feed.subscribe(received::add);             // the healthy one

        feed.tick(T0);
        feed.tick(T0.plusMillis(500));
        assertEquals(1, throwCounts.size(), "the throwing listener was dropped after its first failure");
        assertEquals(2, received.size(), "the healthy listener kept receiving");
        assertEquals(1, feed.listenerCount());
    }
}
