package guide.data.webhooks;

import guide.domain.webhooks.WebhookDelivery;
import guide.domain.webhooks.WebhookReceipt;
import guide.domain.webhooks.WebhookStore;
import guide.domain.webhooks.WebhookSubscription;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter: the simulator's state, in memory, bounded. A teaching console
 * must never grow without limit because someone left a poll running — so the
 * store evicts oldest-first at fixed ceilings (subscriptions, per-subscription
 * deliveries, receipts) and the scripts map stays naturally small (one entry
 * per fired event that carried a respond override).
 *
 * All methods are synchronized: the @Scheduled tick thread and request
 * threads share this store, and a demo that corrupts itself under its own
 * load would teach the wrong lesson. Ordering: subscriptions in creation
 * order, deliveries newest-first (the timeline), receipts newest-first.
 */
public final class InMemoryWebhookStore implements WebhookStore {

    static final int MAX_SUBSCRIPTIONS = 20;
    static final int MAX_DELIVERIES_PER_SUBSCRIPTION = 50;
    static final int MAX_RECEIPTS = 100;

    private final Map<String, WebhookSubscription> subscriptions = new LinkedHashMap<>();
    private final Map<String, Deque<WebhookDelivery>> deliveriesBySubscription = new LinkedHashMap<>();
    private final Deque<WebhookReceipt> receipts = new ArrayDeque<>();
    private final Map<String, String> scripts = new LinkedHashMap<>();

    @Override
    public synchronized void saveSubscription(WebhookSubscription subscription) {
        subscriptions.put(subscription.id(), subscription);
        while (subscriptions.size() > MAX_SUBSCRIPTIONS) {
            String oldest = subscriptions.keySet().iterator().next();
            subscriptions.remove(oldest);
            deliveriesBySubscription.remove(oldest);
        }
    }

    @Override
    public synchronized Optional<WebhookSubscription> findSubscription(String id) {
        return Optional.ofNullable(subscriptions.get(id));
    }

    @Override
    public synchronized List<WebhookSubscription> subscriptions() {
        return List.copyOf(subscriptions.values());
    }

    @Override
    public synchronized void saveDelivery(WebhookDelivery delivery) {
        Deque<WebhookDelivery> history = deliveriesBySubscription.computeIfAbsent(
                delivery.subscriptionId(), ignored -> new ArrayDeque<>());
        history.removeIf(existing -> existing.id().equals(delivery.id()));
        history.addFirst(delivery);
        while (history.size() > MAX_DELIVERIES_PER_SUBSCRIPTION) {
            history.removeLast();
        }
    }

    @Override
    public synchronized List<WebhookDelivery> deliveries(String subscriptionId) {
        Deque<WebhookDelivery> history = deliveriesBySubscription.get(subscriptionId);
        return history == null ? List.of() : List.copyOf(history);
    }

    @Override
    public synchronized List<WebhookDelivery> dueDeliveries(Instant now, int limit) {
        List<WebhookDelivery> due = new ArrayList<>();
        for (Deque<WebhookDelivery> history : deliveriesBySubscription.values()) {
            for (WebhookDelivery delivery : history) {
                if (delivery.state() == WebhookDelivery.DeliveryState.PENDING
                        && delivery.nextAttemptAt() != null
                        && !delivery.nextAttemptAt().isAfter(now)) {
                    due.add(delivery);
                }
            }
        }
        due.sort(Comparator.comparing(WebhookDelivery::nextAttemptAt));
        return due.size() <= limit ? due : new ArrayList<>(due.subList(0, limit));
    }

    @Override
    public synchronized void saveReceipt(WebhookReceipt receipt) {
        receipts.addFirst(receipt);
        while (receipts.size() > MAX_RECEIPTS) {
            receipts.removeLast();
        }
    }

    @Override
    public synchronized List<WebhookReceipt> receipts() {
        return List.copyOf(receipts);
    }

    @Override
    public synchronized void scriptResponse(String eventId, String respondBehavior) {
        scripts.put(eventId, respondBehavior);
        while (scripts.size() > MAX_RECEIPTS) {
            scripts.remove(scripts.keySet().iterator().next());
        }
    }

    @Override
    public synchronized Optional<String> scriptedResponse(String eventId) {
        return Optional.ofNullable(scripts.get(eventId));
    }
}
