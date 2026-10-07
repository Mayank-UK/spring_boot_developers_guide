package guide.presentation.demo.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The refresh island: issue → exchange (rotate) → the theft story. The
 * family view shows generations and the revoked flag — the demo's
 * observability surface for watching a lineage die.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefreshDemoTest {

    private static final String ISSUE = "/api/demo/auth/refresh/issue";
    private static final String EXCHANGE = "/api/demo/auth/refresh/exchange";
    private static final String FAMILIES = "/api/demo/auth/refresh/families";

    @Autowired
    private MockMvc mockMvc;

    private String issueFor(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post(ISSUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.refreshToken");
    }

    @Test
    void issuingMintsThePairAndStartsAFamily() throws Exception {
        MvcResult issued = mockMvc.perform(post(ISSUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"alice-demo-pass-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("issued"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.accessTokenExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.familyId").isNotEmpty())
                .andReturn();
        String refresh = JsonPath.read(issued.getResponse().getContentAsString(), "$.refreshToken");
        org.junit.jupiter.api.Assertions.assertTrue(refresh.startsWith("sdg_rt_"),
                "refresh tokens carry the sdg_rt_ prefix: " + refresh);
    }

    @Test
    void issuingRequiresValidCredentials() throws Exception {
        mockMvc.perform(post(ISSUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exchangeRotatesTheTokenAndKeepsTheFamily() throws Exception {
        String first = issueFor("bob", "bob-demo-pass-01");
        MvcResult exchanged = mockMvc.perform(post(EXCHANGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + first + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("rotated"))
                .andReturn();
        String body = exchanged.getResponse().getContentAsString();
        String second = JsonPath.read(body, "$.refreshToken");
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second);
        // the next generation exchanges cleanly — the family lives on
        mockMvc.perform(post(EXCHANGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + second + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("rotated"));
    }

    @Test
    void reusingASpentTokenRevokesTheWholeFamily() throws Exception {
        String first = issueFor("carol", "carol-demo-pass-01");
        MvcResult exchanged = mockMvc.perform(post(EXCHANGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + first + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String current = JsonPath.read(exchanged.getResponse().getContentAsString(), "$.refreshToken");

        // the replay: the SPENT token again — theft signal, family dies
        mockMvc.perform(post(EXCHANGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + first + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers
                        .containsString("family revoked")));

        // and the CURRENT token is dead with it — that is the point of family revocation
        mockMvc.perform(post(EXCHANGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + current + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers
                        .containsString("revoked")));
    }

    @Test
    void theFamiliesViewShowsGenerationsWithoutEverLeakingHashes() throws Exception {
        String first = issueFor("bob", "bob-demo-pass-01");
        mockMvc.perform(post(EXCHANGE)
                        .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + first + "\"}"))
                .andExpect(status().isOk());
        MvcResult view = mockMvc.perform(get(FAMILIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.families").isArray())
                .andReturn();
        String body = view.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("tokenHash"),
                "hashes never leave the store");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("rotated"),
                "spent generations are visible in the timeline");
    }

    @Test
    void anUnknownRefreshTokenIsAPlain401() throws Exception {
        mockMvc.perform(post(EXCHANGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"sdg_rt_never-issued\"}"))
                .andExpect(status().isUnauthorized());
    }
}
