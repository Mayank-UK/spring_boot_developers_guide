package guide.presentation.demo.auth;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.auth.AuthorizeClient;
import guide.domain.auth.ExchangeAuthorizationCode;
import guide.domain.auth.ExchangeRefreshToken;
import guide.domain.auth.IssueTokenPair;
import guide.domain.auth.KnownClients;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 23 (authentication), the OAuth2
 * authorization-code dance — played entirely in-app, the way the express
 * guide taught it: this app's authorize endpoint IS the (teaching)
 * authorization server, and the client demo plays the client app.
 * - GET  /api/demo/auth/oauth/clients  → the registry (the client-app
 *   perspective: where may I redirect, what must I bring)
 * - POST /api/demo/auth/oauth/authorize → the login-page + consent
 *   simulation: valid credentials + consent=true mint a single-use,
 *   PKCE-bound, 60s-TTL code, answered as the redirect the client follows
 * - POST /api/demo/auth/oauth/token    → the exchange: grant_type
 *   authorization_code (code + redirect_uri + client_id + code_verifier)
 *   or refresh_token (rotation + family reuse detection)
 *
 * Real deployments outsource this whole endpoint pair to an external
 * IdP (Keycloak, Auth0, the corporate OIDC server) — the prose teaches
 * that; the specimen here exists so every step is observable.
 */
@RestController
public class OAuthDemoController {

    private final KnownClients clients;
    private final AuthorizeClient authorizeClient;
    private final ExchangeAuthorizationCode exchangeCode;
    private final ExchangeRefreshToken exchangeRefresh;

    public OAuthDemoController(KnownClients clients, AuthorizeClient authorizeClient,
            ExchangeAuthorizationCode exchangeCode, ExchangeRefreshToken exchangeRefresh) {
        this.clients = clients;
        this.authorizeClient = authorizeClient;
        this.exchangeCode = exchangeCode;
        this.exchangeRefresh = exchangeRefresh;
    }

    @GetMapping(value = "/api/demo/auth/oauth/clients", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> clientRegistry() {
        KnownClients.ClientApp console = clients.find(KnownClients.CONSOLE_CLIENT_ID);
        return Map.of("clients", List.of(Map.of(
                "clientId", console.clientId(),
                "name", console.name(),
                "allowedRedirectUris", console.allowedRedirectUris())));
    }

    @PostMapping(value = "/api/demo/auth/oauth/authorize",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> authorize(@RequestBody AuthorizeClient.Request request) {
        return switch (authorizeClient.execute(request)) {
            case guide.core.result.Success<AuthorizeClient.AuthorizationIssued> success -> {
                AuthorizeClient.AuthorizationIssued issued = success.value();
                yield Map.of(
                        "redirectTo", issued.redirectTo(),
                        "code", issued.code(),
                        "state", issued.state() == null ? "" : issued.state(),
                        "expiresAt", issued.expiresAt().toString(),
                        "note", "single-use, 60s TTL — the client app now exchanges it at /token");
            }
            case guide.core.result.Failed<AuthorizeClient.AuthorizationIssued> failed ->
                    throw AppException.of(failed.failure());
        };
    }

    public record TokenRequest(String grantType, String code, String redirectUri,
            String clientId, String codeVerifier, String refreshToken) {
    }

    @PostMapping(value = "/api/demo/auth/oauth/token",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> token(@RequestBody TokenRequest request) {
        if ("authorization_code".equals(request.grantType())) {
            return switch (exchangeCode.execute(request.code(), request.clientId(),
                    request.redirectUri(), request.codeVerifier())) {
                case guide.core.result.Success<IssueTokenPair.TokenPair> success ->
                        tokenResponse(success.value(), "authorization_code");
                case guide.core.result.Failed<IssueTokenPair.TokenPair> failed ->
                        throw AppException.of(failed.failure());
            };
        }
        if ("refresh_token".equals(request.grantType())) {
            return switch (exchangeRefresh.execute(request.refreshToken())) {
                case guide.core.result.Success<IssueTokenPair.TokenPair> success ->
                        tokenResponse(success.value(), "refresh_token");
                case guide.core.result.Failed<IssueTokenPair.TokenPair> failed ->
                        throw AppException.of(failed.failure());
            };
        }
        throw new AppException(Failure.of(FailureKind.VALIDATION,
                "grant_type must be authorization_code or refresh_token"));
    }

    /** The OAuth token response shape: access token, its type, expiry, and the refresh token. */
    private static Map<String, Object> tokenResponse(IssueTokenPair.TokenPair pair, String grant) {
        return Map.of(
                "accessToken", pair.accessToken(),
                "tokenType", "Bearer",
                "expiresAt", pair.accessTokenExpiresAt().toString(),
                "refreshToken", pair.refreshToken(),
                "familyId", pair.familyId(),
                "grant", grant);
    }
}
