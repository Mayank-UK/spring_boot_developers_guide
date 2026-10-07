package guide.domain.auth;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import java.time.Clock;

/**
 * The token endpoint, authorization_code grant: the CLIENT app presents
 * the code it caught in the redirect plus the SAME redirect_uri and
 * client_id from the authorization step, and the PKCE verifier whose
 * S256 hash must equal the challenge bound into the code. Everything the
 * code carried is re-verified at exchange — single use, unexpired,
 * client-bound, redirect-bound, PKCE-bound — and only then does a token
 * pair leave the server. A wrong verifier fails WITHOUT a second chance:
 * the code is consumed either way.
 */
public final class ExchangeAuthorizationCode {

    private final AuthorizationCodeStore codeStore;
    private final IssueTokenPair issueTokenPair;
    private final Clock clock;

    public ExchangeAuthorizationCode(AuthorizationCodeStore codeStore,
            IssueTokenPair issueTokenPair, Clock clock) {
        this.codeStore = codeStore;
        this.issueTokenPair = issueTokenPair;
        this.clock = clock;
    }

    public Result<IssueTokenPair.TokenPair> execute(String code, String clientId,
            String redirectUri, String codeVerifier) {
        if (code == null || code.isBlank()) {
            return new Failed<>(Failure.of(FailureKind.VALIDATION, "code is required"));
        }
        // consume FIRST: single-use means the code is spent even when everything after fails
        AuthorizationCodeStore.StoredAuthorization stored =
                codeStore.consume(OpaqueTokens.sha256Hex(code));
        if (stored == null) {
            return new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                    "unknown or already-used code — codes are single-use"));
        }
        if (clock.instant().isAfter(stored.expiresAt())) {
            return new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                    "authorization code expired — restart the flow"));
        }
        if (!stored.clientId().equals(clientId) || !stored.redirectUri().equals(redirectUri)) {
            return new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                    "client_id/redirect_uri do not match the authorization"));
        }
        String expected = OpaqueTokens.s256Challenge(codeVerifier == null ? "" : codeVerifier);
        if (!OpaqueTokens.challengeMatches("S256:" + expected, "S256:" + stored.codeChallenge())) {
            return new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                    "PKCE verification failed — verifier does not match the challenge"));
        }
        // the pair's access side is minted passwordless: the CODE was the proof
        return issueTokenPair.forAuthorizedUser(stored.username());
    }
}
