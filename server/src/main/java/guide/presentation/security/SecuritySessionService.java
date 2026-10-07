package guide.presentation.security;

import guide.core.errors.AppException;
import guide.domain.auth.AuthenticateCredentials;
import guide.domain.auth.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * The servlet-side glue of the session demo: authenticating credentials
 * INTO the HTTP session, reading the principal back out, and logging
 * out. Domain logic (the credential check) lives in the use case; this
 * class owns the three servlet facts the domain must never know —
 * session fixation protection (change the session id on login), the
 * SecurityContext repository save (explicit since Spring Security 6),
 * and whole-session invalidation on logout.
 */
public final class SecuritySessionService {

    private final AuthenticateCredentials authenticate;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public SecuritySessionService(AuthenticateCredentials authenticate) {
        this.authenticate = authenticate;
    }

    /** Verify credentials, rotate the session id, save the context into the session. */
    public UserPrincipal login(String username, String password,
            HttpServletRequest request, HttpServletResponse response) {
        return switch (authenticate.execute(username, password)) {
            case guide.core.result.Success<UserPrincipal> success -> {
                UserPrincipal user = success.value();
                // fixation protection: a session that existed pre-login gets a NEW id
                // (attributes survive; an attacker's copy of the old id dies here)
                HttpSession existing = request.getSession(false);
                if (existing != null) {
                    request.changeSessionId();
                }
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(user, null, authorities(user));
                authentication.setDetails("session");
                context.setAuthentication(authentication);
                securityContextRepository.saveContext(context, request, response);
                yield user;
            }
            case guide.core.result.Failed<UserPrincipal> failed ->
                    throw AppException.of(failed.failure());
        };
    }

    /** The authenticated principal of THIS request's context, or null. */
    public UserPrincipal currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        return authentication.getPrincipal() instanceof UserPrincipal user ? user : null;
    }

    /** How this request authenticated — surfaced so the demo can show the mechanism. */
    public String authenticatedVia() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "none";
        }
        Object details = authentication.getDetails();
        if (details instanceof String text) {
            if (text.startsWith("api-key")) {
                return "api-key";
            }
            if (text.equals("session")) {
                return "session";
            }
        }
        if (authentication.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt) {
            return "jwt";
        }
        return authentication.getClass().getSimpleName();
    }

    /** Invalidate the session and clear the context — SecurityContextLogoutHandler does both. */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response,
                SecurityContextHolder.getContext().getAuthentication());
    }

    /** The cookie facts the login response reports (config values, not secrets). */
    public Map<String, Object> cookieAttributes(boolean httpOnly, String sameSite, boolean secure) {
        return Map.of("name", "JSESSIONID", "httpOnly", httpOnly,
                "sameSite", sameSite, "secure", secure);
    }

    private static List<SimpleGrantedAuthority> authorities(UserPrincipal user) {
        return user.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
                .toList();
    }
}
