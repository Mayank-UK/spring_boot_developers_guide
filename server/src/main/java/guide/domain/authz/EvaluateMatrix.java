package guide.domain.authz;

import guide.domain.auth.IdentityStore;
import guide.domain.auth.UserPrincipal;
import java.util.ArrayList;
import java.util.List;

/**
 * Use case behind GET /api/demo/authz/matrix: render the FULL verdict
 * table — every seeded user × every known action × every seeded document
 * — straight from the real policies. Nothing here is hardcoded: the rows
 * are computed by the same DenyByDefault evaluator the guarded endpoints
 * consult, so the rendered matrix and the live behavior CANNOT drift
 * apart (a table copy-pasted into prose could; this one cannot).
 *
 * The role-axis summary (role → allowed actions) rides along because
 * RBAC's first teaching is that the matrix is DATA — three roles, three
 * actions, one map to read.
 */
public final class EvaluateMatrix {

    /** One cell of the table: user × action × document → verdict + why. */
    public record MatrixRow(String username, List<String> roles, String action,
            String documentId, String documentTitle, String documentOwner, String visibility,
            boolean allowed, String checkedBy, String reason) {
    }

    /** The rendered matrix: role summary + every user×action×document verdict. */
    public record MatrixView(List<String> actions,
            List<RoleSummary> roleMatrix, List<UserRow> users, List<MatrixRow> rows) {
    }

    /** The RBAC section's table: one role, the actions its bundle allows. */
    public record RoleSummary(String role, List<String> allowedActions) {
    }

    /** A seeded user and their role-level action summary (any-role OR). */
    public record UserRow(String username, List<String> roles, List<String> actionSummary) {
    }

    private final IdentityStore identityStore;
    private final DocumentStore documentStore;
    private final RolePolicy rolePolicy;
    private final DenyByDefault evaluator;

    public EvaluateMatrix(IdentityStore identityStore, DocumentStore documentStore,
            RolePolicy rolePolicy, DenyByDefault evaluator) {
        this.identityStore = identityStore;
        this.documentStore = documentStore;
        this.rolePolicy = rolePolicy;
        this.evaluator = evaluator;
    }

    public MatrixView execute() {
        List<RoleSummary> roleMatrix = rolePolicy.knownRoles().stream()
                .map(role -> new RoleSummary(role, rolePolicy.actionsAllowed(role).stream()
                        .map(AuthzAction::wireName).toList()))
                .toList();
        List<OwnedDocument> documents = documentStore.findAll();
        List<UserPrincipal> users = List.of("alice", "bob", "carol").stream()
                .map(identityStore::findByUsername)
                .flatMap(java.util.Optional::stream)
                .toList();
        List<UserRow> userRows = users.stream()
                .map(user -> new UserRow(user.username(), user.roles(),
                        rolePolicy.knownActions().stream()
                                .filter(action -> rolePolicy.anyRoleAllows(user.roles(), action))
                                .map(AuthzAction::wireName).toList()))
                .toList();
        List<MatrixRow> rows = new ArrayList<>();
        for (UserPrincipal user : users) {
            for (AuthzAction action : rolePolicy.knownActions()) {
                for (OwnedDocument document : documents) {
                    DenyByDefault.Verdict verdict = evaluator.decide(user, action.wireName(), document);
                    rows.add(new MatrixRow(user.username(), user.roles(), action.wireName(),
                            document.id(), document.title(), document.ownerId(),
                            document.visibility().wireName(), verdict.allowed(),
                            verdict.checkedBy(), verdict.reason()));
                }
            }
        }
        return new MatrixView(
                rolePolicy.knownActions().stream().map(AuthzAction::wireName).toList(),
                roleMatrix, userRows, List.copyOf(rows));
    }
}
