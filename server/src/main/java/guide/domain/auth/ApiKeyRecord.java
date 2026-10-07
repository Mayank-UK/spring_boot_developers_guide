package guide.domain.auth;

import java.time.Instant;

/**
 * One issued API key, as stored: NEVER the key itself. At rest we keep the
 * SHA-256 of the full key (so a store dump cannot authenticate anything)
 * plus a short DISPLAY PREFIX — the first characters of the real key, the
 * industry convention (GitHub's ghp_A1b2…, Stripe's sk_live_…AbCd) that
 * lets an operator find a key in a list without the key being usable.
 */
public record ApiKeyRecord(String id, String username, String keyHash, String displayPrefix,
        Instant createdAt, Instant lastUsedAt) {

    public ApiKeyRecord {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(username, "username");
        java.util.Objects.requireNonNull(keyHash, "keyHash");
        java.util.Objects.requireNonNull(displayPrefix, "displayPrefix");
        java.util.Objects.requireNonNull(createdAt, "createdAt");
    }

    /** The redacted view every listing carries: id + prefix + owner, never the hash. */
    public String redacted() {
        return displayPrefix + "…";
    }
}
