package guide.domain.security;

import java.util.Optional;
import javax.crypto.SecretKey;

/**
 * Port: where the per-subject encryption keys live — and therefore the thing
 * an erasure request must DESTROY. One key per data subject is the whole
 * design: erasing a subject means deleting exactly one key, no matter how
 * many ciphertext rows, backups, or replicas carry its data.
 *
 * Production homes for these keys are real KMS/secret-store territory (AWS
 * KMS per-data-key envelopes, Vault transit keys); the in-memory adapter in
 * data/security/ exists so the DEMO can show the lifecycle honestly without
 * external services. The port is the promise: create, look up, destroy.
 */
public interface KeyVault {

    /** Mint a fresh AES-256 key for the subject; returns its id. Fails if one already exists. */
    String createKey(String subjectId);

    /** The subject's key, if it still exists — empty after erasure, by design. */
    Optional<SecretKey> key(String subjectId);

    /** The key id (stable handle, safe to log) — empty when the subject has no key. */
    Optional<String> keyId(String subjectId);

    /** Erasure: destroy the subject's key. Returns true when a key existed and died. */
    boolean destroyKey(String subjectId);
}
