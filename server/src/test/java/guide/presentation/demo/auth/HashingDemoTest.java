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
 * The PasswordEncoder lab: hash format, cost parameter, and the timing
 * evidence — the verification of the CORRECT password and of a WRONG
 * one cost the same order of magnitude because bcrypt always runs its
 * full schedule. Timing assertions are deliberately LOOSE (a CI box
 * shares cores); the claim taught is "roughly equal", not "equal".
 */
@SpringBootTest
@AutoConfigureMockMvc
class HashingDemoTest {

    private static final String HASH = "/api/demo/auth/hashing/hash";
    private static final String VERIFY = "/api/demo/auth/hashing/verify";
    private static final String COMPARE = "/api/demo/auth/hashing/compare";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void hashingProducesTheCanonicalBcryptFormatAtTheChosenCost() throws Exception {
        MvcResult result = mockMvc.perform(post(HASH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"correct horse battery staple\",\"cost\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("bcrypt"))
                .andExpect(jsonPath("$.cost").value(10))
                .andExpect(jsonPath("$.elapsedMs").isNumber())
                .andReturn();
        String hash = JsonPath.read(result.getResponse().getContentAsString(), "$.hash");
        org.junit.jupiter.api.Assertions.assertEquals(60, hash.length(),
                "bcrypt hashes are exactly 60 chars ($2a$10$ + 22 salt + 31 hash): " + hash);
        org.junit.jupiter.api.Assertions.assertTrue(hash.startsWith("$2a$10$"),
                "version and cost are the leading segments: " + hash);
    }

    @Test
    void theSamePasswordHashesDifferentlyEveryTime() throws Exception {
        String first = hash("sdg-same-password");
        String second = hash("sdg-same-password");
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second,
                "per-hash salts mean identical passwords never collide");
    }

    @Test
    void verifyAnswersMatchesAndTheTimingEvidence() throws Exception {
        String hash = hash("sdg-timing-password");
        double correctMs = verifyMs("sdg-timing-password", hash, true);
        double wrongMs = verifyMs("sdg-timing-passwork", hash, false);
        // both must take REAL work (bcrypt cost 10) — a fast verify would mean
        // something other than bcrypt answered
        org.junit.jupiter.api.Assertions.assertTrue(correctMs >= 5.0,
                "cost-10 verify takes tens of ms, got " + correctMs);
        // and roughly the SAME work: the wrong password runs the full schedule too
        double ratio = Math.max(correctMs, wrongMs) / Math.max(1.0, Math.min(correctMs, wrongMs));
        org.junit.jupiter.api.Assertions.assertTrue(ratio <= 4.0,
                "correct vs wrong verify within 4x (both run the full schedule): "
                        + correctMs + "ms vs " + wrongMs + "ms");
    }

    @Test
    void compareDissectsTheFormatIntoItsNamedParts() throws Exception {
        String hash = hash("sdg-anatomy-password");
        mockMvc.perform(get(COMPARE).param("hash", hash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("bcrypt"))
                .andExpect(jsonPath("$.version").value("2a"))
                .andExpect(jsonPath("$.cost").value(10))
                .andExpect(jsonPath("$.salt").isNotEmpty())
                .andExpect(jsonPath("$.hashBody").isNotEmpty())
                .andExpect(jsonPath("$.notes").isArray());
    }

    @Test
    void aNonBcryptHashIsRejectedByTheLabNotSwallowed() throws Exception {
        mockMvc.perform(get(COMPARE).param("hash", "md5-looking-not-bcrypt"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(VERIFY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"x\",\"hash\":\" plaintext \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    private String hash(String password) throws Exception {
        MvcResult result = mockMvc.perform(post(HASH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.hash");
    }

    private double verifyMs(String password, String hash, boolean expectMatch) throws Exception {
        MvcResult result = mockMvc.perform(post(VERIFY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + password + "\",\"hash\":\"" + hash + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches").value(expectMatch))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.elapsedMs");
    }
}
