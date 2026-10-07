package guide.domain.auth;

import java.time.Instant;

/**
 * Authorization-code storage port: single-use by CONSTRUCTION — consume()
 * removes. Codes are stored HASHED (SHA-256) like every other bearer
 * secret in this topic, and carry a short TTL the use case enforces
 * against the clock (60 seconds here — a code is a handshake artifact,
 * not a credential; it should barely outlive the redirect it rides in).
 */
public interface AuthorizationCodeStore {

    /** Store one issued code (by hash) with everything the token exchange must re-verify. */
    void save(String codeHash, StoredAuthorization authorization);

    /** Take the code OUT of the store — the second presentation finds nothing. */
    StoredAuthorization consume(String codeHash);

    /** Everything the authorization decision bound to the code. */
    record StoredAuthorization(String username, String clientId, String redirectUri,
            String codeChallenge, String challengeMethod, Instant expiresAt) {
    }
}
