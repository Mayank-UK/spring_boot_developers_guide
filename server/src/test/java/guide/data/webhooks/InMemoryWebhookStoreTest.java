package guide.data.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.domain.webhooks.WebhookDelivery;
import guide.domain.webhooks.WebhookReceipt;
import guide.domain.webhooks.WebhookSubscription;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * InMemoryWebhookStore tests — the bounds that keep a teaching console from
 * growing forever, and the dueDeliveries contract the engine's tick leans
 * on: PENDING only, due only, oldest-due first, batch-limited.
 */
class InMemoryWebhookStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

    private WebhookSubscription subscription(String id) {
        return new WebhookSubscription(id, "http://localhost:8080/api/demo/webhooks/receiver",
                "whsec_store_test_secret_" + id, List.of("invoice.paid"), true, T0);
    }

    private WebhookDelivery delivery(String id, String subscriptionId, Instant dueAt) {
        return new WebhookDelivery(id, subscriptionId, "evt_" + id, "invoice.paid", "{}",
                List.of(), WebhookDelivery.DeliveryState.PENDING, dueAt);
    }

    @Test
    void subscriptionsEvictOldestBeyondTheCeiling() {
        InMemoryWebhookStore store = new InMemoryWebhookStore();
        for (int i = 0; i < InMemoryWebhookStore.MAX_SUBSCRIPTIONS + 3; i++) {
            store.saveSubscription(subscription("sub_" + i));
        }
        assertEquals(InMemoryWebhookStore.MAX_SUBSCRIPTIONS, store.subscriptions().size());
        assertEquals(Optional.empty(), store.findSubscription("sub_0"),
                "the three oldest are gone");
        assertTrue(store.findSubscription("sub_5").isPresent(), "the newest survive");
    }

    @Test
    void deliveriesKeepNewestFirstAndBounded() {
        InMemoryWebhookStore store = new InMemoryWebhookStore();
        store.saveSubscription(subscription("sub_a"));
        for (int i = 0; i < InMemoryWebhookStore.MAX_DELIVERIES_PER_SUBSCRIPTION + 5; i++) {
            store.saveDelivery(delivery("dlv_" + i, "sub_a", T0.plusSeconds(i)));
        }
        List<WebhookDelivery> history = store.deliveries("sub_a");
        assertEquals(InMemoryWebhookStore.MAX_DELIVERIES_PER_SUBSCRIPTION, history.size());
        assertEquals("dlv_" + (InMemoryWebhookStore.MAX_DELIVERIES_PER_SUBSCRIPTION + 4),
                history.getFirst().id(), "newest is first");
    }

    @Test
    void dueDeliveriesArePendingDueOldestFirstAndLimited() {
        InMemoryWebhookStore store = new InMemoryWebhookStore();
        store.saveSubscription(subscription("sub_b"));
        store.saveDelivery(delivery("later", "sub_b", T0.plusSeconds(30)));
        store.saveDelivery(delivery("second", "sub_b", T0.plusSeconds(10)));
        store.saveDelivery(delivery("first", "sub_b", T0.plusSeconds(5)));
        WebhookDelivery delivered = delivery("done", "sub_b", T0)
                .withState(WebhookDelivery.DeliveryState.DELIVERED, null);
        store.saveDelivery(delivered);

        List<WebhookDelivery> due = store.dueDeliveries(T0.plusSeconds(20), 10);
        assertEquals(List.of("first", "second"), due.stream().map(WebhookDelivery::id).toList(),
                "due, pending, oldest first — delivered and future excluded");
        assertEquals(List.of("first"), store.dueDeliveries(T0.plusSeconds(20), 1).stream()
                .map(WebhookDelivery::id).toList(), "the limit is honored");
    }

    @Test
    void receiptsKeepNewestFirstAndBounded() {
        InMemoryWebhookStore store = new InMemoryWebhookStore();
        for (int i = 0; i < InMemoryWebhookStore.MAX_RECEIPTS + 2; i++) {
            store.saveReceipt(new WebhookReceipt("evt_" + i, "invoice.paid", "verified",
                    "ok", false, 200, "note", T0.plusSeconds(i)));
        }
        assertEquals(InMemoryWebhookStore.MAX_RECEIPTS, store.receipts().size());
        assertEquals("evt_" + (InMemoryWebhookStore.MAX_RECEIPTS + 1),
                store.receipts().getFirst().eventId(), "newest first");
    }

    @Test
    void scriptsRoundTrip() {
        InMemoryWebhookStore store = new InMemoryWebhookStore();
        store.scriptResponse("evt_x", "500");
        assertEquals(Optional.of("500"), store.scriptedResponse("evt_x"));
        assertEquals(Optional.empty(), store.scriptedResponse("evt_never"));
    }
}
