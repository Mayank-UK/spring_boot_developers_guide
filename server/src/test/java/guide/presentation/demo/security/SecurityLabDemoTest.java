package guide.presentation.demo.security;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The security lab against the REAL application context (full filter chain,
 * like the auth baseline tests): the CORS policy answers preflights the
 * chain receives (verdicts measured, not assumed), the rate limiter's
 * 429+Retry-After contract is exercised to exhaustion and through the reset
 * seam, and both probe routes are reachable through their respective
 * chains. Deterministic by construction: every rate-limit call uses this
 * class's own key and a one-hour window, so refill never rescues a bucket
 * mid-test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityLabDemoTest {

    private static final String RATE_LIMITED = "/api/demo/security/lab/ratelimited";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aPreflightFromTheAllowlistedOriginIsAnsweredByTheConfiguredPolicy() throws Exception {
        mockMvc.perform(options("/api/demo/security/lab/cors/target")
                        .header("Origin", "https://partner.demo.sdg.dev")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin",
                        "https://partner.demo.sdg.dev"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST"))
                .andExpect(header().string("Access-Control-Allow-Headers", "Content-Type"));
    }

    @Test
    void aPreflightFromAnUnlistedOriginIsRefusedByTheChain() throws Exception {
        mockMvc.perform(options("/api/demo/security/lab/cors/target")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void bothProbeRoutesAreReachableThroughTheirChains() throws Exception {
        mockMvc.perform(get("/api/demo/security/lab/probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chain").value("main (headers hardened — SecurityConfig)"));
        mockMvc.perform(get("/api/demo/security/lab/insecure/probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chain").value(
                        "insecure-comparison (protections deliberately relaxed)"));
    }

    @Test
    void theRateBudgetDepletesToA429WithRetryAfterAndTheEnvelope() throws Exception {
        String key = "mockmvc-lab-" + System.nanoTime();
        String url = RATE_LIMITED + "?key=" + key + "&limit=2&window=3600";

        mockMvc.perform(post(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.remainingTokens").value(1));
        mockMvc.perform(post(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingTokens").value(0));

        mockMvc.perform(post(url))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("rate_limited"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.retryAfterSeconds",
                        greaterThanOrEqualTo(1)));
    }

    @Test
    void theResetSeamRestoresTheBudgetForTestsAndTheDemo() throws Exception {
        String key = "mockmvc-reset-" + System.nanoTime();
        mockMvc.perform(post(RATE_LIMITED + "?key=" + key + "&limit=1&window=3600"))
                .andExpect(status().isOk());
        mockMvc.perform(post(RATE_LIMITED + "?key=" + key + "&limit=1&window=3600"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post(RATE_LIMITED + "/reset?key=" + key + "&limit=1&window=3600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value(true));

        mockMvc.perform(post(RATE_LIMITED + "?key=" + key + "&limit=1&window=3600"))
                .andExpect(status().isOk());
    }

    @Test
    void theStatusReadPeeksWithoutConsuming() throws Exception {
        String key = "mockmvc-peek-" + System.nanoTime();
        mockMvc.perform(get(RATE_LIMITED + "?key=" + key + "&limit=3&window=60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTokens").value(3))
                .andExpect(jsonPath("$.limit").value(3));
    }
}
