package guide.domain.webhooks;

import guide.domain.webhooks.SignatureExhibit.SignatureVerdict;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Use case: the consumer's chair. The loopback receiver hands over the raw
 * body string, the signature header, and optional teaching overrides, and
 * THIS code decides what a well-behaved webhook consumer does — in the order
 * the topic insists on:
 *
 * 1. VERIFY THE SIGNATURE over the raw bytes, constant-time, before anything
 *    else — not after parsing, not after business logic. An unverified body
 *    is attacker-controlled bytes; parsing it deeply first is how
 *    parser-differential and deserialization bugs become incidents.
 * 2. Only then read the envelope (id, type) — shallowly.
 * 3. Answer 2xx IMMEDIATELY and process asynchronously (here: the receipt is
 *    the "processing"). A consumer that does real work before responding
 *    teaches the sender to time it out.
 * 4. Dedupe on the event id, because at-least-once delivery means duplicates
 *    are a WHEN, not an IF — the receipt records it.
 *
 * Teaching hooks (the honest ones this demo owns): ?respond= overrides the
 * consumer's answer for one request, and a per-event script left by the fire
 * use case does the same for a whole delivery — how failure injection stays
 * deterministic. {@link Delayer} is Thread::sleep in production and a no-op
 * in tests, so "timeout" is exercised without a single test sleeping.
 */
public class ReceiveWebhookDelivery {

    /** How the consumer answers — the demo's failure-injection surface. */
    public static final String BEHAVIOR_OK = "200";
    public static final String BEHAVIOR_ERROR = "500";
    public static final String BEHAVIOR_TIMEOUT = "timeout";

    /** What the receiver answers plus what it recorded — the controller maps this straight to HTTP. */
    public record Outcome(int status, boolean accepted, String eventId, String eventType,
            String signature, String signatureReason, boolean duplicate, String note) {
    }

    /** Injectable stand-in for Thread::sleep — the timeout behavior without slow tests. */
    @FunctionalInterface
    public interface Delayer {
        void delay(Duration duration);
    }

    private static final Duration TIMEOUT_LENGTH = Duration.ofSeconds(5);

    private final WebhookStore store;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Delayer delayer;

    public ReceiveWebhookDelivery(WebhookStore store, ObjectMapper mapper, Clock clock,
            Delayer delayer) {
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
        this.delayer = delayer;
    }

    /**
     * @param respondOverride the ?respond= query value, or null
     * @param tolerance       the replay window (query-overridable, default 5 minutes)
     */
    public Outcome receive(String signatureHeader, String eventHeader, String body,
            String respondOverride, Duration tolerance) {
        Objects.requireNonNull(body, "body");

        // 1. verify FIRST — over the exact received bytes, against every secret that
        // could have signed (the demo receiver serves all loopback subscriptions; a real
        // consumer has one configured secret, or one per known producer)
        SignatureVerdict verdict = verifyAgainstLoopbackSecrets(signatureHeader, body, tolerance);
        if (!verdict.valid()) {
            return recordAndAnswer(401, false, "(unverified)", eventHeaderOrFallback(eventHeader),
                    verdict, false, "refused before parsing — signature verdict: " + verdict.verdict());
        }

        // 2. parse the envelope SHALLOWLY — id and type only
        JsonNode envelope = parseEnvelope(body);
        if (envelope == null || !envelope.hasNonNull("id")) {
            return recordAndAnswer(400, false, "(unreadable)", eventHeaderOrFallback(eventHeader),
                    verdict, false, "verified but unparseable — a consumer still refuses cleanly");
        }
        String eventId = envelope.path("id").asString();
        String eventType = envelope.hasNonNull("type") ? envelope.path("type").asString()
                : eventHeaderOrFallback(eventHeader);

        // 3. resolve the answer: query override > the fire-time script > the happy default
        String behavior = resolveBehavior(eventId, respondOverride);
        if (!BEHAVIOR_OK.equals(behavior) && !BEHAVIOR_ERROR.equals(behavior)
                && !BEHAVIOR_TIMEOUT.equals(behavior)) {
            return recordAndAnswer(400, false, eventId, eventType, verdict, false,
                    "unknown respond behavior: " + behavior);
        }
        if (BEHAVIOR_TIMEOUT.equals(behavior)) {
            delayer.delay(TIMEOUT_LENGTH); // sender's read timeout (2s) fires first
        }

        // 4. dedupe on the event id — at-least-once means this WILL happen
        boolean duplicate = store.receipts().stream()
                .anyMatch(receipt -> receipt.eventId().equals(eventId));
        String note = duplicate
                ? "at-least-once: duplicate of an earlier receipt — consumer dedupes by id"
                : "2xx fast, then processed async — the consumer's contract";
        int status = BEHAVIOR_ERROR.equals(behavior) ? 500 : 200;
        return recordAndAnswer(status, status == 200, eventId, eventType, verdict, duplicate, note);
    }

    private SignatureVerdict verifyAgainstLoopbackSecrets(String signatureHeader, String body,
            Duration tolerance) {
        List<String> secrets = store.subscriptions().stream()
                .filter(subscription -> subscription.url().contains("/api/demo/webhooks/receiver"))
                .map(WebhookSubscription::secret)
                .toList();
        if (secrets.isEmpty()) {
            return new SignatureVerdict(false, "invalid",
                    "no subscription targets this receiver — nothing to verify against");
        }
        SignatureVerdict firstFailure = null;
        for (String secret : secrets) {
            SignatureVerdict verdict = SignatureExhibit.verify(secret, signatureHeader, body,
                    clock.instant(), tolerance);
            if (verdict.valid()) {
                return verdict;
            }
            firstFailure = firstFailure == null ? verdict : firstFailure;
        }
        return firstFailure;
    }

    private JsonNode parseEnvelope(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            return node.isObject() ? node : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private String resolveBehavior(String eventId, String respondOverride) {
        if (respondOverride != null && !respondOverride.isBlank()) {
            return respondOverride.trim();
        }
        return store.scriptedResponse(eventId).orElse(BEHAVIOR_OK);
    }

    private Outcome recordAndAnswer(int status, boolean accepted, String eventId, String eventType,
            SignatureVerdict verdict, boolean duplicate, String note) {
        store.saveReceipt(new WebhookReceipt(eventId, eventType, verdict.verdict(),
                verdict.reason(), duplicate, status, note, clock.instant()));
        return new Outcome(status, accepted, eventId, eventType, verdict.verdict(),
                verdict.reason(), duplicate, note);
    }

    private static String eventHeaderOrFallback(String eventHeader) {
        return eventHeader == null || eventHeader.isBlank() ? "(no event header)" : eventHeader;
    }
}
