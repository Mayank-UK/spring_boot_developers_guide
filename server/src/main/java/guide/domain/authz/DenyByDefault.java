package guide.domain.authz;

import guide.domain.auth.UserPrincipal;

/**
 * The fail-closed evaluator: one method that turns (caller, action,
 * object) into a verdict, and DENIES unless a policy explicitly allows.
 * Every layer of this topic's demo ultimately routes through here or
 * mirrors it — the discipline is the same at every layer:
 *
 * - UNKNOWN ACTION → deny. The wire sends strings; a string outside
 *   AuthzAction's closed set is a deny, never an exception, never an
 *   optimistic default. New endpoints must REGISTER their action or
 *   they deny everyone — the safe direction.
 * - NO ROLE ALLOWS IT → deny (RolePolicy, itself fail-closed on
 *   unknown roles).
 * - THE OBJECT POLICY DENIES → deny (OwnershipPolicy).
 * - Only explicit allowance allows.
 *
 * The verdict carries WHICH policy answered and WHY — authorization
 * decisions that cannot explain themselves cannot be audited, and
 * unauditable authz is unfixable authz.
 */
public final class DenyByDefault {

    /** One decision, with the layer that produced it and its reason. */
    public record Verdict(boolean allowed, String checkedBy, String reason) {

        static Verdict allow(String checkedBy, String reason) {
            return new Verdict(true, checkedBy, reason);
        }

        static Verdict deny(String checkedBy, String reason) {
            return new Verdict(false, checkedBy, reason);
        }
    }

    private final RolePolicy rolePolicy;
    private final OwnershipPolicy ownershipPolicy;

    public DenyByDefault(RolePolicy rolePolicy, OwnershipPolicy ownershipPolicy) {
        this.rolePolicy = rolePolicy;
        this.ownershipPolicy = ownershipPolicy;
    }

    /**
     * The verdict for one caller acting on one document. Read top to
     * bottom: every early return except the last is a DENY — that shape
     * IS the fail-closed property.
     */
    public Verdict decide(UserPrincipal caller, String action, OwnedDocument document) {
        AuthzAction known = AuthzAction.fromWire(action).orElse(null);
        if (known == null) {
            return Verdict.deny("deny-by-default",
                    "unknown action '" + action + "' — not in the matrix, denied (fail closed)");
        }
        if (!rolePolicy.anyRoleAllows(caller.roles(), known)) {
            return Verdict.deny("role-policy",
                    "no role of " + caller.username() + " allows '" + known.wireName() + "'");
        }
        return switch (known) {
            case READ -> ownershipPolicy.canRead(caller, document)
                    ? Verdict.allow("ownership-policy", ownershipPolicy.readReason(caller, document))
                    : Verdict.deny("ownership-policy",
                            ownershipPolicy.readReason(caller, document));
            case PUBLISH -> ownershipPolicy.canPublish(caller, document)
                    ? Verdict.allow("ownership-policy",
                            ownershipPolicy.publishReason(caller, document))
                    : Verdict.deny("ownership-policy",
                            ownershipPolicy.publishReason(caller, document));
            // list is not an object-level action: the matrix verdict is the verdict
            case LIST -> Verdict.allow("role-policy", "list allowed; visibility filters the rows");
        };
    }
}
