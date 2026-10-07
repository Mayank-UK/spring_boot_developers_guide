package guide.domain.auth;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The authorization endpoint's decision: the resource owner (proven by
 * username+password — this simulates the login page a real authorization
 * server would show) CONSENTS to let a known client act for them, and the
 * server mints a single-use, short-TTL, PKCE-bound authorization code —
 * never a token. The response carries the redirect the CLIENT app would
 * be sent to: code and state on the query string, exactly as the wire
 * format specifies. consent=false is the user saying no: access_denied,
 * no code, and (in the real protocol) an error redirect.
 */
public final class AuthorizeClient {

    public static final Duration CODE_TTL = Duration.ofSeconds(60);

    private final KnownClients clients;
    private final AuthenticateCredentials authenticate;
    private final AuthorizationCodeStore codeStore;
    private final Clock clock;

    public AuthorizeClient(KnownClients clients, AuthenticateCredentials authenticate,
            AuthorizationCodeStore codeStore, Clock clock) {
        this.clients = clients;
        this.authenticate = authenticate;
        this.codeStore = codeStore;
        this.clock = clock;
    }

    public record Request(String responseType, String clientId, String redirectUri, String state,
            String codeChallenge, String codeChallengeMethod, String username, String password,
            boolean consent) {
    }

    public record AuthorizationIssued(String redirectTo, String code, String state,
            Instant expiresAt) {
    }

    public Result<AuthorizationIssued> execute(Request request) {
        if (!"code".equals(request.responseType())) {
            return new Failed<>(Failure.of(FailureKind.VALIDATION,
                    "response_type must be \"code\" — this server speaks the authorization-code flow only"));
        }
        KnownClients.ClientApp client = clients.find(request.clientId());
        if (client == null) {
            return new Failed<>(Failure.of(FailureKind.VALIDATION,
                    "unknown client_id — the client registry is the allowlist"));
        }
        if (!clients.redirectAllowed(request.clientId(), request.redirectUri())) {
            return new Failed<>(Failure.of(FailureKind.VALIDATION,
                    "redirect_uri is not registered for this client (open-redirect guard)"));
        }
        if (request.codeChallenge() == null || request.codeChallenge().isBlank()
                || !"S256".equals(request.codeChallengeMethod())) {
            return new Failed<>(Failure.of(FailureKind.VALIDATION,
                    "PKCE required: code_challenge + method S256 (public clients must prove key possession)"));
        }
        if (!request.consent()) {
            return new Failed<>(Failure.of(FailureKind.FORBIDDEN,
                    "access_denied — the resource owner declined consent"));
        }
        return switch (authenticate.execute(request.username(), request.password())) {
            case Success<UserPrincipal> success -> {
                String code = OpaqueTokens.generate(OpaqueTokens.CODE_PREFIX);
                codeStore.save(OpaqueTokens.sha256Hex(code),
                        new AuthorizationCodeStore.StoredAuthorization(
                                success.value().username(), request.clientId(),
                                request.redirectUri(), request.codeChallenge(),
                                request.codeChallengeMethod(), clock.instant().plus(CODE_TTL)));
                StringBuilder redirect = new StringBuilder(request.redirectUri())
                        .append(request.redirectUri().contains("?") ? '&' : '?')
                        .append("code=").append(code);
                if (request.state() != null && !request.state().isBlank()) {
                    redirect.append("&state=").append(request.state());
                }
                yield Result.success(new AuthorizationIssued(redirect.toString(), code,
                        request.state(), clock.instant().plus(CODE_TTL)));
            }
            case Failed<UserPrincipal> failed -> new Failed<>(failed.failure());
        };
    }
}
