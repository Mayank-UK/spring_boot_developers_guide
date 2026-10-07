package guide.domain.security;

import java.time.Instant;
import java.util.Map;

/**
 * One subject's stored personal data — as CIPHERTEXT only. Every field value
 * is AES-GCM output (IV + ciphertext, base64) under a key that belongs to
 * this subject alone; the plaintext never touches the store. The record is
 * what survives an erasure request: the rows stay, the key dies, and the
 * bytes become unreadable noise — the crypto-shred pattern.
 *
 * @param subjectId        the data subject ("usr_42")
 * @param keyId            which vault key the fields were sealed under (per-subject)
 * @param encryptedFields  field name → base64(iv ‖ AES-GCM ciphertext)
 * @param algorithm        fixed marker "AES-256/GCM/NoPadding" — self-describing at rest
 * @param storedAt         when the data was sealed
 */
public record PiiRecord(String subjectId, String keyId, Map<String, String> encryptedFields,
        String algorithm, Instant storedAt) {

    public static final String ALGORITHM = "AES-256/GCM/NoPadding";
}
