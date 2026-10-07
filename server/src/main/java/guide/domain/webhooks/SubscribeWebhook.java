package guide.domain.webhooks;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.security.UrlAllowlist;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Use case: register a consumer endpoint — the provider half of every
 * webhook handshake ("point you at my URL, I'll POST events you asked
 * for"). Validation is the design lesson made mechanical: a real destination
 * (http/https — never file:, the SSRF floor), a strong shared secret
 * (≥16 chars), and event types that follow the past-tense naming contract.
 *
 * The created subscription's full secret is returned EXACTLY ONCE, in this
 * use case's output — after that, only the masked form exists outside the
 * signing code, exactly like every real provider's console.
 */
public class SubscribeWebhook {

    /** url may be null/blank — the caller's default (the loopback receiver) applies. */
    public record Request(String url, String secret, List<String> eventTypes) {
    }

    /** The one-time view: the subscription (masked) plus the secret, shown at creation only. */
    public record Created(String id, String url, String secretMasked, String secret,
            List<String> eventTypes, boolean active, String createdAt, boolean urlDefaulted) {
    }

    private final WebhookStore store;
    private final String defaultReceiverUrl;
    private final Clock clock;
    private final UrlAllowlist urlAllowlist;

    /**
     * Topic 25 (security) — FLAGGED SANCTIONED EDIT: the SSRF guard joined
     * the constructor (default-deny for private/metadata destinations, the
     * app's own loopback receiver explicitly allowlisted). Minimal change:
     * one collaborator, one verdict call below; everything else untouched.
     */
    public SubscribeWebhook(WebhookStore store, String defaultReceiverUrl, Clock clock,
            UrlAllowlist urlAllowlist) {
        this.store = store;
        this.defaultReceiverUrl = defaultReceiverUrl;
        this.clock = clock;
        this.urlAllowlist = urlAllowlist;
    }

    public Result<Created> execute(Request request) {
        if (request == null || isBlank(request.secret())) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "secret is required (≥" + WebhookSubscription.MIN_SECRET_LENGTH + " characters)"));
        }
        if (request.secret().length() < WebhookSubscription.MIN_SECRET_LENGTH) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "secret must be at least " + WebhookSubscription.MIN_SECRET_LENGTH
                            + " characters — signing secrets are credentials"));
        }
        Set<String> types = new LinkedHashSet<>();
        for (String raw : request.eventTypes() == null ? List.<String>of() : request.eventTypes()) {
            if (isBlank(raw)) {
                return Result.failed(Failure.of(FailureKind.VALIDATION,
                        "event types must not be blank"));
            }
            String eventType = raw.trim();
            if (!WebhookSubscription.isValidEventType(eventType)) {
                return Result.failed(Failure.of(FailureKind.VALIDATION,
                        "event type must be lower-case dot notation in past tense: " + raw
                                + " (e.g. invoice.paid)"));
            }
            types.add(eventType);
        }
        if (types.isEmpty()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "subscribe to at least one event type"));
        }
        boolean defaulted = isBlank(request.url());
        String url = defaulted ? defaultReceiverUrl : request.url().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "url must be http(s) — a webhook destination is an HTTP endpoint"));
        }
        try {
            URI.create(url).toASCIIString();
        } catch (IllegalArgumentException badUri) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "url is not a valid URI: " + url));
        }
        // Topic 25: the SSRF guard — the server will FETCH this url later, so
        // private ranges and metadata endpoints are refused at SUBSCRIPTION
        // time, not discovered at delivery time.
        UrlAllowlist.Verdict outbound = urlAllowlist.check(url);
        if (!outbound.allowed()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "url refused by the outbound allowlist (SSRF guard): " + outbound.reason()));
        }

        Instant now = clock.instant();
        WebhookSubscription subscription = new WebhookSubscription(
                "sub_" + UUID.randomUUID(), url, request.secret(), new ArrayList<>(types), true, now);
        store.saveSubscription(subscription);
        return Result.success(new Created(subscription.id(), subscription.url(),
                subscription.maskedSecret(), subscription.secret(),
                subscription.eventTypes(), subscription.active(),
                now.toString(), defaulted));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
