package guide.domain.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The identity substrate port: everything authentication needs to know
 * about users and their credentials. Implemented in memory for this guide
 * (data/auth/InMemoryIdentityStore, seeded users, bcrypt hashes minted at
 * startup); a real deployment swaps in a JDBC/JPA/LDAP adapter and nothing
 * above this interface changes.
 *
 * Read side serves the two lookups the filter chain performs per request:
 * username lookup (the UserDetailsService adapter) and API-key lookup (the
 * X-API-Key filter, by SHA-256). Admin side is the demo's key-management
 * surface (issue, list, revoke) — kept on the same port because it is the
 * same substrate, bounded in memory like every teaching store.
 */
public interface IdentityStore {

    /** Find a user by login name — the DaoAuthenticationProvider's one question. */
    Optional<UserPrincipal> findByUsername(String username);

    /** Find an active API key by the SHA-256 of the presented key. */
    Optional<ApiKeyRecord> findApiKeyByHash(String keyHash);

    /** Store a newly issued key (hash at rest); the raw key is shown once and never stored. */
    void saveApiKey(ApiKeyRecord record);

    /** Record that a key authenticated successfully (audit surface: lastUsedAt). */
    void markApiKeyUsed(String keyHash, Instant instant);

    /** Revoke (delete) a key by its record id. */
    boolean revokeApiKey(String id);

    /** All keys, redacted views only — never hashes, never raw keys. */
    List<ApiKeyRecord> apiKeys();
}
