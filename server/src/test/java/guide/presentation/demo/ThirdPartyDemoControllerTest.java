package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.ThirdPartyDemoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the thirdparty:hub demo surfaces with the topic's own
 * wiring. Deterministic facts pinned: the report serves three
 * integrations with fake posture, idle outcomes, healthy quota and KEY
 * VERDICTS (never values — the verdict names the env var and says
 * not-set, and no field anywhere carries a key string); each trigger
 * runs its fake and answers the provider receipt plus the updated row
 * (mail sent with a demo id, declines as 200-values with the documented
 * decline codes, storage stored with the deterministic sha-256 etag);
 * the sandbox posture changes behavior observably (mail greylists
 * first contact, storage enforces the provider ceiling); the quota dial
 * demonstrates blast radius — mail exhausts to rate_limited while
 * payments keeps succeeding on the next request; and bad input is the
 * typed 400 envelope, never a 500. @BeforeEach reset keeps every test
 * independent (the hub is stateful and the slice context is shared).
 * Zero network by construction: every adapter is in-process.
 */
@WebMvcTest(ThirdPartyDemoController.class)
@Import(ThirdPartyDemoConfig.class)
class ThirdPartyDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetHub() throws Exception {
        mockMvc.perform(post("/api/demo/thirdparty/reset")).andExpect(status().isOk());
    }

    @Test
    void theHubReportServesThreeIntegrationsWithVerdictsNeverValues() throws Exception {
        mockMvc.perform(get("/api/demo/thirdparty/hub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integrations.length()").value(3))
                .andExpect(jsonPath("$.integrations[0].id").value("mail"))
                .andExpect(jsonPath("$.integrations[0].posture").value("fake"))
                .andExpect(jsonPath("$.integrations[0].lastOutcome").value("idle"))
                .andExpect(jsonPath("$.integrations[0].quotaLimit").value(8))
                .andExpect(jsonPath("$.integrations[0].quotaState").value("healthy"))
                .andExpect(jsonPath("$.integrations[1].id").value("payments"))
                .andExpect(jsonPath("$.integrations[2].id").value("storage"))
                .andExpect(jsonPath("$.integrations[2].keyEnvVar").value("GUIDE_THIRDPARTY_STORAGE_KEY"))
                .andExpect(jsonPath("$.integrations[2].keyPresentFromEnv").value(false))
                .andExpect(jsonPath("$.integrations[2].keyVerdict")
                        .value(org.hamcrest.Matchers.containsString("nothing to leak")))
                .andExpect(jsonPath("$.reading[2]")
                        .value(org.hamcrest.Matchers.containsString("verdicts only, never values")));
    }

    @Test
    void mailSendAnswersTheFakeReceiptAndTheUpdatedRow() throws Exception {
        mockMvc.perform(post("/api/demo/thirdparty/mail").param("to", "friend@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("sent"))
                .andExpect(jsonPath("$.messageId").value("demo-mail-1"))
                .andExpect(jsonPath("$.after.lastOutcome").value("sent"))
                .andExpect(jsonPath("$.after.callsUsed").value(1))
                .andExpect(jsonPath("$.after.quotaState").value("healthy"));
    }

    @Test
    void crlfInMailHeadersIsRejectedAtTheBoundary() throws Exception {
        mockMvc.perform(post("/api/demo/thirdparty/mail").param("to", "a@b.example")
                        .param("subject", "hi\r\nBcc: victim@example.com"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("header injection")));
    }

    @Test
    void declinesAreAnswersOn200sAndTestModeFlipsWithThePosture() throws Exception {
        mockMvc.perform(post("/api/demo/thirdparty/payments")
                        .param("token", "tok_chargeDeclined").param("amountCents", "1999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("declined"))
                .andExpect(jsonPath("$.declineCode").value("card_declined"))
                .andExpect(jsonPath("$.testMode").value(false))
                .andExpect(jsonPath("$.after.lastOutcome").value("declined"));

        mockMvc.perform(post("/api/demo/thirdparty/posture")
                        .param("integration", "payments").param("posture", "sandbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed")
                        .value(org.hamcrest.Matchers.containsString("sandbox")))
                .andExpect(jsonPath("$.after.posture").value("sandbox"));

        mockMvc.perform(post("/api/demo/thirdparty/payments")
                        .param("token", "tok_visa").param("amountCents", "1999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("succeeded"))
                .andExpect(jsonPath("$.testMode").value(true));
    }

    @Test
    void unknownTokensDeclineWithTheProviderShape() throws Exception {
        mockMvc.perform(post("/api/demo/thirdparty/payments")
                        .param("token", "tok_notReal").param("amountCents", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("declined"))
                .andExpect(jsonPath("$.declineCode").value("invalid_token"));
    }

    @Test
    void storagePutAnswersTheDeterministicEtagAndRejectsTraversalKeys() throws Exception {
        mockMvc.perform(post("/api/demo/thirdparty/storage")
                        .param("key", "guide/hello.txt").param("sizeBytes", "2048"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("stored"))
                .andExpect(jsonPath("$.etag").value("4261cbab88fc4db5"))
                .andExpect(jsonPath("$.bytes").value(2048))
                .andExpect(jsonPath("$.after.callsUsed").value(1));

        mockMvc.perform(post("/api/demo/thirdparty/storage")
                        .param("key", "guide/../etc/passwd").param("sizeBytes", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("path traversal")));
    }

    @Test
    void sandboxStorageEnforcesTheProviderCeilingAsAFailedReceipt() throws Exception {
        mockMvc.perform(post("/api/demo/thirdparty/posture")
                        .param("integration", "storage").param("posture", "sandbox"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/demo/thirdparty/storage")
                        .param("key", "guide/big.bin").param("sizeBytes", "20480"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("failed"))
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("EntityTooLarge")));
    }

    @Test
    void theQuotaDialDemonstratesBlastRadiusContainment() throws Exception {
        // use up mail's budget: dial to 1, send once (succeeds), send again (gate)
        mockMvc.perform(post("/api/demo/thirdparty/quota")
                        .param("integration", "mail").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed")
                        .value(org.hamcrest.Matchers.containsString("quota for mail is now 1")));

        mockMvc.perform(post("/api/demo/thirdparty/mail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("sent"));

        mockMvc.perform(post("/api/demo/thirdparty/mail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("rate_limited"))
                .andExpect(jsonPath("$.after.quotaState").value("exhausted"))
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("never made")));

        // THE POINT: the sibling keeps answering while mail is degraded
        mockMvc.perform(post("/api/demo/thirdparty/payments").param("token", "tok_visa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("succeeded"))
                .andExpect(jsonPath("$.after.quotaState").value("healthy"));
    }

    @Test
    void sandboxMailGreylistsFirstContactAndTheRetryDelivers() throws Exception {
        mockMvc.perform(post("/api/demo/thirdparty/posture")
                        .param("integration", "mail").param("posture", "sandbox"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/demo/thirdparty/mail").param("to", "newfriend@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("queued"))
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("421")));

        mockMvc.perform(post("/api/demo/thirdparty/mail").param("to", "newfriend@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("sent"));
    }

    @Test
    void badInputIsTheTypedValidationEnvelopeNeverA500() throws Exception {
        mockMvc.perform(post("/api/demo/thirdparty/quota")
                        .param("integration", "banana").param("limit", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("integration must be one of")));
        mockMvc.perform(post("/api/demo/thirdparty/quota")
                        .param("integration", "mail").param("limit", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("limit must be between")));
        mockMvc.perform(post("/api/demo/thirdparty/posture")
                        .param("integration", "mail").param("posture", "live"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("posture must be one of")));
        mockMvc.perform(post("/api/demo/thirdparty/payments").param("amountCents", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("amountCents must be between")));
        mockMvc.perform(post("/api/demo/thirdparty/storage").param("sizeBytes", "9999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("sizeBytes must be between")));
    }

    @Test
    void resetReturnsTheHubToConstructedState() throws Exception {
        mockMvc.perform(post("/api/demo/thirdparty/mail")).andExpect(status().isOk());
        mockMvc.perform(post("/api/demo/thirdparty/quota")
                        .param("integration", "mail").param("limit", "2")).andExpect(status().isOk());

        mockMvc.perform(post("/api/demo/thirdparty/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integrations[0].callsUsed").value(0))
                .andExpect(jsonPath("$.integrations[0].quotaLimit").value(8))
                .andExpect(jsonPath("$.integrations[0].posture").value("fake"))
                .andExpect(jsonPath("$.integrations[0].lastOutcome").value("idle"));
    }
}
