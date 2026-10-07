package guide.domain.webhooks;

/**
 * The one-port boundary between the delivery engine and the network: POST the
 * body to the subscription's URL with the signature headers, and report what
 * happened. The engine owns policy (retries, backoff, dead-lettering); the
 * adapter owns transport only — which is exactly why the engine's tests can
 * drive a full five-attempt descent to the DLQ with a fake port and a fake
 * clock, no sockets, no sleeps.
 *
 * {@code httpStatus} is null on transport-level failure (connection refused,
 * timeout) — "no HTTP response exists" is a fact the timeline should show,
 * not an error to paper over.
 */
public interface HttpDeliveryPort {

    AttemptOutcome deliver(WebhookSubscription subscription, String eventType, String body);

    /**
     * @param delivered       2xx was returned — the consumer acknowledged
     * @param httpStatus      the numeric status, or null if none was received
     * @param signatureHeader the exact X-SDG-Signature sent (per-request, safe to display)
     */
    record AttemptOutcome(boolean delivered, Integer httpStatus, String signatureHeader) {
    }
}
