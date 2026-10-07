package guide.presentation.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.data.webhooks.InMemoryWebhookStore;
import guide.domain.webhooks.ReceiveWebhookDelivery;
import guide.domain.webhooks.SignatureExhibit;
import guide.domain.webhooks.WebhookStore;
import guide.domain.webhooks.WebhookSubscription;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * MockMvc over the loopback receiver with a FIXED clock and a counting
 * Delayer — the consumer's whole contract exercised as HTTP: signed body →
 * 200, bad signature → 401 (and a receipt that says why), stale timestamp →
 * 401, ?respond=500 → 500, the fire-time script honored, duplicates recorded,
 * and the tight ?toleranceSeconds= replay demo. No test sleeps: "timeout" is
 * proven by the delayer being called, not by waiting. DirtiesContext per
 * method because the simulator's store is the demo's memory, shared by design.
 */
@WebMvcTest(WebhookReceiverController.class)
@Import(WebhookReceiverControllerTest.FakeWiring.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WebhookReceiverControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final String SECRET = "whsec_receiver_slice_secret_1";
    private static final String RECEIVER_URL = "http://localhost:8080/api/demo/webhooks/receiver";
    private static final String BODY =
            "{\"id\":\"evt_slice\",\"type\":\"invoice.paid\",\"data\":{\"amount\":4200}}";

    /**
     * @TestConfiguration (not plain @Configuration): a nested @Configuration
     * would be detected as the slice's default configuration class and the
     * receiver's mappings would never register.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class FakeWiring {

        @Bean
        Clock webhookClock() {
            return Clock.fixed(NOW, ZoneId.of("UTC"));
        }

        @Bean
        WebhookStore webhookStore() {
            WebhookStore store = new InMemoryWebhookStore();
            store.saveSubscription(new WebhookSubscription("sub_slice", RECEIVER_URL, SECRET,
                    List.of("invoice.paid"), true, NOW));
            return store;
        }

        @Bean
        ObjectMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        AtomicInteger delayCalls() {
            return new AtomicInteger();
        }

        @Bean
        ReceiveWebhookDelivery receiveWebhookDelivery(WebhookStore webhookStore,
                ObjectMapper jsonMapper, AtomicInteger delayCalls) {
            return new ReceiveWebhookDelivery(webhookStore, jsonMapper, webhookClock(),
                    duration -> delayCalls.incrementAndGet());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebhookStore store;

    @Autowired
    private AtomicInteger delayCalls;

    private static String signed(String secret, String body, Instant timestamp) {
        return SignatureExhibit.signatureHeader(secret,
                String.valueOf(timestamp.getEpochSecond()), body);
    }

    @Test
    void signedDeliveryIsAcceptedWithImmediate2xx() throws Exception {
        mockMvc.perform(post("/api/demo/webhooks/receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SignatureExhibit.SIGNATURE_HEADER, signed(SECRET, BODY, NOW))
                        .header(SignatureExhibit.EVENT_HEADER, "invoice.paid")
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.eventId").value("evt_slice"))
                .andExpect(jsonPath("$.signature").value("verified"))
                .andExpect(jsonPath("$.duplicate").value(false));
        assertEquals(0, delayCalls.get());
    }

    @Test
    void wrongSecretSignatureIsA401AndTheReceiptExplainsWhy() throws Exception {
        mockMvc.perform(post("/api/demo/webhooks/receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SignatureExhibit.SIGNATURE_HEADER,
                                signed("whsec_someone_elses_secret_here", BODY, NOW))
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.signature").value("invalid"));
        org.junit.jupiter.api.Assertions.assertTrue(
                store.receipts().getFirst().signatureReason().contains("hmac mismatch"),
                "the receipt explains why: " + store.receipts().getFirst().signatureReason());
    }

    @Test
    void missingSignatureHeaderIsA401NotA500() throws Exception {
        mockMvc.perform(post("/api/demo/webhooks/receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.signatureReason", org.hamcrest.Matchers.containsString("missing")));
    }

    @Test
    void staleTimestampOutsideTheWindowIsRejected() throws Exception {
        String stale = signed(SECRET, BODY, NOW.minus(Duration.ofMinutes(6)));
        mockMvc.perform(post("/api/demo/webhooks/receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SignatureExhibit.SIGNATURE_HEADER, stale)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.signature").value("stale"));
    }

    @Test
    void tightToleranceTurnsARecentSignatureStale() throws Exception {
        String twoSecondsOld = signed(SECRET, BODY, NOW.minus(Duration.ofSeconds(2)));
        mockMvc.perform(post("/api/demo/webhooks/receiver?toleranceSeconds=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SignatureExhibit.SIGNATURE_HEADER, twoSecondsOld)
                        .content(BODY))
                // the replay demo in two seconds, not five minutes
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.signature").value("stale"));
        mockMvc.perform(post("/api/demo/webhooks/receiver?toleranceSeconds=5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SignatureExhibit.SIGNATURE_HEADER, twoSecondsOld)
                        .content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void respond500InjectsTheFailureTheSenderWillRetryOn() throws Exception {
        mockMvc.perform(post("/api/demo/webhooks/receiver?respond=500")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SignatureExhibit.SIGNATURE_HEADER, signed(SECRET, BODY, NOW))
                        .content(BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.accepted").value(false));
        assertEquals(500, store.receipts().getFirst().respondedStatus());
    }

    @Test
    void timeoutBehaviorDelaysOnceThenAnswers() throws Exception {
        mockMvc.perform(post("/api/demo/webhooks/receiver?respond=timeout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SignatureExhibit.SIGNATURE_HEADER, signed(SECRET, BODY, NOW))
                        .content(BODY))
                .andExpect(status().isOk());
        assertEquals(1, delayCalls.get());
    }

    @Test
    void fireTimeScriptAnswers500WithoutQueryParams() throws Exception {
        store.scriptResponse("evt_slice", "500");
        // the script the fire endpoint left for this event id, honored with no query params
        mockMvc.perform(post("/api/demo/webhooks/receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SignatureExhibit.SIGNATURE_HEADER, signed(SECRET, BODY, NOW))
                        .content(BODY))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void duplicateDeliveryIsFlaggedInTheReceiptsView() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/demo/webhooks/receiver")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(SignatureExhibit.SIGNATURE_HEADER, signed(SECRET, BODY, NOW))
                            .content(BODY))
                    .andExpect(status().isOk());
        }
        org.junit.jupiter.api.Assertions.assertTrue(store.receipts().getFirst().duplicate(),
                "the second receipt carries the at-least-once flag");
    }

    @Test
    void receiptsAreVisibleThroughTheConsoleEndpointShape() throws Exception {
        mockMvc.perform(post("/api/demo/webhooks/receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SignatureExhibit.SIGNATURE_HEADER, signed(SECRET, BODY, NOW))
                        .content(BODY))
                .andExpect(status().isOk());
        List<guide.domain.webhooks.WebhookReceipt> receipts = store.receipts();
        assertEquals(1, receipts.size());
        assertEquals("evt_slice", receipts.getFirst().eventId());
        assertEquals("verified", receipts.getFirst().signatureVerdict());
    }
}
