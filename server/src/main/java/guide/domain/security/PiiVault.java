package guide.domain.security;

import java.util.Optional;

/**
 * Port: the ciphertext store. Deliberately DUMB — it keeps {@link PiiRecord}
 * rows and knows nothing about keys or plaintext. That separation is the
 * crypto-shred design speaking: the vault (database, backups, replicas) and
 * the key home are DIFFERENT systems, because an erasure must be possible
 * even when the vault cannot delete rows everywhere at once.
 */
public interface PiiVault {

    void save(PiiRecord record);

    Optional<PiiRecord> find(String subjectId);
}
