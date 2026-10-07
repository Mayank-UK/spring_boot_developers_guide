package guide.domain.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * DeliveryEngine tests — the retry/backoff/DLQ curriculum, driven ENTIRELY by
 * a fake clock and a scripted port: no Thread.sleep, no sockets, no
 * scheduler. Time is a parameter of attemptNextDue(now), so the whole
 * five-failure descent into the dead-letter queue is a table of instants:
 * t0 attempt 1, +1s attempt 2, +2s attempt 3, +4s attempt 4, +8s attempt 5
 * (capped backoff) → dead-lettered, terminal, never touched again.
 */
class DeliveryEngineTest {

    private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

    /** The test's clock — advanced by hand; the engine reads it only in fire(). */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** Port script: fails the first N attempts with HTTP 500, then delivers 200. */
    private static final class ScriptedPort implements HttpDeliveryPort {
        private final int failuresBeforeSuccess;
        private int calls;

        ScriptedPort(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public AttemptOutcome deliver(WebhookSubscription subscription, String eventType, String body) {
            calls++;
            boolean delivered = calls > failuresBeforeSuccess;
            return new AttemptOutcome(delivered, delivered ? 200 : 500,
                    "t=" + calls + ",v1=cafe" + calls);
        }
    }

    /** Minimal store fake: last-write-wins maps, no bounds — the engine's view of persistence. */
    private static final class FakeStore implements WebhookStore {
        private final java.util.LinkedHashMap<String, WebhookSubscription> subs = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<String, WebhookDelivery> deliveries = new java.util.LinkedHashMap<>();
        private final java.util.ArrayList<WebhookReceipt> receipts = new java.util.ArrayList<>();

        @Override
        public void saveSubscription(WebhookSubscription subscription) {
            subs.put(subscription.id(), subscription);
        }

        @Override
        public java.util.Optional<WebhookSubscription> findSubscription(String id) {
            return java.util.Optional.ofNullable(subs.get(id));
        }

        @Override
        public List<WebhookSubscription> subscriptions() {
            return List.copyOf(subs.values());
        }

        @Override
        public void saveDelivery(WebhookDelivery delivery) {
            deliveries.put(delivery.id(), delivery);
        }

        @Override
        public List<WebhookDelivery> deliveries(String subscriptionId) {
            return deliveries.values().stream()
                    .filter(delivery -> delivery.subscriptionId().equals(subscriptionId))
                    .toList();
        }

        @Override
        public List<WebhookDelivery> dueDeliveries(Instant now, int limit) {
            return deliveries.values().stream()
                    .filter(delivery -> delivery.state() == WebhookDelivery.DeliveryState.PENDING
                            && !delivery.nextAttemptAt().isAfter(now))
                    .sorted(java.util.Comparator.comparing(WebhookDelivery::nextAttemptAt))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void saveReceipt(WebhookReceipt receipt) {
            receipts.add(receipt);
        }

        @Override
        public List<WebhookReceipt> receipts() {
            return List.copyOf(receipts);
        }

        @Override
        public void scriptResponse(String eventId, String respondBehavior) {
        }

        @Override
        public java.util.Optional<String> scriptedResponse(String eventId) {
            return java.util.Optional.empty();
        }
    }

    private final WebhookStore store = new FakeStore();
    private final MutableClock clock = new MutableClock(T0);

    /** The subscription, registered in the store (the engine resolves it per attempt). */
    private WebhookSubscription subscription() {
        WebhookSubscription subscription = new WebhookSubscription("sub_test",
                "http://localhost:8080/api/demo/webhooks/receiver",
                "whsec_engine_test_secret_000000", List.of("invoice.paid"), true, T0);
        store.saveSubscription(subscription);
        return subscription;
    }

    @Test
    void firstAttemptSuccessDeliversImmediately() {
        DeliveryEngine engine = new DeliveryEngine(store, new ScriptedPort(0), clock, () -> 0.0);
        WebhookDelivery fired = engine.fire(subscription(), "evt_ok", "invoice.paid", "{}");
        clock.set(T0);

        List<WebhookDelivery> touched = engine.attemptNextDue(T0);
        assertEquals(1, touched.size());
        WebhookDelivery after = store.deliveries("sub_test").getFirst();
        assertEquals(WebhookDelivery.DeliveryState.DELIVERED, after.state());
        assertEquals(1, after.attempts().size());
        assertEquals(WebhookDelivery.Attempt.Outcome.DELIVERED, after.attempts().getFirst().outcome());
        assertEquals(200, after.attempts().getFirst().httpStatus());
        assertNull(after.nextAttemptAt());
        assertEquals(fired.id(), after.id());
    }

    @Test
    void exponentialBackoffScheduleIsExactWithZeroJitter() {
        DeliveryEngine engine = new DeliveryEngine(store, new ScriptedPort(Integer.MAX_VALUE),
                clock, () -> 0.0);
        engine.fire(subscription(), "evt_fail", "invoice.paid", "{}");

        // attempt 1 at t0, then +1s, +2s, +4s, +8s (capped) — the full descent
        Instant[] attemptTimes = {T0, T0.plusSeconds(1), T0.plusSeconds(3),
                T0.plusSeconds(7), T0.plusSeconds(15)};
        for (int i = 0; i < attemptTimes.length; i++) {
            List<WebhookDelivery> touched = engine.attemptNextDue(attemptTimes[i]);
            assertEquals(1, touched.size(), "attempt " + (i + 1) + " should run at " + attemptTimes[i]);
            WebhookDelivery after = touched.getFirst();
            assertEquals(i + 1, after.attempts().size());
            if (i < 4) {
                assertEquals(WebhookDelivery.DeliveryState.PENDING, after.state());
                assertEquals(attemptTimes[i + 1], after.nextAttemptAt(),
                        "backoff after failure " + (i + 1));
                // not due one nanosecond early
                assertEquals(0, engine.attemptNextDue(attemptTimes[i + 1].minusNanos(1)).size());
            } else {
                assertEquals(WebhookDelivery.DeliveryState.DEAD_LETTERED, after.state());
                assertEquals(WebhookDelivery.Attempt.Outcome.DEAD_LETTERED,
                        after.attempts().getLast().outcome());
                assertNull(after.nextAttemptAt());
            }
        }

        // terminal means terminal: plenty of time passes, nothing moves
        assertEquals(0, engine.attemptNextDue(T0.plusSeconds(3600)).size());
        assertEquals(5, store.deliveries("sub_test").getFirst().attempts().size());
    }

    @Test
    void failedAttemptsRecordHttpStatusAndSignature() {
        DeliveryEngine engine = new DeliveryEngine(store, new ScriptedPort(Integer.MAX_VALUE),
                clock, () -> 0.0);
        engine.fire(subscription(), "evt_meta", "invoice.paid", "{}");
        engine.attemptNextDue(T0);

        WebhookDelivery.Attempt first = store.deliveries("sub_test").getFirst().attempts().getFirst();
        assertEquals(WebhookDelivery.Attempt.Outcome.FAILED, first.outcome());
        assertEquals(500, first.httpStatus());
        assertEquals("t=1,v1=cafe1", first.signatureHeader());
    }

    @Test
    void successOnThirdAttemptEndsTheRetries() {
        DeliveryEngine engine = new DeliveryEngine(store, new ScriptedPort(2), clock, () -> 0.0);
        engine.fire(subscription(), "evt_third", "invoice.paid", "{}");

        engine.attemptNextDue(T0);                                   // fail 1 → due t0+1s
        engine.attemptNextDue(T0.plusSeconds(1));                    // fail 2 → due t0+3s
        List<WebhookDelivery> third = engine.attemptNextDue(T0.plusSeconds(3)); // success
        assertEquals(WebhookDelivery.DeliveryState.DELIVERED, third.getFirst().state());
        assertEquals(3, third.getFirst().attempts().size());
        assertNull(third.getFirst().nextAttemptAt());
        assertEquals(0, engine.attemptNextDue(T0.plusSeconds(100)).size());
    }

    @Test
    void backoffCurveIsOneTwoFourThenCapped() {
        assertEquals(Duration.ofSeconds(1), DeliveryEngine.backoffAfter(1));
        assertEquals(Duration.ofSeconds(2), DeliveryEngine.backoffAfter(2));
        assertEquals(Duration.ofSeconds(4), DeliveryEngine.backoffAfter(3));
        assertEquals(Duration.ofSeconds(8), DeliveryEngine.backoffAfter(4));
        assertEquals(Duration.ofSeconds(8), DeliveryEngine.backoffAfter(50),
                "the cap holds for absurd inputs");
    }

    @Test
    void jitterOnlyEverAddsDelay() {
        DeliveryEngine engine = new DeliveryEngine(store, new ScriptedPort(Integer.MAX_VALUE),
                clock, () -> 0.5);
        engine.fire(subscription(), "evt_jitter", "invoice.paid", "{}");
        engine.attemptNextDue(T0);
        WebhookDelivery after = store.deliveries("sub_test").getFirst();
        assertEquals(T0.plusMillis(1500), after.nextAttemptAt(),
                "1s base + 50% jitter = exactly 1.5s here (deterministic fraction)");
        assertTrue(after.nextAttemptAt().isAfter(T0.plus(Duration.ofSeconds(1))));
    }

    @Test
    void nothingDueMeansEmptyTickNotAnError() {
        DeliveryEngine engine = new DeliveryEngine(store, new ScriptedPort(0), clock, () -> 0.0);
        assertEquals(0, engine.attemptNextDue(T0).size());
    }
}
