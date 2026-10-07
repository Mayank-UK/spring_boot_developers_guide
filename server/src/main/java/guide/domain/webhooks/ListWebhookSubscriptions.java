package guide.domain.webhooks;

import java.util.List;

/**
 * Use case: the console's subscription list. Deliberately a DIFFERENT shape
 * from the aggregate — the view carries the MASKED secret only, because a
 * listing is exactly where secrets get leaked by lazy serialization. The
 * full secret left the system once, at subscribe time, and never comes back.
 */
public class ListWebhookSubscriptions {

    /** The listing row: everything about a subscription except the credential itself. */
    public record SubscriptionView(String id, String url, String secretMasked,
            List<String> eventTypes, boolean active, String createdAt) {

        static SubscriptionView of(WebhookSubscription subscription) {
            return new SubscriptionView(subscription.id(), subscription.url(),
                    subscription.maskedSecret(), subscription.eventTypes(),
                    subscription.active(), subscription.createdAt().toString());
        }
    }

    public record SubscriptionsView(List<SubscriptionView> subscriptions) {
    }

    private final WebhookStore store;

    public ListWebhookSubscriptions(WebhookStore store) {
        this.store = store;
    }

    public SubscriptionsView execute() {
        return new SubscriptionsView(store.subscriptions().stream()
                .map(SubscriptionView::of)
                .toList());
    }
}
