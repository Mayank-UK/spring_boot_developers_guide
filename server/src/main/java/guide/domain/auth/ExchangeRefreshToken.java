package guide.domain.auth;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import java.time.Clock;

/**
 * The refresh grant — and the theft detector. Present the CURRENT token
 * of a family: it is spent (ROTATED), the next generation is minted, the
 * pair rolls on. Present a token that was ALREADY spent: the entire
 * family is REVOKED and the answer is 401. A spent token being replayed
 * means two parties hold the same token — one is the thief, and the
 * honest response is to assume the worst for the whole lineage.
 */
public final class ExchangeRefreshToken {

    private final RefreshFamilyStore refreshFamilies;
    private final IdentityStore identityStore;
    private final JwtMinter jwtMinter;
    private final Clock clock;

    public ExchangeRefreshToken(RefreshFamilyStore refreshFamilies, IdentityStore identityStore,
            JwtMinter jwtMinter, Clock clock) {
        this.refreshFamilies = refreshFamilies;
        this.identityStore = identityStore;
        this.jwtMinter = jwtMinter;
        this.clock = clock;
    }

    public Result<IssueTokenPair.TokenPair> execute(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return new Failed<>(Failure.of(FailureKind.VALIDATION,
                    "refreshToken is required"));
        }
        RefreshFamilyStore.StoredRefreshToken stored =
                refreshFamilies.findByTokenHash(OpaqueTokens.sha256Hex(refreshToken));
        if (stored == null) {
            return new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                    "unknown refresh token"));
        }
        if (stored.familyRevoked()) {
            return new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                    "refresh family is revoked — log in again"));
        }
        if (stored.state() != RefreshFamilyStore.StoredRefreshToken.TokenState.ACTIVE) {
            // reuse: this token was already exchanged once. Revoke the lineage.
            refreshFamilies.revokeFamily(stored.familyId());
            return new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                    "refresh token reuse detected — family revoked (possible theft)"));
        }
        return identityStore.findByUsername(stored.username())
                .<Result<IssueTokenPair.TokenPair>>map(user -> {
                    String nextRefresh = OpaqueTokens.generate(OpaqueTokens.REFRESH_PREFIX);
                    refreshFamilies.rotate(stored.familyId(),
                            OpaqueTokens.sha256Hex(refreshToken),
                            OpaqueTokens.sha256Hex(nextRefresh), clock.instant());
                    JwtMinter.MintedAccessToken access = jwtMinter.mint(user, JwtMinter.DEFAULT_TTL);
                    return Result.success(new IssueTokenPair.TokenPair(
                            access.token(), access.expiresAt(), nextRefresh, stored.familyId()));
                })
                .orElseGet(() -> new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                        "refresh family owner no longer exists")));
    }
}
