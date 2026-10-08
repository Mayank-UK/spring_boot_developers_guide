package guide.presentation.demo.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * The JWT island against the real chain — mint, validate, refuse. The
 * resource server (chain-configured) is the verifying half: a valid
 * Bearer authenticates the request, a TAMPERED token is rejected by the
 * signature check, and an EXPIRED one by the timestamp validator (this
 * app's decoder narrows nimbus's default 60s clock-skew to 1s — see
 * AuthDemoConfig — so ttlSeconds=1 dies within ~2s, which is what the
 * demo's expiry hook exists for).
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtDemoTest {

    private static final String TOKEN = "/api/demo/auth/jwt/token";
    private static final String PROTECTED = "/api/demo/auth/jwt/protected";

    @Autowired
    private MockMvc mockMvc;

    private String mintFor(String username, String password, Integer ttlSeconds) throws Exception {
        String body = ttlSeconds == null
                ? "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"
                : "{\"username\":\"" + username + "\",\"password\":\"" + password
                        + "\",\"ttlSeconds\":" + ttlSeconds + "}";
        MvcResult result = mockMvc.perform(post(TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    @Test
    void mintingReturnsTheTokenAndADecodedClaimsPreview() throws Exception {
        mockMvc.perform(post(TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"alice-demo-pass-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.decoded.sub").value("alice"))
                .andExpect(jsonPath("$.decoded.roles[0]").value("admin"))
                .andExpect(jsonPath("$.decoded.jti").isNotEmpty())
                .andExpect(jsonPath("$.decoded.exp").isNotEmpty());
    }

    @Test
    void badCredentialsAnswerTheEnvelopeNotAToken() throws Exception {
        mockMvc.perform(post(TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    @Test
    void aValidBearerTokenAuthenticatesTheProtectedResource() throws Exception {
        String token = mintFor("bob", "bob-demo-pass-01", null);
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("bob"))
                .andExpect(jsonPath("$.roles[0]").value("staff"))
                .andExpect(jsonPath("$.via").value("jwt"))
                .andExpect(jsonPath("$.jti").isNotEmpty());
    }

    @Test
    void aTamperedTokenIsRejectedByTheSignatureCheck() throws Exception {
        String token = mintFor("alice", "alice-demo-pass-01", null);
        // forge: flip one character in the MIDDLE of the payload segment. The
        // signature covers header + payload, so ANY payload byte change trips
        // the signature check — and a middle base64 char is fully significant
        // (only a segment's final char may carry ignored padding bits), so a
        // distinct char ALWAYS changes the decoded bytes. Flipping the
        // signature's own LAST char was a 1-in-16 coin flip: its low bits can
        // be ignored padding, decoding to the very same signature.
        String[] parts = token.split("\\.");
        int flipAt = parts[1].length() / 2;
        char original = parts[1].charAt(flipAt);
        char forged = original == 'A' ? 'B' : 'A';
        parts[1] = parts[1].substring(0, flipAt) + forged + parts[1].substring(flipAt + 1);
        String tampered = String.join(".", parts);
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers
                        .containsString("invalid_token")));
    }

    @Test
    void anExpiredTokenIsRejectedWithTheBearerErrorDetail() throws Exception {
        String token = mintFor("carol", "carol-demo-pass-01", 1);
        Thread.sleep(2_200); // 1s TTL + 1s tolerated skew, with margin
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers
                        .containsString("invalid_token")));
    }

    @Test
    void noBearerAtAllIsAPlainChallenge() throws Exception {
        mockMvc.perform(get(PROTECTED))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
    }
}
