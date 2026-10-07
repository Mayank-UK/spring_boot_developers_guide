package guide.domain.auth;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import java.time.Clock;
import java.util.UUID;

/**
 * Issues an ACCESS + REFRESH pair after password proof. The pair's split
 * is the whole point: the access token is a short-lived JWT the API
 * validates statelessly; the refresh token is a LONG-lived OPAQUE secret
 * that only the issuer can resolve — so it holds the power to mint new
 * access tokens without holding API access, and its use stays rare
 * enough that rotation-on-use is affordable.
 */
public final class IssueTokenPair {

    private final AuthenticateCredentials authenticate;
    private final IdentityStore identityStore;
    private final RefreshFamilyStore refreshFamilies;
    private final JwtMinter jwtMinter;
    private final Clock clock;

    public IssueTokenPair(AuthenticateCredentials authenticate, IdentityStore identityStore,
            RefreshFamilyStore refreshFamilies, JwtMinter jwtMinter, Clock clock) {
        this.authenticate = authenticate;
        this.identityStore = identityStore;
        this.refreshFamilies = refreshFamilies;
        this.jwtMinter = jwtMinter;
        this.clock = clock;
    }

    public record TokenPair(String accessToken, java.time.Instant accessTokenExpiresAt,
            String refreshToken, String familyId) {
    }

    public Result<TokenPair> execute(String username, String password) {
        return switch (authenticate.execute(username, password)) {
            case Success<UserPrincipal> success ->
                    issueFor(success.value());
            case Failed<UserPrincipal> failed -> new Failed<>(failed.failure());
        };
    }

    /**
     * The OAuth path: the bearer of a valid authorization CODE already
     * proved the account owner consented — the pair is minted without a
     * password, for a fresh refresh family.
     */
    public Result<TokenPair> forAuthorizedUser(String username) {
        return identityStore.findByUsername(username)
                .<Result<TokenPair>>map(this::issueFor)
                .orElseGet(() -> new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                        "unknown user: " + username)));
    }

    private Result<TokenPair> issueFor(UserPrincipal user) {
        String refreshToken = OpaqueTokens.generate(OpaqueTokens.REFRESH_PREFIX);
        String familyId = "fam_" + UUID.randomUUID().toString().substring(0, 8);
        refreshFamilies.startFamily(familyId, user.username(),
                OpaqueTokens.sha256Hex(refreshToken), clock.instant());
        JwtMinter.MintedAccessToken access = jwtMinter.mint(user, JwtMinter.DEFAULT_TTL);
        return Result.success(new TokenPair(access.token(), access.expiresAt(),
                refreshToken, familyId));
    }
}
