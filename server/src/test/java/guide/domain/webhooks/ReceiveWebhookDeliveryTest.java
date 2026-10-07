package guide.domain.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * ReceiveWebhookDelivery tests — the consumer's contract, each claim from
 * the receiving section: signature verified over raw bytes BEFORE the body
 * is interpreted; 2xx answered immediately; duplicates flagged (at-least-once);
 * ?respond= and the fire-time script steer the answer; timeout is exercised
 * through a counting Delayer (no thread ever sleeps here).
 */
class ReceiveWebhookDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final String SECRET = "whsec_receiver_test_secret_0123";
    private static final String RECEIVER_URL = "http://localhost:8080/api/demo/webhooks/receiver";

    private final ObjectMapper mapper = JsonMapper.builder().build();

    /** Store fake with script support — the receiver's slice of persistence. */
    private static final class ReceiverStore implements WebhookStore {
        final java.util.LinkedHashMap<String, WebhookSubscription> subs = new java.util.LinkedHashMap<>();
        final java.util.ArrayList<WebhookReceipt> receipts = new java.util.ArrayList<>();
        final java.util.LinkedHashMap<String, String> scripts = new java.util.LinkedHashMap<>();

        @Override
        public void saveSubscription(WebhookSubscription subscription) {
            subs.put(subscription.id(), subscription);
        }

        @Override
        public java.util.Optional<WebhookSubscription> findSubscription(String id) {
            return java.util.Optional.ofNullable(subs.get(id));
        }

        @Override
        public List<WebhookSubscription> subscriptions() {
            return List.copyOf(subs.values());
        }

        @Override
        public void saveDelivery(WebhookDelivery delivery) {
        }

        @Override
        public List<WebhookDelivery> deliveries(String subscriptionId) {
            return List.of();
        }

        @Override
        public List<WebhookDelivery> dueDeliveries(Instant now, int limit) {
            return List.of();
        }

        @Override
        public void saveReceipt(WebhookReceipt receipt) {
            receipts.add(receipt);
        }

        @Override
        public List<WebhookReceipt> receipts() {
            return List.copyOf(receipts);
        }

        @Override
        public void scriptResponse(String eventId, String respondBehavior) {
            scripts.put(eventId, respondBehavior);
        }

        @Override
        public java.util.Optional<String> scriptedResponse(String eventId) {
            return java.util.Optional.ofNullable(scripts.get(eventId));
        }
    }

    private final ReceiverStore store = new ReceiverStore();

    private ReceiveWebhookDelivery receiver(AtomicInteger delayCalls) {
        store.saveSubscription(new WebhookSubscription("sub_r", RECEIVER_URL, SECRET,
                List.of("invoice.paid"), true, NOW));
        Clock fixed = new Clock() {
            @Override
            public Instant instant() {
                return NOW;
            }

            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };
        return new ReceiveWebhookDelivery(store, mapper, fixed, duration -> delayCalls.incrementAndGet());
    }

    private String signed(String body) {
        return SignatureExhibit.signatureHeader(SECRET, String.valueOf(NOW.getEpochSecond()), body);
    }

    @Test
    void verifiedDeliveryIsAcceptedFastAndRecorded() {
        AtomicInteger delays = new AtomicInteger();
        String body = "{\"id\":\"evt_a\",\"type\":\"invoice.paid\",\"data\":{\"amount\":1}}";
        ReceiveWebhookDelivery.Outcome outcome = receiver(delays).receive(
                signed(body), "invoice.paid", body, null, SignatureExhibit.DEFAULT_TOLERANCE);
        assertEquals(200, outcome.status());
        assertTrue(outcome.accepted());
        assertEquals("verified", outcome.signature());
        assertEquals("evt_a", outcome.eventId());
        assertEquals(0, delays.get(), "no sleep on the happy path — 2xx is immediate");
        assertEquals(1, store.receipts().size());
        assertFalse(store.receipts().getFirst().duplicate());
    }

    @Test
    void invalidSignatureIsRefusedBeforeParsingAndStillRecorded() {
        AtomicInteger delays = new AtomicInteger();
        String body = "{\"id\":\"evt_b\",\"type\":\"invoice.paid\",\"data\":{}}";
        String wrongSecretHeader = SignatureExhibit.signatureHeader("whsec_completely_wrong_secret",
                String.valueOf(NOW.getEpochSecond()), body);
        ReceiveWebhookDelivery.Outcome outcome = receiver(delays).receive(
                wrongSecretHeader, "invoice.paid", body, null, SignatureExhibit.DEFAULT_TOLERANCE);
        assertEquals(401, outcome.status());
        assertFalse(outcome.accepted());
        assertEquals("invalid", outcome.signature());
        assertEquals("(unverified)", outcome.eventId(),
                "the envelope id is never read when the signature fails");
        assertEquals(1, store.receipts().size());
    }

    @Test
    void staleSignatureIsRejectedEvenThoughHmacIsValid() {
        AtomicInteger delays = new AtomicInteger();
        String body = "{\"id\":\"evt_c\",\"type\":\"invoice.paid\",\"data\":{}}";
        String oldTimestamp = String.valueOf(NOW.minus(Duration.ofMinutes(10)).getEpochSecond());
        String stale = SignatureExhibit.signatureHeader(SECRET, oldTimestamp, body);
        ReceiveWebhookDelivery.Outcome outcome = receiver(delays).receive(
                stale, "invoice.paid", body, null, SignatureExhibit.DEFAULT_TOLERANCE);
        assertEquals(401, outcome.status());
        assertEquals("stale", outcome.signature());
    }

    @Test
    void respondOverrideAnswers500AndRecordsIt() {
        AtomicInteger delays = new AtomicInteger();
        String body = "{\"id\":\"evt_d\",\"type\":\"invoice.paid\",\"data\":{}}";
        ReceiveWebhookDelivery.Outcome outcome = receiver(delays).receive(
                signed(body), "invoice.paid", body, "500",
                SignatureExhibit.DEFAULT_TOLERANCE);
        assertEquals(500, outcome.status());
        assertFalse(outcome.accepted());
        assertEquals(500, store.receipts().getFirst().respondedStatus());
    }

    @Test
    void fireTimeScriptSteersTheReceiverWithoutQueryParams() {
        AtomicInteger delays = new AtomicInteger();
        String body = "{\"id\":\"evt_e\",\"type\":\"invoice.paid\",\"data\":{}}";
        store.scriptResponse("evt_e", "500");
        ReceiveWebhookDelivery.Outcome outcome = receiver(delays).receive(
                signed(body), "invoice.paid", body, null, SignatureExhibit.DEFAULT_TOLERANCE);
        assertEquals(500, outcome.status(), "the script left at fire time wins when no query override");
    }

    @Test
    void timeoutBehaviorDelaysThenStillAnswers() {
        AtomicInteger delays = new AtomicInteger();
        String body = "{\"id\":\"evt_f\",\"type\":\"invoice.paid\",\"data\":{}}";
        ReceiveWebhookDelivery.Outcome outcome = receiver(delays).receive(
                signed(body), "invoice.paid", body, "timeout",
                SignatureExhibit.DEFAULT_TOLERANCE);
        assertEquals(1, delays.get(), "the timeout path delays exactly once");
        assertEquals(200, outcome.status(),
                "late 2xx still arrives — but the sender's 2s read timeout fired long before");
    }

    @Test
    void duplicateEventIdsAreFlaggedNotRejected() {
        AtomicInteger delays = new AtomicInteger();
        String body = "{\"id\":\"evt_g\",\"type\":\"invoice.paid\",\"data\":{}}";
        ReceiveWebhookDelivery receive = receiver(delays);
        receive.receive(signed(body), "invoice.paid", body, null, SignatureExhibit.DEFAULT_TOLERANCE);
        ReceiveWebhookDelivery.Outcome again = receive.receive(
                signed(body), "invoice.paid", body, null, SignatureExhibit.DEFAULT_TOLERANCE);
        assertEquals(200, again.status(), "a duplicate is the sender's at-least-once, not an error");
        assertTrue(again.duplicate());
        assertTrue(store.receipts().getLast().duplicate());
    }

    @Test
    void unparseableButVerifiedBodyIsA400NotACrash() {
        AtomicInteger delays = new AtomicInteger();
        String garbage = "verified-but-not-json";
        ReceiveWebhookDelivery.Outcome outcome = receiver(delays).receive(
                signed(garbage), "invoice.paid", garbage, null, SignatureExhibit.DEFAULT_TOLERANCE);
        assertEquals(400, outcome.status());
        assertFalse(outcome.accepted());
    }

    @Test
    void unknownRespondBehaviorIsA400NotA500() {
        AtomicInteger delays = new AtomicInteger();
        String body = "{\"id\":\"evt_h\",\"type\":\"invoice.paid\",\"data\":{}}";
        ReceiveWebhookDelivery.Outcome outcome = receiver(delays).receive(
                signed(body), "invoice.paid", body, "teapot",
                SignatureExhibit.DEFAULT_TOLERANCE);
        assertEquals(400, outcome.status());
    }
}
