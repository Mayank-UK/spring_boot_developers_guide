package guide.presentation.demo.auth;

import guide.domain.auth.UserPrincipal;
import guide.presentation.security.SecuritySessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 23 (authentication), the session island —
 * form-style login over JSON, the authenticated principal back out, and
 * a logout that kills the whole session:
 * - POST /api/demo/auth/session/login  {username, password} → principal +
 *   the session cookie's attributes (HttpOnly/SameSite/Secure surfaced)
 * - GET  /api/demo/auth/session/me     → the principal from THIS request's
 *   SecurityContext (session-restored); anonymous is refused by the chain
 *   with 401 before the controller runs
 * - POST /api/demo/auth/session/logout → session invalidated, context cleared
 *
 * Thin: the credential check is AuthenticateCredentials (via the session
 * service), the servlet facts (fixation protection, context save,
 * invalidation) live in SecuritySessionService.
 */
@RestController
public class AuthSessionsDemoController {

    private final SecuritySessionService sessions;
    private final boolean cookieHttpOnly;
    private final String cookieSameSite;
    private final boolean cookieSecure;

    public AuthSessionsDemoController(SecuritySessionService sessions,
            @Value("${server.servlet.session.cookie.http-only:true}") boolean cookieHttpOnly,
            @Value("${server.servlet.session.cookie.same-site:lax}") String cookieSameSite,
            @Value("${server.servlet.session.cookie.secure:false}") boolean cookieSecure) {
        this.sessions = sessions;
        this.cookieHttpOnly = cookieHttpOnly;
        this.cookieSameSite = cookieSameSite;
        this.cookieSecure = cookieSecure;
    }

    public record LoginRequest(String username, String password) {
    }

    @PostMapping(value = "/api/demo/auth/session/login",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> login(@RequestBody LoginRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        UserPrincipal user = sessions.login(request.username(), request.password(),
                httpRequest, httpResponse);
        return Map.of(
                "user", userView(user),
                "session", Map.of(
                        "cookie", sessions.cookieAttributes(cookieHttpOnly, cookieSameSite, cookieSecure),
                        "fixationProtected", true),
                "via", "session");
    }

    @GetMapping(value = "/api/demo/auth/session/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> me() {
        UserPrincipal user = sessions.currentUser();
        if (user == null) {
            // defensive: the chain's authenticated() rule answers 401 first
            throw new guide.core.errors.AppException(guide.core.errors.Failure.of(
                    guide.core.errors.FailureKind.UNAUTHORIZED, "no authenticated principal"));
        }
        return Map.of(
                "user", userView(user),
                "via", sessions.authenticatedVia());
    }

    @PostMapping(value = "/api/demo/auth/session/logout",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(request, response);
        return Map.of("loggedOut", true, "sessionInvalidated", true);
    }

    private static Map<String, Object> userView(UserPrincipal user) {
        return Map.of("id", user.id(), "username", user.username(),
                "roles", List.copyOf(user.roles()));
    }
}
