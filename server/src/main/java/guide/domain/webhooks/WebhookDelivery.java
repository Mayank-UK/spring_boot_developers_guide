package guide.domain.webhooks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One event on its way to one subscription — the unit the delivery engine
 * shepherds. The state machine is the topic's spine:
 *
 * <pre>
 * pending ──tick──▶ delivering ──2xx──▶ delivered            (terminal)
 *    ▲                  │
 *    └── +backoff ──────┤ non-2xx / transport error
 *                       └── attempts ≥ MAX ──▶ dead-lettered (terminal)
 * </pre>
 *
 * Immutable by design: the engine never mutates a delivery in place, it
 * derives the next state with {@link #advanced(DeliveryState, Instant,
 * Attempt)} so every attempt append is a new value — the same
 * events-as-data discipline the rest of this codebase teaches.
 */
public record WebhookDelivery(
        String id,
        String subscriptionId,
        String eventId,
        String eventType,
        /** The exact signed bytes: the envelope JSON as it will be POSTed. */
        String payload,
        List<Attempt> attempts,
        DeliveryState state,
        /** When the engine may next try; null once terminal. Drives the backoff timeline. */
        Instant nextAttemptAt) {

    /** Wire name is the lower-case hyphenated form the topic teaches: "dead-lettered". */
    public enum DeliveryState {
        PENDING("pending"),
        DELIVERING("delivering"),
        DELIVERED("delivered"),
        DEAD_LETTERED("dead-lettered");

        private final String wireName;

        DeliveryState(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public boolean terminal() {
            return this == DELIVERED || this == DEAD_LETTERED;
        }
    }

    /**
     * One try, verbatim: which attempt number, when, how it ended, the HTTP
     * status if there was an HTTP response at all (null = transport-level
     * failure: refused, timed out), and the exact signature header that was
     * sent — safe to show, because a per-request digest is not the secret.
     */
    public record Attempt(int n, Instant at, Outcome outcome, Integer httpStatus, String signatureHeader) {

        public enum Outcome {
            DELIVERED("delivered"),
            FAILED("failed"),
            DEAD_LETTERED("dead-lettered");

            private final String wireName;

            Outcome(String wireName) {
                this.wireName = wireName;
            }

            public String wireName() {
                return wireName;
            }
        }

        public Attempt {
            if (n < 1) {
                throw new IllegalArgumentException("attempt number starts at 1");
            }
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public WebhookDelivery {
        requireNonBlank(id, "id");
        requireNonBlank(subscriptionId, "subscriptionId");
        requireNonBlank(eventId, "eventId");
        requireNonBlank(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        Objects.requireNonNull(state, "state");
        if (state.terminal() && nextAttemptAt != null) {
            throw new IllegalArgumentException("terminal delivery has no nextAttemptAt");
        }
        if (!state.terminal() && nextAttemptAt == null) {
            throw new IllegalArgumentException("non-terminal delivery needs nextAttemptAt");
        }
    }

    /** The engine's only mutation path: derive the next delivery value with one more attempt recorded. */
    public WebhookDelivery advanced(DeliveryState nextState, Instant nextAttemptAt, Attempt attempt) {
        if (attempt.n() != attempts.size() + 1) {
            throw new IllegalArgumentException("attempt numbers are consecutive from 1");
        }
        List<Attempt> nextAttempts = new ArrayList<>(attempts);
        nextAttempts.add(attempt);
        return new WebhookDelivery(id, subscriptionId, eventId, eventType, payload,
                List.copyOf(nextAttempts), nextState, nextAttemptAt);
    }

    /**
     * State-only transition (attempts unchanged) — how the engine publishes
     * "the POST is in flight" before the network call, so a crash mid-attempt
     * leaves evidence in the store instead of an invisible limbo.
     */
    public WebhookDelivery withState(DeliveryState nextState, Instant nextAt) {
        return new WebhookDelivery(id, subscriptionId, eventId, eventType, payload,
                attempts, nextState, nextAt);
    }

    private static void requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("delivery " + what + " must not be blank");
        }
    }
}
