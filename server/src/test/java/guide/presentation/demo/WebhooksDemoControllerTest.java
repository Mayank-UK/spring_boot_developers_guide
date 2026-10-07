package guide.presentation.demo;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.data.webhooks.InMemoryWebhookStore;
import guide.domain.webhooks.DeliveryEngine;
import guide.domain.webhooks.FireWebhookEvent;
import guide.domain.webhooks.HttpDeliveryPort;
import guide.domain.webhooks.ListWebhookDeliveries;
import guide.domain.webhooks.ListWebhookReceipts;
import guide.domain.webhooks.ListWebhookSubscriptions;
import guide.domain.webhooks.SubscribeWebhook;
import guide.domain.webhooks.WebhookStore;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * MockMvc over the sender's console with the REAL use cases and engine, but
 * a FAKE delivery port and NO scheduler: firing is deterministic (the
 * delivery sits pending until a tick would run — in production the ticker,
 * in the engine's own tests a hand-driven clock). This is the
 * deterministic-seam pattern the topic teaches about its own demo.
 * DirtiesContext per method: the simulator's store is per-console state.
 */
@WebMvcTest(WebhooksDemoController.class)
@Import(WebhooksDemoControllerTest.FakeWiring.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WebhooksDemoControllerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final String SECRET = "whsec_controller_test_secret_01";

    /**
     * The deterministic wiring: real domain, fake transport, no scheduling.
     * @TestConfiguration (not plain @Configuration): a nested @Configuration
     * would be detected as the test's DEFAULT configuration class and hijack
     * the slice bootstrap — every mapping 404s as a static resource.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class FakeWiring {

        @Bean
        Clock webhookClock() {
            return Clock.fixed(FIXED_NOW, java.time.ZoneId.of("UTC"));
        }

        @Bean
        WebhookStore webhookStore() {
            return new InMemoryWebhookStore();
        }

        /** Always-failing port with a call counter — fire tests assert it was NEVER called. */
        @Bean
        HttpDeliveryPort httpDeliveryPort(AtomicInteger deliveryCalls) {
            return (subscription, eventType, body) -> {
                deliveryCalls.incrementAndGet();
                return new HttpDeliveryPort.AttemptOutcome(false, 500, "t=1,v1=fake");
            };
        }

        @Bean
        AtomicInteger deliveryCalls() {
            return new AtomicInteger();
        }

        @Bean
        DeliveryEngine deliveryEngine(WebhookStore webhookStore, HttpDeliveryPort httpDeliveryPort,
                Clock webhookClock) {
            return new DeliveryEngine(webhookStore, httpDeliveryPort, webhookClock, () -> 0.0);
        }

        @Bean
        SubscribeWebhook subscribeWebhook(WebhookStore webhookStore, Clock webhookClock) {
            // Topic 25 (security) — FLAGGED SANCTIONED EDIT: the SSRF guard's
            // constructor param (loopback receiver allowlisted, as in production wiring).
            return new SubscribeWebhook(webhookStore,
                    "http://localhost:8080/api/demo/webhooks/receiver", webhookClock,
                    guide.domain.security.UrlAllowlist.forLocalDevelopment());
        }

        @Bean
        FireWebhookEvent fireWebhookEvent(WebhookStore webhookStore, DeliveryEngine deliveryEngine,
                Clock webhookClock) {
            return new FireWebhookEvent(webhookStore, deliveryEngine, jsonMapper(), webhookClock);
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
        ObjectMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AtomicInteger deliveryCalls;

    private String subscribe() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/demo/webhooks/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"" + SECRET + "\",\"eventTypes\":[\"invoice.paid\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void subscribeDefaultsTheUrlToTheLoopbackReceiverAndShowsTheSecretOnce() throws Exception {
        mockMvc.perform(post("/api/demo/webhooks/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"" + SECRET + "\",\"eventTypes\":[\"invoice.paid\",\"order.fulfilled\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urlDefaulted").value(true))
                .andExpect(jsonPath("$.url",
                        containsString("/api/demo/webhooks/receiver")))
                .andExpect(jsonPath("$.secret").value(SECRET))
                .andExpect(jsonPath("$.secretMasked", containsString("…")))
                .andExpect(jsonPath("$.eventTypes.length()").value(2))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void weakSecretsAndBadEventNamesAreValidationFailures() throws Exception {
        mockMvc.perform(post("/api/demo/webhooks/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"short\",\"eventTypes\":[\"invoice.paid\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
        mockMvc.perform(post("/api/demo/webhooks/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"" + SECRET + "\",\"eventTypes\":[\"CreateInvoice\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("past tense")));
    }

    @Test
    void listingMasksTheSecretEverywhereButCreation() throws Exception {
        subscribe();
        MvcResult listing = mockMvc.perform(get("/api/demo/webhooks/subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.length()").value(1))
                .andExpect(jsonPath("$.subscriptions[0].secretMasked", containsString("…")))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(
                listing.getResponse().getContentAsString().contains(SECRET),
                "the listing must never echo the secret");
    }

    @Test
    void fireBuildsTheEnvelopeAndLeavesTheDeliveryPending() throws Exception {
        String subscriptionId = subscribe();
        MvcResult fired = mockMvc.perform(post("/api/demo/webhooks/subscriptions/" + subscriptionId + "/fire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"invoice.paid\",\"payload\":{\"amount\":4200}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("pending"))
                .andExpect(jsonPath("$.attempts.length()").value(0))
                .andExpect(jsonPath("$.attemptsLeft").value(5))
                .andReturn();
        String body = fired.getResponse().getContentAsString();
        // $.payload is the envelope JSON as a string — decode it, then judge its shape
        String payload = com.jayway.jsonpath.JsonPath.read(body, "$.payload");
        org.junit.jupiter.api.Assertions.assertTrue(payload.contains("\"occurredAt\"")
                        && payload.contains("\"data\"") && payload.contains("\"type\":\"invoice.paid\"")
                        && payload.contains("\"evt_"),
                "the payload IS the envelope: " + payload);
        org.junit.jupiter.api.Assertions.assertEquals(0, deliveryCalls.get(),
                "no transport happens without a tick — deterministic in the slice");
    }

    @Test
    void deliveriesTimelineShowsTheFiredEvent() throws Exception {
        String subscriptionId = subscribe();
        mockMvc.perform(post("/api/demo/webhooks/subscriptions/" + subscriptionId + "/fire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"invoice.paid\",\"payload\":{}}"));
        mockMvc.perform(get("/api/demo/webhooks/subscriptions/" + subscriptionId + "/deliveries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionId").value(subscriptionId))
                .andExpect(jsonPath("$.deliveries.length()").value(1))
                .andExpect(jsonPath("$.deliveries[0].state").value("pending"))
                .andExpect(jsonPath("$.deliveries[0].nextAttemptAt").isNotEmpty());
    }

    @Test
    void firingAnUnsubscribedEventTypeIsRejected() throws Exception {
        String subscriptionId = subscribe();
        mockMvc.perform(post("/api/demo/webhooks/subscriptions/" + subscriptionId + "/fire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"customer.deleted\",\"payload\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("listens to")));
    }

    @Test
    void unknownSubscriptionIsANotFoundEnvelope() throws Exception {
        mockMvc.perform(post("/api/demo/webhooks/subscriptions/sub_missing/fire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"invoice.paid\",\"payload\":{}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void invalidRespondDirectiveIsRejectedUpFront() throws Exception {
        String subscriptionId = subscribe();
        mockMvc.perform(post("/api/demo/webhooks/subscriptions/" + subscriptionId + "/fire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"invoice.paid\",\"payload\":{},\"respond\":\"teapot\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("respond")));
    }

    @Test
    void receiptsStartEmptyAndDeliveriesNeverLeakTheSecret() throws Exception {
        mockMvc.perform(get("/api/demo/webhooks/receipts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipts.length()").value(0));
        String subscriptionId = subscribe();
        MvcResult timeline = mockMvc.perform(post("/api/demo/webhooks/subscriptions/" + subscriptionId + "/fire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"invoice.paid\",\"payload\":{}}"))
                .andExpect(status().isOk())
                .andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(
                timeline.getResponse().getContentAsString().contains(SECRET),
                "delivery views carry signatures, never secrets");
    }
}
