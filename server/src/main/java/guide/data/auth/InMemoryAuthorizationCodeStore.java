package guide.data.auth;

import guide.domain.auth.AuthorizationCodeStore;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapter: authorization codes, in memory, single-use by construction —
 * consume() REMOVES. Stored by SHA-256 hash (never the code itself) with
 * a hard ceiling; expiry is the use case's call against the stored
 * expiresAt, so a stale code still consumes (single-use outranks TTL: an
 * expired code must not be exchangeable even once).
 */
public final class InMemoryAuthorizationCodeStore implements AuthorizationCodeStore {

    static final int MAX_CODES = 32;

    private final Map<String, StoredAuthorization> codesByHash = new LinkedHashMap<>();

    @Override
    public synchronized void save(String codeHash, StoredAuthorization authorization) {
        codesByHash.put(codeHash, authorization);
        while (codesByHash.size() > MAX_CODES) {
            codesByHash.remove(codesByHash.keySet().iterator().next());
        }
    }

    @Override
    public synchronized StoredAuthorization consume(String codeHash) {
        return codesByHash.remove(codeHash);
    }
}
