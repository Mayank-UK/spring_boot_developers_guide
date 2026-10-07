package guide.presentation.demo.auth;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.auth.JwtMinter;
import guide.domain.auth.UserPrincipal;
import guide.presentation.security.SecuritySessionService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 23 (authentication), the JWT island:
 * - POST /api/demo/auth/jwt/token {username, password} → mint (the
 *   issuer half): token + decoded-claims preview; ttlSeconds is a DEMO
 *   hook so the tour can mint a token that expires in seconds
 * - GET  /api/demo/auth/jwt/protected → the resource-server half: the
 *   chain's oauth2ResourceServer validated the Bearer token BEFORE this
 *   controller ran — the principal IS the decoded, verified Jwt
 *
 * A tampered or expired Bearer never reaches here: 401 with the
 * WWW-Authenticate error detail, the resource server's own answer.
 */
@RestController
public class JwtDemoController {

    private final guide.domain.auth.AuthenticateCredentials authenticate;
    private final JwtMinter jwtMinter;
    private final SecuritySessionService sessions;

    public JwtDemoController(guide.domain.auth.AuthenticateCredentials authenticate,
            JwtMinter jwtMinter, SecuritySessionService sessions) {
        this.authenticate = authenticate;
        this.jwtMinter = jwtMinter;
        this.sessions = sessions;
    }

    public record TokenRequest(String username, String password, Integer ttlSeconds) {
    }

    @PostMapping(value = "/api/demo/auth/jwt/token",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> token(@RequestBody TokenRequest request,
            @RequestParam(name = "ttlSeconds", required = false) Long ttlQuery) {
        return switch (authenticate.execute(request.username(), request.password())) {
            case guide.core.result.Success<UserPrincipal> success -> {
                Duration ttl = resolveTtl(request.ttlSeconds(), ttlQuery);
                JwtMinter.MintedAccessToken minted = jwtMinter.mint(success.value(), ttl);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("accessToken", minted.token());
                body.put("tokenType", "Bearer");
                body.put("expiresIn", ttl.toSeconds());
                body.put("decoded", minted.claims());
                yield body;
            }
            case guide.core.result.Failed<UserPrincipal> failed -> throw AppException.of(failed.failure());
        };
    }

    @GetMapping(value = "/api/demo/auth/jwt/protected", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> protectedResource() {
        Object principal = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        if (principal instanceof Jwt jwt) {
            return Map.of(
                    "message", "the resource server verified this Bearer token — the claims below are TRUSTED",
                    "sub", jwt.getSubject(),
                    "roles", jwt.getClaimAsStringList("roles"),
                    "jti", jwt.getId(),
                    "issuedAt", jwt.getIssuedAt().toString(),
                    "expiresAt", jwt.getExpiresAt().toString(),
                    "via", "jwt");
        }
        // authenticated by a non-JWT mechanism (session/key) — still allowed by the chain
        UserPrincipal user = sessions.currentUser();
        return Map.of(
                "message", "authenticated, though not by a Bearer token",
                "sub", user == null ? "unknown" : user.username(),
                "via", sessions.authenticatedVia());
    }

    /** Demo hook: ttlSeconds (body or query) shortens the expiry so the tour can watch one die. */
    private static Duration resolveTtl(Integer bodyTtl, Long queryTtl) {
        // NOTE: no ternary here on purpose — `cond ? bodyTtl : queryTtl` would UNBOX both
        // branches (Integer/Long numeric promotion to long) and NPE on a null queryTtl
        Long requested = bodyTtl != null ? Long.valueOf(bodyTtl) : queryTtl;
        if (requested == null) {
            return JwtMinter.DEFAULT_TTL;
        }
        if (requested < 1 || requested > 900) {
            throw new AppException(Failure.of(FailureKind.VALIDATION,
                    "ttlSeconds must be between 1 and 900 (the demo's expiry hook)"));
        }
        return Duration.ofSeconds(requested);
    }
}
