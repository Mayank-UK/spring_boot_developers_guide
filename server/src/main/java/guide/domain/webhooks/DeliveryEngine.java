package guide.domain.webhooks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Topic 14's delivery engine: the state machine that walks every delivery
 * from {@code pending} to {@code delivered} or the dead-letter queue. Policy
 * lives here and NOWHERE else — how many attempts (5), how the backoff grows
 * (exponential from 1s, doubled per failure, capped at 8s, plus jitter), and
 * when a delivery becomes runnable again.
 *
 * The two seams that make it testable and demo-drivable:
 * - the {@link Clock} is injected (production: system UTC; tests: a fake they
 *   advance by hand), and
 * - {@link #attemptNextDue(Instant)} takes {@code now} EXPLICITLY instead of
 *   reading the clock — the @Scheduled ticker passes the wall clock, tests
 *   pass constructed instants, and neither sleeps. The engine never owns a
 *   timer; time is a parameter.
 */
public final class DeliveryEngine {

    /** After this many attempts the delivery is dead-lettered — the DLQ is a place, and this is the door. */
    public static final int MAX_ATTEMPTS = 5;

    /** backoff = 1s, 2s, 4s, then capped at 8s (and jitter on top, up to +100%). */
    static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(8);

    /** How many due deliveries one tick may process — the batch ceiling that keeps a tick bounded. */
    static final int TICK_BATCH = 10;

    private final WebhookStore store;
    private final HttpDeliveryPort delivery;
    private final Clock clock;
    private final DoubleSupplier jitter;

    /**
     * @param jitter uniform [0,1) — the share of the base delay added on top,
     *               so competing senders don't retry in lockstep (thundering
     *               herd). Tests inject {@code () -> 0.0} for exact schedules.
     */
    public DeliveryEngine(WebhookStore store, HttpDeliveryPort delivery, Clock clock,
            DoubleSupplier jitter) {
        this.store = Objects.requireNonNull(store, "store");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    /** Enqueue one event for one subscription: attempt 1 becomes due immediately. */
    public WebhookDelivery fire(WebhookSubscription subscription, String eventId,
            String eventType, String payloadJson) {
        WebhookDelivery delivery = new WebhookDelivery("dlv_" + UUID.randomUUID(),
                subscription.id(), eventId, eventType, payloadJson,
                List.of(), WebhookDelivery.DeliveryState.PENDING, clock.instant());
        store.saveDelivery(delivery);
        return delivery;
    }

    /**
     * The tick: attempt everything time has made runnable. Returns the
     * deliveries it touched (empty when the queue is idle) so callers and
     * tests can assert on the effect of one tick without re-querying.
     */
    public List<WebhookDelivery> attemptNextDue(Instant now) {
        List<WebhookDelivery> touched = new ArrayList<>();
        for (WebhookDelivery due : store.dueDeliveries(now, TICK_BATCH)) {
            touched.add(attempt(due, now));
        }
        return List.copyOf(touched);
    }

    private WebhookDelivery attempt(WebhookDelivery current, Instant now) {
        // publish the in-flight state first: a crash mid-POST leaves evidence, not a mystery
        store.saveDelivery(current.withState(WebhookDelivery.DeliveryState.DELIVERING,
                current.nextAttemptAt()));
        WebhookSubscription subscription = store.findSubscription(current.subscriptionId()).orElse(null);
        HttpDeliveryPort.AttemptOutcome outcome = subscription == null
                ? new HttpDeliveryPort.AttemptOutcome(false, null, null)
                : delivery.deliver(subscription, current.eventType(), current.payload());

        int attemptNumber = current.attempts().size() + 1;
        boolean lastChance = attemptNumber >= MAX_ATTEMPTS;
        WebhookDelivery next;
        if (outcome.delivered()) {
            next = current.advanced(WebhookDelivery.DeliveryState.DELIVERED, null,
                    new WebhookDelivery.Attempt(attemptNumber, now,
                            WebhookDelivery.Attempt.Outcome.DELIVERED,
                            outcome.httpStatus(), outcome.signatureHeader()));
        } else if (lastChance || subscription == null) {
            next = current.advanced(WebhookDelivery.DeliveryState.DEAD_LETTERED, null,
                    new WebhookDelivery.Attempt(attemptNumber, now,
                            WebhookDelivery.Attempt.Outcome.DEAD_LETTERED,
                            outcome.httpStatus(), outcome.signatureHeader()));
        } else {
            next = current.advanced(WebhookDelivery.DeliveryState.PENDING,
                    now.plus(withJitter(backoffAfter(attemptNumber))),
                    new WebhookDelivery.Attempt(attemptNumber, now,
                            WebhookDelivery.Attempt.Outcome.FAILED,
                            outcome.httpStatus(), outcome.signatureHeader()));
        }
        store.saveDelivery(next);
        return next;
    }

    /**
     * The backoff curve as a pure function: 1s, 2s, 4s, 8s, then flat —
     * exponential growth shows respect for a struggling consumer, the cap
     * shows respect for your own freshness SLA.
     */
    static Duration backoffAfter(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("backoff starts after the first failure");
        }
        long exponent = Math.min(failedAttempts - 1, 3);
        Duration delay = BASE_BACKOFF.multipliedBy(1L << exponent);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private Duration withJitter(Duration delay) {
        long extra = (long) (delay.toMillis() * jitter.getAsDouble());
        return delay.plusMillis(extra);
    }
}
