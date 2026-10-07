package guide.data.security;

import guide.domain.security.KeyVault;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Adapter: the KeyVault port as an in-memory map — the DEMO's key home. The
 * honesty note that travels with it: in-memory keys die with the process,
 * which for crypto-shred means "erasure" and "restart" would be the same
 * event. A real deployment keeps per-subject keys in a KMS/secret store
 * whose deletion is itself logged — the PATTERN (per-subject key, destroy
 * on erasure) is the teaching; the home is swappable behind the port.
 *
 * One AES-256 key per subject, minted by KeyGenerator, identified by a
 * stable "key_…" handle that is safe to print (logs, erasure entries).
 */
public final class InMemoryKeyVault implements KeyVault {

    private record Held(String keyId, SecretKey key) {
    }

    private final Map<String, Held> keys = new ConcurrentHashMap<>();

    @Override
    public String createKey(String subjectId) {
        return keys.compute(subjectId, (subject, existing) -> {
            if (existing != null) {
                throw new IllegalStateException("key already exists for subject \"" + subject + "\"");
            }
            return new Held("key_" + UUID.randomUUID().toString().substring(0, 8), generateKey());
        }).keyId();
    }

    @Override
    public Optional<SecretKey> key(String subjectId) {
        return Optional.ofNullable(keys.get(subjectId)).map(Held::key);
    }

    @Override
    public Optional<String> keyId(String subjectId) {
        return Optional.ofNullable(keys.get(subjectId)).map(Held::keyId);
    }

    @Override
    public boolean destroyKey(String subjectId) {
        return keys.remove(subjectId) != null;
    }

    private static SecretKey generateKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            return generator.generateKey();
        } catch (Exception unavailable) {
            throw new IllegalStateException("AES key generation unavailable: " + unavailable.getMessage(),
                    unavailable);
        }
    }
}
