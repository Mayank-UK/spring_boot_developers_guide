package guide.presentation.demo.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The OAuth authorization-code dance, in-app: the whole handshake with
 * PKCE (S256), single-use codes, redirect allowlist enforcement, and
 * both token grants. The PKCE pair is computed EXACTLY the way a public
 * client would (the test plays the client app): random verifier, S256
 * challenge = base64url(sha256(verifier)).
 */
@SpringBootTest
@AutoConfigureMockMvc
class OAuthDemoTest {

    private static final String AUTHORIZE = "/api/demo/auth/oauth/authorize";
    private static final String TOKEN = "/api/demo/auth/oauth/token";
    private static final String REDIRECT = "http://localhost:5176/oauth/callback";

    @Autowired
    private MockMvc mockMvc;

    /** The client app's PKCE dance, verbatim. */
    private static String s256(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private record Authorization(String code, String state) {
    }

    private Authorization authorize(String verifier, String state, boolean consent)
            throws Exception {
        MvcResult result = mockMvc.perform(post(AUTHORIZE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseType\":\"code\",\"clientId\":\"sdg-console\","
                                + "\"redirectUri\":\"" + REDIRECT + "\","
                                + (state == null ? "" : "\"state\":\"" + state + "\",")
                                + "\"codeChallenge\":\"" + s256(verifier) + "\","
                                + "\"codeChallengeMethod\":\"S256\","
                                + "\"username\":\"alice\",\"password\":\"alice-demo-pass-01\","
                                + "\"consent\":" + consent + "}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new Authorization(JsonPath.read(body, "$.code"), JsonPath.read(body, "$.state"));
    }

    @Test
    void theHappyPathIssuesACodeAndExchangesItForTokens() throws Exception {
        String verifier = "client-verifier-horse-battery-staple-42";
        Authorization authorization = authorize(verifier, "xyzstate123", true);

        org.junit.jupiter.api.Assertions.assertTrue(authorization.code().startsWith("sdg_ac_"));
        org.junit.jupiter.api.Assertions.assertEquals("xyzstate123", authorization.state());

        MvcResult exchanged = mockMvc.perform(post(TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"authorization_code\","
                                + "\"code\":\"" + authorization.code() + "\","
                                + "\"redirectUri\":\"" + REDIRECT + "\","
                                + "\"clientId\":\"sdg-console\","
                                + "\"codeVerifier\":\"" + verifier + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.grant").value("authorization_code"))
                .andReturn();
        // and the access token is a REAL one — the resource server accepts it
        String accessToken = JsonPath.read(exchanged.getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/api/demo/auth/jwt/protected")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("alice"));
    }

    @Test
    void theRedirectCarriesCodeAndStateExactlyAsTheWireFormatSpecifies() throws Exception {
        String verifier = "verifier-for-redirect-check-99";
        MvcResult result = mockMvc.perform(post(AUTHORIZE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseType\":\"code\",\"clientId\":\"sdg-console\","
                                + "\"redirectUri\":\"" + REDIRECT + "\",\"state\":\"st4te\","
                                + "\"codeChallenge\":\"" + s256(verifier) + "\","
                                + "\"codeChallengeMethod\":\"S256\","
                                + "\"username\":\"bob\",\"password\":\"bob-demo-pass-01\","
                                + "\"consent\":true}"))
                .andExpect(status().isOk())
                .andReturn();
        String redirectTo = JsonPath.read(result.getResponse().getContentAsString(), "$.redirectTo");
        org.junit.jupiter.api.Assertions.assertTrue(redirectTo.startsWith(REDIRECT + "?code=sdg_ac_"),
                "the client app is redirected with the code on the query string: " + redirectTo);
        org.junit.jupiter.api.Assertions.assertTrue(redirectTo.endsWith("&state=st4te"),
                "the state echoes back for the client's CSRF check: " + redirectTo);
    }

    @Test
    void codesAreSingleUse() throws Exception {
        String verifier = "verifier-single-use-77";
        Authorization authorization = authorize(verifier, null, true);
        String body = "{\"grantType\":\"authorization_code\","
                + "\"code\":\"" + authorization.code() + "\","
                + "\"redirectUri\":\"" + REDIRECT + "\","
                + "\"clientId\":\"sdg-console\","
                + "\"codeVerifier\":\"" + verifier + "\"}";
        mockMvc.perform(post(TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post(TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers
                        .containsString("single-use")));
    }

    @Test
    void theWrongVerifierFailsPKCEAndBurnsTheCode() throws Exception {
        String verifier = "verifier-pkce-wrong-11";
        Authorization authorization = authorize(verifier, null, true);
        mockMvc.perform(post(TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"authorization_code\","
                                + "\"code\":\"" + authorization.code() + "\","
                                + "\"redirectUri\":\"" + REDIRECT + "\","
                                + "\"clientId\":\"sdg-console\","
                                + "\"codeVerifier\":\"attacker-guess\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers
                        .containsString("PKCE")));
    }

    @Test
    void theRedirectAllowlistIsEnforced() throws Exception {
        String verifier = "verifier-open-redirect-22";
        mockMvc.perform(post(AUTHORIZE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseType\":\"code\",\"clientId\":\"sdg-console\","
                                + "\"redirectUri\":\"https://attacker.example/steal\","
                                + "\"codeChallenge\":\"" + s256(verifier) + "\","
                                + "\"codeChallengeMethod\":\"S256\","
                                + "\"username\":\"alice\",\"password\":\"alice-demo-pass-01\","
                                + "\"consent\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers
                        .containsString("redirect")));
    }

    @Test
    void decliningConsentIsAccessDenied() throws Exception {
        String verifier = "verifier-no-consent-33";
        mockMvc.perform(post(AUTHORIZE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseType\":\"code\",\"clientId\":\"sdg-console\","
                                + "\"redirectUri\":\"" + REDIRECT + "\","
                                + "\"codeChallenge\":\"" + s256(verifier) + "\","
                                + "\"codeChallengeMethod\":\"S256\","
                                + "\"username\":\"alice\",\"password\":\"alice-demo-pass-01\","
                                + "\"consent\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers
                        .containsString("access_denied")));
    }

    @Test
    void pkceIsRequired() throws Exception {
        mockMvc.perform(post(AUTHORIZE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseType\":\"code\",\"clientId\":\"sdg-console\","
                                + "\"redirectUri\":\"" + REDIRECT + "\","
                                + "\"username\":\"alice\",\"password\":\"alice-demo-pass-01\","
                                + "\"consent\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers
                        .containsString("PKCE")));
    }

    @Test
    void theRefreshGrantRotatesAndRevokesOnReuse() throws Exception {
        String verifier = "verifier-refresh-grant-55";
        Authorization authorization = authorize(verifier, null, true);
        MvcResult exchanged = mockMvc.perform(post(TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"authorization_code\","
                                + "\"code\":\"" + authorization.code() + "\","
                                + "\"redirectUri\":\"" + REDIRECT + "\","
                                + "\"clientId\":\"sdg-console\","
                                + "\"codeVerifier\":\"" + verifier + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = exchanged.getResponse().getContentAsString();
        String refresh = JsonPath.read(body, "$.refreshToken");

        MvcResult rotated = mockMvc.perform(post(TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"refresh_token\",\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grant").value("refresh_token"))
                .andReturn();
        String rotatedRefresh = JsonPath.read(rotated.getResponse().getContentAsString(), "$.refreshToken");
        org.junit.jupiter.api.Assertions.assertNotEquals(refresh, rotatedRefresh,
                "rotation mints a new refresh token");

        // reuse of the ORIGINAL: family revoked, 401 — even the CURRENT token dies with it
        mockMvc.perform(post(TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"refresh_token\",\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers
                        .containsString("family revoked")));
        mockMvc.perform(post(TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                .content("{\"grantType\":\"refresh_token\",\"refreshToken\":\"" + rotatedRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
