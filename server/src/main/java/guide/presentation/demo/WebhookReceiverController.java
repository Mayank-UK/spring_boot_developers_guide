package guide.presentation.demo;

import guide.domain.webhooks.ReceiveWebhookDelivery;
import guide.domain.webhooks.SignatureExhibit;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoint for topic 14 — the CONSUMER, sitting inside the sender's app
 * so the whole webhook journey is observable from one process (the
 * local-development story). POST /api/demo/webhooks/receiver is what the
 * delivery engine actually hits on localhost.
 *
 * Two deliberate breaks with this app's own conventions, because the
 * receiver is simulating ANOTHER service and must behave like one:
 * - the body binds as a raw {@link String} — the signature is verified over
 *   the exact received bytes, and a re-serialized parse of them would sign
 *   nothing (the controller never even sees a DTO);
 * - answers are plain {@link ResponseEntity}s, not the app's error envelope —
 *   a webhook consumer speaks status codes to the sender's retry policy,
 *   which reads the status and nothing else.
 *
 * Teaching hooks (query, all optional): {@code ?respond=200|500|timeout}
 * injects the consumer's answer for THIS request; {@code ?toleranceSeconds=}
 * shrinks the replay window so a captured-signature replay is demonstrable
 * in seconds instead of minutes.
 */
@RestController
public class WebhookReceiverController {

    private static final long MIN_TOLERANCE_SECONDS = 1;
    private static final long MAX_TOLERANCE_SECONDS = 3600;

    private final ReceiveWebhookDelivery receiveWebhookDelivery;

    public WebhookReceiverController(ReceiveWebhookDelivery receiveWebhookDelivery) {
        this.receiveWebhookDelivery = receiveWebhookDelivery;
    }

    @PostMapping(value = "/api/demo/webhooks/receiver",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReceiveWebhookDelivery.Outcome> receive(
            @RequestHeader(name = SignatureExhibit.SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(name = SignatureExhibit.EVENT_HEADER, required = false) String event,
            @RequestBody String body,
            @RequestParam(name = "respond", required = false) String respond,
            @RequestParam(name = "toleranceSeconds", required = false) Long toleranceSeconds) {
        Duration tolerance = clampTolerance(toleranceSeconds);
        ReceiveWebhookDelivery.Outcome outcome = receiveWebhookDelivery.receive(
                signature, event, body, respond, tolerance);
        return ResponseEntity.status(outcome.status()).body(outcome);
    }

    private static Duration clampTolerance(Long toleranceSeconds) {
        if (toleranceSeconds == null) {
            return SignatureExhibit.DEFAULT_TOLERANCE;
        }
        long clamped = Math.max(MIN_TOLERANCE_SECONDS, Math.min(toleranceSeconds, MAX_TOLERANCE_SECONDS));
        return Duration.ofSeconds(clamped);
    }
}
