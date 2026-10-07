package guide.domain.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Opaque token material: how API keys, refresh tokens and authorization
 * codes are BORN and how they are KEPT. Two rules, both load-bearing:
 *
 * - BORN random: 32 bytes from the JVM's SecureRandom, base64url — no
 *   UUIDs (their bit layout is not a security boundary), no timestamps,
 *   no usernames inside bearer material.
 * - KEPT hashed: at rest we store SHA-256(token), the password-hashing
 *   lesson applied to bearer tokens — a leaked store is not a leaked set
 *   of credentials. SHA-256 (not bcrypt) because these tokens are high
 *   entropy (128-bit+ random): brute force over the hash space is
 *   infeasible, so the slow-hash cost is unnecessary — unlike passwords,
 *   which are low-entropy and need it.
 */
public final class OpaqueTokens {

    public static final String API_KEY_PREFIX = "sdg_key_";
    public static final String REFRESH_PREFIX = "sdg_rt_";
    public static final String CODE_PREFIX = "sdg_ac_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private OpaqueTokens() {
    }

    /** A fresh opaque bearer secret: prefix + 43 base64url chars (32 bytes). */
    public static String generate(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The at-rest form: hex SHA-256 of the token. */
    public static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", unavailable);
        }
    }

    /** PKCE S256: the code challenge is the base64url SHA-256 of the verifier. */
    public static String s256Challenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", unavailable);
        }
    }

    /** Constant-time challenge comparison — the webhooks topic's lesson, reused. */
    public static boolean challengeMatches(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }
}
