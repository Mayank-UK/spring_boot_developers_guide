package guide.presentation.security;

import guide.domain.auth.ApiKeyRecord;
import guide.domain.auth.IdentityStore;
import guide.domain.auth.OpaqueTokens;
import guide.domain.auth.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The X-API-Key filter: one header in, one principal out. It sits in the
 * chain BEFORE the username/password machinery and only acts when a key
 * is presented AND nothing has authenticated yet — it ADDS a mechanism,
 * never overrides another (a Bearer token wins because the resource
 * server runs first in this app's ordering).
 *
 * The lookup is by SHA-256 of the presented key: the store holds hashes,
 * so a database dump mints nothing. A WRONG key is indistinguishable
 * from an absent one — no 401-with-detail from the filter itself; the
 * endpoint's authorization rule answers the same 401 either way, and no
 * oracle leaks which part failed.
 */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final IdentityStore identityStore;

    public ApiKeyAuthenticationFilter(IdentityStore identityStore) {
        this.identityStore = identityStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String presentedKey = request.getHeader(HEADER);
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (presentedKey != null && !presentedKey.isBlank()
                && (existing == null || !existing.isAuthenticated())) {
            identityStore.findApiKeyByHash(OpaqueTokens.sha256Hex(presentedKey))
                    .ifPresent(record -> authenticate(request, record, presentedKey));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, ApiKeyRecord record, String presentedKey) {
        UserPrincipal user = identityStore.findByUsername(record.username()).orElse(null);
        if (user == null) {
            return; // key points at a deleted account — behaves like an unknown key
        }
        identityStore.markApiKeyUsed(record.keyHash(), java.time.Instant.now());
        List<SimpleGrantedAuthority> authorities = user.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(java.util.Locale.ROOT)))
                .toList();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user, null, authorities);
        authentication.setDetails("api-key: " + record.redacted());
        request.setAttribute("guide.auth.via", "api-key");
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
