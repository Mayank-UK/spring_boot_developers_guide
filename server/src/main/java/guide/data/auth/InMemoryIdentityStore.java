package guide.data.auth;

import guide.domain.auth.ApiKeyRecord;
import guide.domain.auth.IdentityStore;
import guide.domain.auth.UserPrincipal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Adapter: the seeded identity substrate, in memory. The three demo users
 * (alice/admin+staff, bob/staff, carol/reader) are seeded with bcrypt
 * hashes MINTED AT STARTUP from plaintext constants — never hardcoded
 * hash literals. The constants are dev fixtures and say so; the hashes
 * are real bcrypt output, so every verify path exercises the real
 * encoder. Keys are stored hashed (SHA-256) with a bounded ceiling —
 * a teaching store never grows without limit.
 */
public final class InMemoryIdentityStore implements IdentityStore {

    /** Dev fixtures — the guide's openly-documented demo passwords. Not a pattern to copy. */
    static final String ALICE_PASSWORD = "alice-demo-pass-01";
    static final String BOB_PASSWORD = "bob-demo-pass-01";
    static final String CAROL_PASSWORD = "carol-demo-pass-01";

    static final int MAX_API_KEYS = 24;

    private final Map<String, UserPrincipal> usersByUsername = new LinkedHashMap<>();
    private final Map<String, ApiKeyRecord> apiKeysById = new LinkedHashMap<>();
    private final Map<String, ApiKeyRecord> apiKeysByHash = new LinkedHashMap<>();

    public InMemoryIdentityStore(PasswordEncoder passwordEncoder) {
        seed("alice", List.of("admin", "staff"), ALICE_PASSWORD, passwordEncoder);
        seed("bob", List.of("staff"), BOB_PASSWORD, passwordEncoder);
        seed("carol", List.of("reader"), CAROL_PASSWORD, passwordEncoder);
    }

    private void seed(String username, List<String> roles, String password,
            PasswordEncoder passwordEncoder) {
        usersByUsername.put(username, new UserPrincipal(
                "usr_" + username, username, roles, passwordEncoder.encode(password)));
    }

    @Override
    public synchronized Optional<UserPrincipal> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }

    @Override
    public synchronized Optional<ApiKeyRecord> findApiKeyByHash(String keyHash) {
        return Optional.ofNullable(apiKeysByHash.get(keyHash));
    }

    @Override
    public synchronized void saveApiKey(ApiKeyRecord record) {
        apiKeysById.put(record.id(), record);
        apiKeysByHash.put(record.keyHash(), record);
        while (apiKeysById.size() > MAX_API_KEYS) {
            String oldest = apiKeysById.keySet().iterator().next();
            apiKeysByHash.remove(apiKeysById.remove(oldest).keyHash());
        }
    }

    @Override
    public synchronized void markApiKeyUsed(String keyHash, Instant instant) {
        ApiKeyRecord record = apiKeysByHash.get(keyHash);
        if (record != null) {
            ApiKeyRecord touched = new ApiKeyRecord(record.id(), record.username(),
                    record.keyHash(), record.displayPrefix(), record.createdAt(), instant);
            apiKeysById.put(touched.id(), touched);
            apiKeysByHash.put(touched.keyHash(), touched);
        }
    }

    @Override
    public synchronized boolean revokeApiKey(String id) {
        ApiKeyRecord removed = apiKeysById.remove(id);
        if (removed == null) {
            return false;
        }
        apiKeysByHash.remove(removed.keyHash());
        return true;
    }

    @Override
    public synchronized List<ApiKeyRecord> apiKeys() {
        return List.copyOf(apiKeysById.values());
    }
}
