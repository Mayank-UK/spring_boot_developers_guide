package guide.config;

import guide.data.webhooks.HttpDeliveryAdapter;
import guide.data.webhooks.InMemoryWebhookStore;
import guide.data.webhooks.WebhookDeliveryTicker;
import guide.domain.security.UrlAllowlist;
import guide.domain.webhooks.DeliveryEngine;
import guide.domain.webhooks.FireWebhookEvent;
import guide.domain.webhooks.HttpDeliveryPort;
import guide.domain.webhooks.ListWebhookDeliveries;
import guide.domain.webhooks.ListWebhookReceipts;
import guide.domain.webhooks.ListWebhookSubscriptions;
import guide.domain.webhooks.ReceiveWebhookDelivery;
import guide.domain.webhooks.SubscribeWebhook;
import guide.domain.webhooks.WebhookStore;
import java.time.Clock;
import java.util.Random;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Demo wiring for topic 14 (webhooks) — the whole simulator as explicit
 * beans, ports to adapters, constructor injection throughout. Notable
 * choices:
 *
 * - {@code @EnableScheduling} USED to live here — topic 29 (background
 *   jobs) relocated it to JobsDemoConfig, which owns time-driven work.
 *   The annotation is idempotent and global, so this ticker still beats
 *   exactly as before; the suite proves it.
 * - The Clock is not a bean — the production wiring wants system UTC and the
 *   classes take Clock in their constructors, which is the seam tests use
 *   with fakes. No bean-collision surface for sibling topics.
 * - The ObjectMapper IS a bean parameter: the simulator serializes with the
 *   SAME mapper Boot configured for the rest of the app — one serialization
 *   policy, no second opinion about what a date looks like.
 * - The default subscription URL targets the app's own loopback receiver on
 *   {@code local.server.port} (8080 fallback under slices) — the local-dev
 *   story: sender and consumer in one process, no tunnel required.
 */
@Configuration
public class WebhooksDemoConfig {

    @Bean
    WebhookStore webhookStore() {
        return new InMemoryWebhookStore();
    }

    @Bean
    HttpDeliveryPort httpDeliveryPort() {
        return new HttpDeliveryAdapter(Clock.systemUTC());
    }

    @Bean
    DeliveryEngine deliveryEngine(WebhookStore webhookStore, HttpDeliveryPort httpDeliveryPort) {
        return new DeliveryEngine(webhookStore, httpDeliveryPort, Clock.systemUTC(),
                new Random()::nextDouble);
    }

    @Bean
    WebhookDeliveryTicker webhookDeliveryTicker(DeliveryEngine deliveryEngine) {
        return new WebhookDeliveryTicker(deliveryEngine, Clock.systemUTC());
    }

    @Bean
    SubscribeWebhook subscribeWebhook(WebhookStore webhookStore,
            @Value("${local.server.port:8080}") int localPort) {
        // Topic 25 (security) — FLAGGED SANCTIONED EDIT: subscriptions now cross
        // the SSRF guard (UrlAllowlist). The policy allows public destinations
        // plus the app's own loopback receiver — the one private target this
        // demo legitimately calls.
        return new SubscribeWebhook(webhookStore,
                "http://localhost:" + localPort + "/api/demo/webhooks/receiver", Clock.systemUTC(),
                UrlAllowlist.forLocalDevelopment());
    }

    @Bean
    FireWebhookEvent fireWebhookEvent(WebhookStore webhookStore, DeliveryEngine deliveryEngine,
            ObjectMapper objectMapper) {
        return new FireWebhookEvent(webhookStore, deliveryEngine, objectMapper, Clock.systemUTC());
    }

    @Bean
    ListWebhookSubscriptions listWebhookSubscriptions(WebhookStore webhookStore) {
        return new ListWebhookSubscriptions(webhookStore);
    }

    @Bean
    ListWebhookDeliveries listWebhookDeliveries(WebhookStore webhookStore) {
        return new ListWebhookDeliveries(webhookStore);
    }

    @Bean
    ListWebhookReceipts listWebhookReceipts(WebhookStore webhookStore) {
        return new ListWebhookReceipts(webhookStore);
    }

    @Bean
    ReceiveWebhookDelivery receiveWebhookDelivery(WebhookStore webhookStore,
            ObjectMapper objectMapper) {
        return new ReceiveWebhookDelivery(webhookStore, objectMapper, Clock.systemUTC(),
            duration -> {
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
    }
}
