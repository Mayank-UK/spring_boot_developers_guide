package guide.domain.webhooks;

import java.util.List;

/**
 * Use case: the sender-side timeline — every delivery for one subscription,
 * every attempt with its outcome and HTTP status, the scheduled next attempt,
 * and how many tries remain before the dead-letter queue. This is the view
 * the console polls while the engine works; timestamps ride as ISO strings
 * so the wire shape is stable and the client never parses epoch math.
 */
public class ListWebhookDeliveries {

    /** One attempt in the timeline — the signature header shown is per-request, never the secret. */
    public record AttemptView(int n, String at, String outcome, Integer httpStatus,
            String signatureHeader) {

        static AttemptView of(WebhookDelivery.Attempt attempt) {
            return new AttemptView(attempt.n(), attempt.at().toString(),
                    attempt.outcome().wireName(), attempt.httpStatus(), attempt.signatureHeader());
        }
    }

    /** One delivery: state, full attempt history, and the backoff bookkeeping. */
    public record DeliveryView(String id, String eventId, String eventType, String state,
            String payload, List<AttemptView> attempts, String nextAttemptAt, int attemptsLeft) {

        static DeliveryView of(WebhookDelivery delivery) {
            return new DeliveryView(delivery.id(), delivery.eventId(), delivery.eventType(),
                    delivery.state().wireName(), delivery.payload(),
                    delivery.attempts().stream().map(AttemptView::of).toList(),
                    delivery.nextAttemptAt() == null ? null : delivery.nextAttemptAt().toString(),
                    DeliveryEngine.MAX_ATTEMPTS - delivery.attempts().size());
        }
    }

    public record DeliveriesView(String subscriptionId, List<DeliveryView> deliveries) {
    }

    private final WebhookStore store;

    public ListWebhookDeliveries(WebhookStore store) {
        this.store = store;
    }

    public DeliveriesView execute(String subscriptionId) {
        return new DeliveriesView(subscriptionId,
                store.deliveries(subscriptionId).stream().map(DeliveryView::of).toList());
    }

    /** Single-delivery mapping for the fire endpoint's immediate response. */
    public static DeliveryView deliveryView(WebhookDelivery delivery) {
        return DeliveryView.of(delivery);
    }
}
