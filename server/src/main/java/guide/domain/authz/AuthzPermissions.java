package guide.domain.authz;

import guide.domain.auth.UserPrincipal;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The bean method security's SpEL calls — registered as the bean NAMED
 * "authz", so @PreAuthorize("@authz.canPublish(#id, authentication)")
 * reaches real policy instead of inline role string-matching. This is
 * the seam where framework annotation meets domain policy: the
 * annotation decides WHEN to ask, this bean knows the ANSWER, and the
 * answer is OwnershipPolicy — never a second opinion invented here.
 *
 * The principal-shape adapter at the bottom is the honest cost of a
 * multi-mechanism app: session logins carry the domain UserPrincipal,
 * the act-as switch and form logins carry Spring's UserDetails, JWT
 * requests carry the verified token — authorization wants ONE shape,
 * so it resolves one. (AuthorizationDeniedException vs the HTTP layer
 * is handled by the demo's advice; this class stays web-free.)
 */
public final class AuthzPermissions {

    private final DocumentStore documentStore;
    private final OwnershipPolicy ownershipPolicy;

    public AuthzPermissions(DocumentStore documentStore, OwnershipPolicy ownershipPolicy) {
        this.documentStore = documentStore;
        this.ownershipPolicy = ownershipPolicy;
    }

    /** The @authz bean check the publish route composes with hasAnyRole. */
    public boolean canPublish(String documentId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false; // anonymous or unresolved → deny, fail closed
        }
        OwnedDocument document = documentStore.findById(documentId).orElse(null);
        if (document == null) {
            return false; // unknown object → deny (the 404 happens in the use case after the gate)
        }
        return ownershipPolicy.canPublish(asPrincipal(authentication), document);
    }

    /**
     * Resolve any mechanism's principal into the domain shape the policy
     * speaks (public: the island's controller resolves its caller the same
     * way — one adapter, two users). The passwordHash slot is a non-null
     * sentinel — this synthetic principal exists for POLICY only and never
     * leaks.
     */
    public static UserPrincipal asPrincipal(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal user) {
            return user;
        }
        if (principal instanceof UserDetails details) {
            return new UserPrincipal("usr_" + details.getUsername(), details.getUsername(),
                    rolesFromAuthorities(details.getAuthorities()), "policy-only-synthetic");
        }
        return new UserPrincipal("usr_" + authentication.getName(), authentication.getName(),
                rolesFromAuthorities(authentication.getAuthorities()), "policy-only-synthetic");
    }

    private static List<String> rolesFromAuthorities(
            Iterable<? extends org.springframework.security.core.GrantedAuthority> authorities) {
        java.util.List<String> roles = new java.util.ArrayList<>();
        for (org.springframework.security.core.GrantedAuthority authority : authorities) {
            String name = authority.getAuthority();
            if (name.startsWith("ROLE_")) {
                roles.add(name.substring("ROLE_".length()).toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(roles);
    }
}
