package guide.domain.webhooks;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The persistence port for topic 14's simulator: everything the delivery
 * engine, the use cases, and the loopback receiver need to remember. The
 * production adapter is the bounded in-memory store (data/webhooks) — a
 * teaching simulator's state is per-run and disposable — but the engine and
 * use cases only ever speak this interface, so a durable store could slot
 * in without touching a line of domain code.
 */
public interface WebhookStore {

    void saveSubscription(WebhookSubscription subscription);

    Optional<WebhookSubscription> findSubscription(String id);

    /** Insertion order, newest last — the console's subscription list. */
    List<WebhookSubscription> subscriptions();

    void saveDelivery(WebhookDelivery delivery);

    /** One subscription's delivery history, newest first — the timeline the console polls. */
    List<WebhookDelivery> deliveries(String subscriptionId);

    /**
     * The scheduler seam's better half: deliveries whose nextAttemptAt is due
     * and which are still PENDING, oldest-due first, at most {@code limit} —
     * the engine's entire view of "what time has made runnable".
     */
    List<WebhookDelivery> dueDeliveries(Instant now, int limit);

    /** The receiver's record of everything it saw — both sides of the console. */
    void saveReceipt(WebhookReceipt receipt);

    List<WebhookReceipt> receipts();

    /** The demo's deterministic teaching hook: script how the receiver answers one event id. */
    void scriptResponse(String eventId, String respondBehavior);

    Optional<String> scriptedResponse(String eventId);
}
