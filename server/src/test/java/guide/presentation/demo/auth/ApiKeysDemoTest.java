package guide.presentation.demo.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * The API-key island against the real chain: issuance (password proof,
 * raw key shown ONCE), the redacted listing, the X-API-Key filter
 * authenticating a request into a principal (whoami), and revocation
 * killing the key. State is the shared demo substrate, so assertions are
 * RELATIVE (at least the key we just issued) — no context dirties.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiKeysDemoTest {

    private static final String KEYS = "/api/demo/auth/apikeys";

    @Autowired
    private MockMvc mockMvc;

    private String issueFor(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post(KEYS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.apiKey");
    }

    @Test
    void issuanceShowsTheRawKeyExactlyOnceWithThePrefixConvention() throws Exception {
        MvcResult issued = mockMvc.perform(post(KEYS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"alice-demo-pass-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andExpect(jsonPath("$.displayPrefix").isNotEmpty())
                .andExpect(jsonPath("$.username").value("alice"))
                .andReturn();
        String apiKey = JsonPath.read(issued.getResponse().getContentAsString(), "$.apiKey");
        org.junit.jupiter.api.Assertions.assertTrue(apiKey.startsWith("sdg_key_"),
                "issued keys carry the sdg_key_ prefix: " + apiKey);
    }

    @Test
    void issuanceRequiresTheAccountsPassword() throws Exception {
        mockMvc.perform(post(KEYS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    @Test
    void whoamiProvesTheKeyIdentifiesThePrincipal() throws Exception {
        String apiKey = issueFor("alice", "alice-demo-pass-01");
        mockMvc.perform(get(KEYS + "/whoami").header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.user.roles[0]").value("admin"))
                .andExpect(jsonPath("$.via").value("api-key"));
    }

    @Test
    void aWrongKeyIsIndistinguishableFromNoKey() throws Exception {
        // no oracle: unknown key and absent key answer the same 401
        mockMvc.perform(get(KEYS + "/whoami").header("X-API-Key", "sdg_key_not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theListingIsRedactedAndNeverCarriesKeyMaterial() throws Exception {
        String apiKey = issueFor("bob", "bob-demo-pass-01");
        MvcResult listing = mockMvc.perform(get(KEYS).header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andReturn();
        String body = listing.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains(apiKey),
                "the listing must never echo raw key material");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("sdg_key_"),
                "the listing shows the display prefixes");
    }

    @Test
    void revocationKillsTheKeyImmediately() throws Exception {
        // NOTE: the identity substrate is SHARED across the suite's contexts, so the
        // test drives only the key IT issued (the listing is insertion-ordered, not
        // newest-first) — relative assertions, no context dirties
        MvcResult issued = mockMvc.perform(post(KEYS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"carol-demo-pass-01\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String apiKey = JsonPath.read(issued.getResponse().getContentAsString(), "$.apiKey");
        String id = JsonPath.read(issued.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(delete(KEYS + "/" + id).header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));

        mockMvc.perform(get(KEYS + "/whoami").header("X-API-Key", apiKey))
                .andExpect(status().isUnauthorized());
    }
}
