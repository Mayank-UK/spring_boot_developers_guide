package guide.data.webhooks;

import guide.domain.webhooks.HttpDeliveryPort;
import guide.domain.webhooks.SignatureExhibit;
import guide.domain.webhooks.WebhookSubscription;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Adapter: the engine's one network call, via Spring's built-in
 * {@link RestClient}. The adapter is deliberately THIN — it knows transport
 * and headers, nothing about retries or policy — but the headers ARE the
 * topic: every POST carries a freshly-computed
 * {@code X-SDG-Signature: t=<now>,v1=<hmac>} (fresh timestamp per attempt,
 * so every attempt's signature is unique and replay of an old one dies
 * against the window) plus {@code X-SDG-Event} so the consumer can route
 * without parsing the body at all.
 *
 * Timeouts are set, not inherited: connect 750ms, read 2s — because an
 * unbounded HTTP client inside a retry loop is how one slow consumer takes
 * the whole sender down (the reliability topic's first rule, applied here).
 * exchange() is used instead of retrieve() so a 500 is DATA the engine
 * schedules around, not an exception the adapter translates.
 */
public final class HttpDeliveryAdapter implements HttpDeliveryPort {

    private final RestClient client;
    private final Clock clock;

    public HttpDeliveryAdapter(Clock clock) {
        this(defaultClient(), clock);
    }

    /** Test seam: a prebuilt client (or aRecording fake's bypass) with the clock still real-or-fake. */
    HttpDeliveryAdapter(RestClient client, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AttemptOutcome deliver(WebhookSubscription subscription, String eventType, String body) {
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        String signature = SignatureExhibit.signatureHeader(subscription.secret(), timestamp, body);
        Integer status;
        try {
            status = client.post()
                    .uri(URI.create(subscription.url()))
                    .header(SignatureExhibit.SIGNATURE_HEADER, signature)
                    .header(SignatureExhibit.EVENT_HEADER, eventType)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode().value());
        } catch (RuntimeException transport) {
            // refused, reset, timed out — no HTTP response exists to report
            return new AttemptOutcome(false, null, signature);
        }
        return new AttemptOutcome(status != null && status >= 200 && status < 300, status, signature);
    }

    private static RestClient defaultClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(750).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(2).toMillis());
        return RestClient.builder().requestFactory(factory).build();
    }
}
