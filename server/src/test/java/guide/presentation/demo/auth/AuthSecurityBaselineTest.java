package guide.presentation.demo.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The security baseline, tested against the REAL application context —
 * the whole point of this class: the filter chain from
 * guide/config/SecurityConfig runs on every request (full context +
 * auto-configured MockMvc, the @WebMvcTest slices deliberately bypass
 * it). What is executable here: the guide stays OPEN (the deliberate
 * teaching inversion), and the auth island is the ONLY protected
 * surface — anonymous requests to its guarded endpoints are refused by
 * the chain with 401 before any controller runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthSecurityBaselineTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theGuideStaysOpenContentHealthAndDemoSurfacesAnswerAnonymous() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/content")).andExpect(status().isOk());
        mockMvc.perform(get("/api/demo/middleware/pipeline")).andExpect(status().isOk());
    }

    @Test
    void theAuthIslandRefusesAnonymousRequestsToItsGuardedEndpoints() throws Exception {
        mockMvc.perform(get("/api/demo/auth/session/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/demo/auth/apikeys/whoami"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/demo/auth/jwt/protected"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theIslandsOpenEndpointsStayReachableAnonymous() throws Exception {
        // login/token/authorize prove themselves with credentials in the body
        mockMvc.perform(get("/api/demo/auth/oauth/clients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clients[0].clientId").value("sdg-console"));
        mockMvc.perform(get("/api/demo/auth/hashing/compare?hash=not-a-hash"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnroutedIslandPathIsARoutingConcernNotAnAuthenticationOne() throws Exception {
        // the permitAll fallthrough means an unrouted path never meets the chain's
        // 401 — it falls through to the app's unrouted-path handling (topic 30's
        // to-fix), which today renders via the catch-all. What is pinned here is
        // the SECURITY claim: not an authentication refusal.
        mockMvc.perform(get("/api/demo/auth/does-not-exist"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().is(org.hamcrest.Matchers.not(401)));
    }
}
