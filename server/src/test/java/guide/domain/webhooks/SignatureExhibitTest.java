package guide.domain.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * SignatureExhibit tests — the signing scheme's contract, each case a claim
 * the topic makes in prose: roundtrip verification, tamper detection, wrong
 * secret, replay window (both edges), malformed headers, and the
 * equal-length-wrong-bytes case that String.equals would pass on timing
 * grounds alone. Deterministic by construction: fixed secret, fixed body,
 * fixed instants, no clock reading.
 */
class SignatureExhibitTest {

    private static final String SECRET = "whsec_exhibit_test_secret_0123456789";
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"invoice.paid\",\"data\":{\"amount\":4200}}";
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    @Test
    void signThenVerifyRoundTrips() {
        String header = SignatureExhibit.signatureHeader(SECRET,
                String.valueOf(NOW.getEpochSecond()), BODY);
        assertTrue(header.startsWith("t=" + NOW.getEpochSecond() + ",v1="),
                "header shape is t=<epoch-seconds>,v1=<hex>: " + header);
        SignatureExhibit.SignatureVerdict verdict =
                SignatureExhibit.verify(SECRET, header, BODY, NOW, TOLERANCE);
        assertTrue(verdict.valid());
        assertEquals("verified", verdict.verdict());
    }

    @Test
    void alteredBodyFailsVerification() {
        String header = SignatureExhibit.signatureHeader(SECRET,
                String.valueOf(NOW.getEpochSecond()), BODY);
        SignatureExhibit.SignatureVerdict verdict =
                SignatureExhibit.verify(SECRET, header, BODY.replace("4200", "9900"), NOW, TOLERANCE);
        assertNotVerified(verdict, "hmac mismatch");
    }

    @Test
    void wrongSecretFailsWithHmacMismatch() {
        String header = SignatureExhibit.signatureHeader("whsec_attacker_does_not_know_this",
                String.valueOf(NOW.getEpochSecond()), BODY);
        SignatureExhibit.SignatureVerdict verdict =
                SignatureExhibit.verify(SECRET, header, BODY, NOW, TOLERANCE);
        assertNotVerified(verdict, "hmac mismatch");
    }

    @Test
    void timestampOutsideWindowIsStaleEvenWithValidHmac() {
        String sixtySecondsAgo = String.valueOf(NOW.minus(Duration.ofMinutes(6)).getEpochSecond());
        String header = SignatureExhibit.signatureHeader(SECRET, sixtySecondsAgo, BODY);
        SignatureExhibit.SignatureVerdict verdict =
                SignatureExhibit.verify(SECRET, header, BODY, NOW, TOLERANCE);
        assertEquals(false, verdict.valid());
        assertEquals("stale", verdict.verdict());
        assertTrue(verdict.reason().contains("replay window"),
                "the reason names the window: " + verdict.reason());
    }

    @Test
    void futureTimestampsAreAlsoRejected() {
        String fromTheFuture = String.valueOf(NOW.plus(Duration.ofMinutes(6)).getEpochSecond());
        String header = SignatureExhibit.signatureHeader(SECRET, fromTheFuture, BODY);
        assertEquals("stale", SignatureExhibit.verify(SECRET, header, BODY, NOW, TOLERANCE).verdict());
    }

    @Test
    void edgeOfWindowStillVerifies() {
        String edge = String.valueOf(NOW.minus(TOLERANCE).getEpochSecond());
        String header = SignatureExhibit.signatureHeader(SECRET, edge, BODY);
        assertTrue(SignatureExhibit.verify(SECRET, header, BODY, NOW, TOLERANCE).valid(),
                "±tolerance is inclusive on the stale edge");
    }

    @Test
    void malformedHeadersAreInvalidNotExceptions() {
        assertEquals("invalid", SignatureExhibit.verify(SECRET, null, BODY, NOW, TOLERANCE).verdict());
        assertEquals("invalid", SignatureExhibit.verify(SECRET, "", BODY, NOW, TOLERANCE).verdict());
        assertEquals("invalid",
                SignatureExhibit.verify(SECRET, "v1=abc", BODY, NOW, TOLERANCE).verdict());
        assertEquals("invalid",
                SignatureExhibit.verify(SECRET, "t=not-a-number,v1=ab", BODY, NOW, TOLERANCE).verdict());
        assertEquals("invalid", SignatureExhibit
                .verify(SECRET, "t=" + NOW.getEpochSecond() + ",v1=not-hex!", BODY, NOW, TOLERANCE)
                .verdict());
    }

    @Test
    void equalLengthButWrongBytesFails() {
        // flip one hex character: same length (no length leak to lean on), every byte must compare
        String header = SignatureExhibit.signatureHeader(SECRET,
                String.valueOf(NOW.getEpochSecond()), BODY);
        char[] chars = header.toCharArray();
        int last = chars.length - 1;
        chars[last] = chars[last] == '0' ? '1' : '0';
        SignatureExhibit.SignatureVerdict verdict =
                SignatureExhibit.verify(SECRET, new String(chars), BODY, NOW, TOLERANCE);
        assertNotVerified(verdict, "hmac mismatch");
    }

    @Test
    void signatureChangesWithTimestampAndBody() {
        String t1 = SignatureExhibit.signatureHeader(SECRET, "1000", BODY);
        String t2 = SignatureExhibit.signatureHeader(SECRET, "1001", BODY);
        String otherBody = SignatureExhibit.signatureHeader(SECRET, "1000", BODY + " ");
        assertNotEquals(t1, t2, "fresh timestamp per attempt ⇒ fresh signature");
        assertNotEquals(t1, otherBody, "the signature covers the exact body bytes");
    }

    private static void assertNotVerified(SignatureExhibit.SignatureVerdict verdict, String reasonPart) {
        assertEquals(false, verdict.valid());
        assertTrue(verdict.reason().contains(reasonPart),
                "reason should contain '" + reasonPart + "': " + verdict.reason());
    }
}
