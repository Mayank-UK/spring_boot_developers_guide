package guide.domain.auth;

import java.util.List;

/**
 * The authenticated party, as the domain sees it: a stable id, the login
 * name, the role set (topic 24's authorization material — plain "admin",
 * "staff", "reader", never the ROLE_ prefix Spring adds on the wire), and
 * the password HASH. The hash lives here because the identity substrate
 * owns verification; it never leaves the domain — no view, DTO, or
 * response may carry it.
 */
public record UserPrincipal(String id, String username, List<String> roles, String passwordHash) {

    public UserPrincipal {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(username, "username");
        roles = List.copyOf(roles);
        java.util.Objects.requireNonNull(passwordHash, "passwordHash");
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
