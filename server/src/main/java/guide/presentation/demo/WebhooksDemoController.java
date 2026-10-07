package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.webhooks.FireWebhookEvent;
import guide.domain.webhooks.ListWebhookDeliveries;
import guide.domain.webhooks.ListWebhookReceipts;
import guide.domain.webhooks.ListWebhookSubscriptions;
import guide.domain.webhooks.SubscribeWebhook;
import guide.domain.webhooks.WebhookDelivery;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 14 (webhooks) — the SENDER's console:
 * - POST /api/demo/webhooks/subscriptions                    → subscribe (url
 *   defaults to the in-app receiver; the secret is shown ONCE, here)
 * - GET  /api/demo/webhooks/subscriptions                    → list (masked secrets)
 * - POST /api/demo/webhooks/subscriptions/{id}/fire          → fire one event
 *   ({eventType, payload, respond?} — respond scripts the receiver for
 *   deterministic failure injection: 200 | 500 | timeout)
 * - GET  /api/demo/webhooks/subscriptions/{id}/deliveries    → the attempt
 *   timeline: backoff schedule, HTTP statuses, DLQ state
 * - GET  /api/demo/webhooks/receipts                         → the consumer's
 *   log, beside the sender's view
 *
 * Thin as always: bind, call ONE use case, map the Result onto HTTP — all
 * retry/backoff/DLQ policy lives in the domain engine, never here.
 */
@RestController
public class WebhooksDemoController {

    private final SubscribeWebhook subscribeWebhook;
    private final ListWebhookSubscriptions listWebhookSubscriptions;
    private final FireWebhookEvent fireWebhookEvent;
    private final ListWebhookDeliveries listWebhookDeliveries;
    private final ListWebhookReceipts listWebhookReceipts;

    public WebhooksDemoController(SubscribeWebhook subscribeWebhook,
            ListWebhookSubscriptions listWebhookSubscriptions, FireWebhookEvent fireWebhookEvent,
            ListWebhookDeliveries listWebhookDeliveries, ListWebhookReceipts listWebhookReceipts) {
        this.subscribeWebhook = subscribeWebhook;
        this.listWebhookSubscriptions = listWebhookSubscriptions;
        this.fireWebhookEvent = fireWebhookEvent;
        this.listWebhookDeliveries = listWebhookDeliveries;
        this.listWebhookReceipts = listWebhookReceipts;
    }

    @PostMapping(value = "/api/demo/webhooks/subscriptions",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    SubscribeWebhook.Created subscribe(@RequestBody SubscribeWebhook.Request request) {
        return unwrap(subscribeWebhook.execute(request));
    }

    @GetMapping(value = "/api/demo/webhooks/subscriptions",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ListWebhookSubscriptions.SubscriptionsView subscriptions() {
        return listWebhookSubscriptions.execute();
    }

    @PostMapping(value = "/api/demo/webhooks/subscriptions/{id}/fire",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ListWebhookDeliveries.DeliveryView fire(@PathVariable String id,
            @RequestBody FireWebhookEvent.Request request) {
        Result<WebhookDelivery> result = fireWebhookEvent.execute(id, request);
        return unwrap(result.map(ListWebhookDeliveries::deliveryView));
    }

    @GetMapping(value = "/api/demo/webhooks/subscriptions/{id}/deliveries",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ListWebhookDeliveries.DeliveriesView deliveries(@PathVariable String id) {
        return listWebhookDeliveries.execute(id);
    }

    @GetMapping(value = "/api/demo/webhooks/receipts",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ListWebhookReceipts.ReceiptsView receipts() {
        return listWebhookReceipts.execute();
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
