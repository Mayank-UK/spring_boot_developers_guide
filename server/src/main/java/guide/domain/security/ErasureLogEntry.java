package guide.domain.security;

import java.time.Instant;

/**
 * One erasure event: the subject, the action taken (the key that died, not
 * the rows — the rows remain as ciphertext), and when. No plaintext, ever.
 */
public record ErasureLogEntry(String id, String subjectId, String keyId, String action, Instant at) {
}
