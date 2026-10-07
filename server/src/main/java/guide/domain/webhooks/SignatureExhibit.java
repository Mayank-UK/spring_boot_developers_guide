package guide.domain.webhooks;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Topic 14's signing scheme, real and pure: HMAC-SHA256 over
 * {@code timestamp + "." + body} with the shared secret, exchanged in one
 * header — {@code X-SDG-Signature: t=<epoch-seconds>,v1=<hex hmac>}. This is
 * the same shape Stripe, GitHub, and Slack use, built on nothing but the
 * JDK's {@link javax.crypto.Mac}.
 *
 * WHY the timestamp is inside the signed material: it makes the signature
 * perishable. A captured request replayed next week carries an old {@code t},
 * which the verify side rejects against a tolerance window — so an attacker
 * who reads the traffic cannot re-send it forever, and the signer can also
 * reject timestamps from the future (clock-skew ceiling). The scheme remains
 * HMAC over the body alone in effect, but freshness becomes part of the
 * proof.
 *
 * WHY {@link MessageDigest#isEqual} and not {@code String.equals}: comparing
 * digests with equals (or ==) returns at the FIRST differing character, and
 * an attacker who can measure response timing byte-by-byte can reconstruct a
 * valid signature without ever knowing the secret — the classic timing
 * attack. isEqual compares every byte for equal-length inputs in constant
 * time; unequal lengths fail fast, which leaks only the length (harmless —
 * the attacker controls that input anyway).
 */
public final class SignatureExhibit {

    /** The header names the demo's sender sets and its receiver demands. */
    public static final String SIGNATURE_HEADER = "X-SDG-Signature";
    public static final String EVENT_HEADER = "X-SDG-Event";

    /** The consumer-side replay window: ±5 minutes of clock skew, the industry default. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    /** The verify verdict a receipt can show: verified, or why not. */
    public record SignatureVerdict(boolean valid, String verdict, String reason) {
    }

    private SignatureExhibit() {
    }

    /** Produce the header value for one outbound attempt — a fresh timestamp every attempt. */
    public static String signatureHeader(String secret, String timestamp, String body) {
        byte[] mac = hmacSha256(secret, timestamp + "." + body);
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac);
    }

    /**
     * The consumer side, in the order the topic teaches: parse the header,
     * judge freshness against the replay window, recompute the HMAC, and
     * compare in constant time. Anything short of {@code verified} means the
     * request is refused BEFORE its body is ever interpreted.
     */
    public static SignatureVerdict verify(String secret, String headerValue, String body,
            Instant now, Duration tolerance) {
        if (headerValue == null || headerValue.isBlank()) {
            return new SignatureVerdict(false, "invalid", "missing signature header");
        }
        String timestamp = null;
        String presented = null;
        for (String part : headerValue.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) {
                if (pair[0].trim().equals("t")) {
                    timestamp = pair[1].trim();
                } else if (pair[0].trim().equals("v1")) {
                    presented = pair[1].trim();
                }
            }
        }
        if (timestamp == null || presented == null) {
            return new SignatureVerdict(false, "invalid", "header must carry t= and v1=");
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException badTimestamp) {
            return new SignatureVerdict(false, "invalid", "t= is not an epoch-seconds number");
        }
        Instant signedAt = Instant.ofEpochSecond(seconds);
        if (signedAt.isBefore(now.minus(tolerance)) || signedAt.isAfter(now.plus(tolerance))) {
            return new SignatureVerdict(false, "stale",
                    "timestamp outside the ±" + tolerance.toSeconds() + "s replay window — possible replay");
        }
        byte[] presentedBytes = parseHex(presented);
        if (presentedBytes == null) {
            return new SignatureVerdict(false, "invalid", "v1= is not hexadecimal");
        }
        byte[] expected = hmacSha256(secret, timestamp + "." + body);
        // constant-time compare — never String.equals on a digest (timing attacks)
        boolean matches = MessageDigest.isEqual(expected, presentedBytes);
        return matches
                ? new SignatureVerdict(true, "verified", "hmac-sha256 match, constant-time compare")
                : new SignatureVerdict(false, "invalid", "hmac mismatch — wrong secret or altered body");
    }

    /** HMAC-SHA256(secret, message) — the four lines every JVM signing scheme is built from. */
    static byte[] hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable in this JVM", unavailable);
        }
    }

    private static byte[] parseHex(String hex) {
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException notHex) {
            return null;
        }
    }
}
