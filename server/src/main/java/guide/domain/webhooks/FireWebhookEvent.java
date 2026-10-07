package guide.domain.webhooks;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Use case: one event leaves the building. Everything the "designing a
 * webhook" section teaches happens between this method's first and last
 * line:
 * - the CONSUMER'S problem is chosen, not guessed (subscription must exist,
 *   be active, and actually subscribe to the event type),
 * - the PAYLOAD ENVELOPE is built once — {id, type, occurredAt, data} — with
 *   an id the consumer can dedupe on and an occurredAt that means "when it
 *   happened", not "when we sent it",
 * - the envelope is serialized to its FINAL wire bytes immediately (the same
 *   string is stored, signed, and sent — a signature over re-serialized
 *   bytes is a signature over nothing), and
 * - the demo's deterministic teaching hook: {@code respond} scripts how the
 *   loopback receiver answers THIS event id, so a five-failure descent into
 *   the DLQ is reproducible on demand instead of hoped for.
 *
 * Jackson appears in a domain use case on purpose, documented here: the
 * event envelope is this context's data contract and serializing it is
 * domain logic; Jackson is a library, not a framework, and the instance is
 * injected (the same mapper Boot configures — one serialization policy).
 */
public class FireWebhookEvent {

    /** payload may be null (becomes {}); respond may be null (receiver default). */
    public record Request(String eventType, JsonNode payload, String respond) {
    }

    public static final Set<String> RESPOND_BEHAVIORS = Set.of("200", "500", "timeout");

    private final WebhookStore store;
    private final DeliveryEngine engine;
    private final ObjectMapper mapper;
    private final Clock clock;

    public FireWebhookEvent(WebhookStore store, DeliveryEngine engine, ObjectMapper mapper,
            Clock clock) {
        this.store = store;
        this.engine = engine;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Result<WebhookDelivery> execute(String subscriptionId, Request request) {
        WebhookSubscription subscription = store.findSubscription(subscriptionId).orElse(null);
        if (subscription == null) {
            return Result.failed(Failure.of(FailureKind.NOT_FOUND,
                    "no webhook subscription " + subscriptionId));
        }
        if (!subscription.active()) {
            return Result.failed(Failure.of(FailureKind.CONFLICT,
                    "subscription is paused — activate it before firing"));
        }
        if (request == null || !WebhookSubscription.isValidEventType(nullSafe(request.eventType()))) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "eventType is required, lower-case dot notation in past tense (e.g. invoice.paid)"));
        }
        String eventType = request.eventType().trim();
        if (!subscription.subscribesTo(eventType)) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "subscription " + subscription.id() + " listens to "
                            + subscription.eventTypes() + ", not " + eventType));
        }
        if (request.respond() != null && !RESPOND_BEHAVIORS.contains(request.respond())) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "respond must be one of " + RESPOND_BEHAVIORS));
        }

        String eventId = "evt_" + UUID.randomUUID();
        ObjectNode envelope = mapper.createObjectNode()
                .put("id", eventId)
                .put("type", eventType)
                .put("occurredAt", clock.instant().toString());
        envelope.set("data", request.payload() == null ? mapper.createObjectNode() : request.payload());
        String wireBytes = mapper.writeValueAsString(envelope);

        if (request.respond() != null) {
            store.scriptResponse(eventId, request.respond());
        }
        return Result.success(engine.fire(subscription, eventId, eventType, wireBytes));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
