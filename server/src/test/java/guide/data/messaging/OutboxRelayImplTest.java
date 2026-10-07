package guide.data.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.messaging.DomainEvent;
import guide.domain.messaging.EventStream;
import guide.domain.messaging.OutboxRelay;
import guide.domain.messaging.OutboxStore;
import guide.domain.messaging.OutboxStore.OutboxEntry;
import guide.domain.messaging.TeachingEventPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The relay's two guarantees, proven deterministically WITHOUT Redis or
 * PostgreSQL (the ports make the fakes trivial — the port/adapter payoff,
 * again):
 *
 * - SINGLE-FLIGHT: a second relayNextBatch while a batch is open takes the
 *   skip path — the fake store BLOCKS the first batch on a latch, so the
 *   interleaving is forced, not raced; the second call must answer
 *   skippedInFlight without touching the store at all.
 * - DEAD-LETTER AFTER N: a claimed row past the attempt threshold never
 *   reaches the stream — it goes to the dead-letter stream and is marked
 *   processed; a row under the threshold publishes and is marked.
 */
class OutboxRelayImplTest {

    private static Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    /** The fake store: scriptable claims, a latch to hold a batch open. */
    static final class FakeStore implements OutboxStore {
        final List<OutboxEntry> nextBatch = new ArrayList<>();
        final List<Long> marked = new ArrayList<>();
        final AtomicInteger claims = new AtomicInteger();
        CountDownLatch holdBatchOpen;

        @Override
        public void append(DomainEvent event) {
        }

        @Override
        public List<OutboxEntry> claimPending(int limit) {
            claims.incrementAndGet();
            if (holdBatchOpen != null) {
                try {
                    holdBatchOpen.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return List.copyOf(nextBatch);
        }

        @Override
        public void markProcessed(List<Long> ids) {
            marked.addAll(ids);
        }

        @Override
        public OutboxStats stats() {
            return new OutboxStats(0, 0, 0);
        }

        @Override
        public List<OutboxEntry> recent(int limit) {
            return List.of();
        }
    }

    /** The fake stream: counts publishes, records dead letters. */
    static final class FakeStream implements EventStream {
        final List<DomainEvent> published = new ArrayList<>();
        final List<String> deadLettered = new ArrayList<>();

        @Override
        public TeachingEventPublisher.PublishedEvent append(DomainEvent event) {
            published.add(event);
            return new TeachingEventPublisher.PublishedEvent("s-" + published.size(), "stream",
                    event);
        }

        @Override
        public String appendDead(DomainEvent event, String reason) {
            deadLettered.add(event.eventId());
            return "dead-" + deadLettered.size();
        }

        @Override
        public void ensureGroup() {
        }

        @Override
        public List<Delivery> readGroup(String consumer, int count, boolean replayPending) {
            return List.of();
        }

        @Override
        public long ack(List<String> ids) {
            return 0;
        }

        @Override
        public PendingView pending() {
            return new PendingView(0, null, null, Map.of(), List.of());
        }

        @Override
        public ClaimedEntries claimIdle(String consumer, java.time.Duration minIdle) {
            return new ClaimedEntries(List.of(), List.of(), consumer, minIdle.toMillis());
        }

        @Override
        public List<DeadLetter> deadLetters(int limit) {
            return List.of();
        }
    }

    private static DomainEvent event(String id) {
        return new DomainEvent(id, "post", "p-1", "post.published", Map.of("postId", "p-1"),
                Map.of("eventId", id), NOW);
    }

    private static OutboxEntry entry(long id, int attempts) {
        return new OutboxEntry(id, event("evt-" + id), attempts, NOW, null);
    }

    @Test
    void aSecondBatchWhileOneIsOpenTakesTheSkipPathWithoutTouchingTheStore() throws Exception {
        FakeStore store = new FakeStore();
        FakeStream stream = new FakeStream();
        store.nextBatch.add(entry(1, 1));
        store.holdBatchOpen = new CountDownLatch(1);
        OutboxRelay relay = new OutboxRelayImpl(store, stream, () -> true);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OutboxRelay.RelayReport> first = pool.submit(
                    () -> relay.relayNextBatch(NOW, 10));
            // Let the first batch reach (and block inside) the store claim.
            Thread.sleep(150);
            Future<OutboxRelay.RelayReport> second = pool.submit(
                    () -> relay.relayNextBatch(NOW, 10));
            OutboxRelay.RelayReport rejected = second.get(2, TimeUnit.SECONDS);
            assertTrue(rejected.skippedInFlight(),
                    "the concurrent batch must be rejected by the single-flight guard");
            assertEquals(0, rejected.claimed());
            assertEquals(1, store.claims.get(),
                    "the rejected call must never have touched the store");
            store.holdBatchOpen.countDown();
            OutboxRelay.RelayReport winner = first.get(2, TimeUnit.SECONDS);
            assertFalse(winner.skippedInFlight());
            assertEquals(1, winner.published());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rowsPastTheAttemptThresholdDeadLetterInsteadOfPublishing() {
        FakeStore store = new FakeStore();
        FakeStream stream = new FakeStream();
        store.nextBatch.add(entry(1, OutboxRelay.MAX_ATTEMPTS)); // at threshold: still publishes
        store.nextBatch.add(entry(2, OutboxRelay.MAX_ATTEMPTS + 1)); // past it: poison
        OutboxRelay relay = new OutboxRelayImpl(store, stream, () -> true);

        OutboxRelay.RelayReport report = relay.relayNextBatch(NOW, 10);

        assertEquals(2, report.claimed());
        assertEquals(1, report.published(), "the at-threshold row still gets its publish");
        assertEquals(1, report.deadLettered(), "the past-threshold row is dead-lettered");
        assertEquals("evt-1", stream.published.getFirst().eventId());
        assertEquals("evt-2", stream.deadLettered.getFirst());
        assertEquals(List.of(1L, 2L), store.marked, "both rows leave the queue");
    }

    @Test
    void canRunFollowsTheWiredGate() {
        OutboxRelayImpl on = new OutboxRelayImpl(new FakeStore(), new FakeStream(), () -> true);
        OutboxRelayImpl off = new OutboxRelayImpl(new FakeStore(), new FakeStream(), () -> false);
        assertTrue(on.canRun());
        assertFalse(off.canRun());
    }
}
