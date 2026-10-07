package guide.domain.webhooks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Topic 14's sender-side aggregate: one consumer endpoint this app will POST
 * events to. A subscription is exactly what a real provider's developer
 * console hands you — a destination URL, a shared signing secret, the event
 * types you care about, and an on/off switch (the "pause deliveries" lever
 * every provider has, because it is how you survive a consumer outage
 * without losing events).
 *
 * The event-type naming rule is enforced here because it is the topic's
 * design lesson in code: types are lower-case, dot-separated, and read as
 * FACTS IN PAST TENSE ("invoice.paid", not "invoice.payment" and never
 * "createInvoice") — an event announces something that already happened, so
 * consumers can never be asked to validate a command that might not occur.
 */
public record WebhookSubscription(
        String id,
        String url,
        String secret,
        List<String> eventTypes,
        boolean active,
        Instant createdAt) {

    /** The shared-secret floor — a signing secret shorter than this is teaching bad habits. */
    public static final int MIN_SECRET_LENGTH = 16;

    /** lower-case dot.notation, segments may contain digits and hyphens: invoice.paid, order.fulfilled-early */
    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*(\\.[a-z0-9]+(-[a-z0-9]+)*)*");

    public WebhookSubscription {
        requireNonBlank(id, "id");
        requireNonBlank(url, "url");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("subscription url must be http(s): " + url);
        }
        requireNonBlank(secret, "secret");
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    "secret must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        Objects.requireNonNull(eventTypes, "eventTypes");
        if (eventTypes.isEmpty()) {
            throw new IllegalArgumentException("subscription needs at least one event type");
        }
        for (String eventType : eventTypes) {
            if (!isValidEventType(eventType)) {
                throw new IllegalArgumentException(
                        "event type must be lower-case dot notation, past tense: " + eventType);
            }
        }
        eventTypes = List.copyOf(eventTypes);
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Does this subscription want this event type? The sender's fan-out filter. */
    public boolean subscribesTo(String eventType) {
        return eventTypes.contains(eventType);
    }

    /**
     * The only form of the secret any listing may show: a prefix and the last
     * two characters, exactly like every real provider's console. The full
     * value leaves the system exactly once — the subscribe response — and
     * after that it exists only to sign.
     */
    public String maskedSecret() {
        return "whsec_…" + secret.substring(secret.length() - 2);
    }

    /** The naming contract both subscribe and fire enforce — one rule, one place. */
    public static boolean isValidEventType(String eventType) {
        return eventType != null && EVENT_TYPE.matcher(eventType).matches();
    }

    private static void requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("subscription " + what + " must not be blank");
        }
    }
}
