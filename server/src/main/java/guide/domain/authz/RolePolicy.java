package guide.domain.authz;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ROLE→ACTION matrix: role-based access control as pure data. A role
 * is a named bundle of allowed actions; the matrix is the whole RBAC
 * model, readable in one place — which is the point. When authorization
 * questions become arguments ("can staff publish?"), the answer is this
 * map, not a meeting.
 *
 * Deny-by-default lives here too, at the role axis: an UNKNOWN role
 * allows nothing, and a known role allows exactly its listed actions.
 * The matrix is deliberately small (three roles, three actions) — the
 * designing lesson is that matrices grow combinatorially with both axes,
 * so both stay as tiny as the product honestly needs.
 */
public final class RolePolicy {

    private final Map<String, Set<AuthzAction>> matrix;

    private RolePolicy(Map<String, Set<AuthzAction>> matrix) {
        this.matrix = matrix;
    }

    /** The demo matrix: reader reads, staff reads and publishes, admin does both. */
    public static RolePolicy seeded() {
        Map<String, Set<AuthzAction>> matrix = new LinkedHashMap<>();
        matrix.put("reader", new LinkedHashSet<>(List.of(AuthzAction.READ, AuthzAction.LIST)));
        matrix.put("staff", new LinkedHashSet<>(List.of(
                AuthzAction.READ, AuthzAction.LIST, AuthzAction.PUBLISH)));
        matrix.put("admin", new LinkedHashSet<>(List.of(
                AuthzAction.READ, AuthzAction.LIST, AuthzAction.PUBLISH)));
        return new RolePolicy(matrix);
    }

    /** May this ROLE perform this action? Unknown roles allow nothing — fail closed. */
    public boolean roleAllows(String role, AuthzAction action) {
        return matrix.getOrDefault(role, Set.of()).contains(action);
    }

    /** May ANY of the caller's roles perform the action (multi-role users)? */
    public boolean anyRoleAllows(List<String> roles, AuthzAction action) {
        return roles != null && roles.stream().anyMatch(role -> roleAllows(role, action));
    }

    /** The actions one role allows — for rendering the matrix, never for deciding. */
    public Set<AuthzAction> actionsAllowed(String role) {
        return Set.copyOf(matrix.getOrDefault(role, Set.of()));
    }

    /** All known roles, declaration order — for rendering. */
    public List<String> knownRoles() {
        return List.copyOf(matrix.keySet());
    }

    /** All known actions across the matrix — for rendering. */
    public List<AuthzAction> knownActions() {
        Set<AuthzAction> actions = new LinkedHashSet<>();
        matrix.values().forEach(actions::addAll);
        return List.copyOf(actions);
    }
}
