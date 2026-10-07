package guide.domain.auth;

import java.time.Instant;
import java.util.List;

/**
 * The refresh-token family store port. A FAMILY is every refresh token
 * descended from one login: issue creates family + generation 1, each
 * exchange ROTATES the presented token (it becomes spent) and mints the
 * next generation in the same family. The family is the unit of
 * revocation — because the only signal strong enough to justify killing
 * every token from one login is the one this shape enables: presenting a
 * token that was already spent (theft, almost certainly).
 */
public interface RefreshFamilyStore {

    /** Start a new family for a login; store the FIRST generation's hash as ACTIVE. */
    void startFamily(String familyId, String username, String firstTokenHash, Instant at);

    /** Look up a token by hash — its family, owner, and state (spent tokens stay visible). */
    StoredRefreshToken findByTokenHash(String tokenHash);

    /** Mark the presented token ROTATED and append the next generation, atomically. */
    void rotate(String familyId, String spentTokenHash, String nextTokenHash, Instant at);

    /** Revoke the WHOLE family — every generation, spent and active. */
    void revokeFamily(String familyId);

    /** The observability view: families with their generation timeline, hashes never leave. */
    List<RefreshFamilyView> families();

    /** One stored token, as a lookup result. */
    record StoredRefreshToken(String familyId, String username, TokenState state,
            boolean familyRevoked) {

        public enum TokenState {
            /** Current, usable. */
            ACTIVE,
            /** Already exchanged — presenting it again is the theft signal. */
            ROTATED
        }
    }

    /** Redacted family view for the demo surface. */
    record RefreshFamilyView(String familyId, String username, boolean revoked,
            List<Generation> generations) {

        public record Generation(StoredRefreshToken.TokenState state, Instant issuedAt) {
        }
    }
}
