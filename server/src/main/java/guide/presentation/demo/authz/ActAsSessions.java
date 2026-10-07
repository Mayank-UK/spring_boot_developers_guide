package guide.presentation.demo.authz;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.auth.IdentityStore;
import guide.domain.auth.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Locale;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * The authz tour's identity switcher — the servlet glue of "act as".
 * Topic 23's session login MACHINERY, reused deliberately: fixation
 * protection (rotate the id whenever identity changes), a fresh
 * SecurityContext saved EXPLICITLY into the session (the Spring
 * Security 6 contract), and the same repository the baseline chain
 * loads from. The one deliberate difference from a real login: NO
 * password — the tour switches between the seeded users to explore
 * WHO MAY DO WHAT, and authorization assumes authentication already
 * happened. A real deployment would never ship a passwordless switch;
 * the demo island documents its own exception.
 *
 * The principal placed in the context is Spring's UserDetails (resolved
 * through the baseline's UserDetailsService), so authentication.getName()
 * is the username for every mechanism-agnostic check (@PostAuthorize's
 * authentication.name included).
 */
public final class ActAsSessions {

    private final IdentityStore identityStore;
    private final UserDetailsService userDetailsService;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public ActAsSessions(IdentityStore identityStore, UserDetailsService userDetailsService) {
        this.identityStore = identityStore;
        this.userDetailsService = userDetailsService;
    }

    /** Establish a session for one of the seeded users; returns the domain view. */
    public UserPrincipal actAs(String username, HttpServletRequest request,
            HttpServletResponse response) {
        UserDetails details;
        try {
            details = userDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException unknown) {
            throw new AppException(Failure.of(FailureKind.NOT_FOUND,
                    "unknown user '" + username + "' — act-as switches between alice, bob, carol"));
        }
        UserPrincipal user = identityStore.findByUsername(username).orElseThrow();
        // fixation protection, mirrored from topic 23's login: identity change
        // rotates the session id — attributes survive, the old id dies
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        // authorities in the app's canonical convention (topic 23's session
        // glue): ROLE_ + UPPERCASE role. The UserDetailsService's .roles()
        // helper only PREFIXES — it does not uppercase, and hasRole matching
        // is case-sensitive: ROLE_admin fails hasRole('ADMIN') silently.
        List<SimpleGrantedAuthority> authorities = user.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(details, null, authorities);
        authentication.setDetails("session (act-as)");
        context.setAuthentication(authentication);
        securityContextRepository.saveContext(context, request, response);
        return user;
    }
}
