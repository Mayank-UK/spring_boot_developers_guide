package guide.domain.authz;

import guide.domain.auth.UserPrincipal;

/**
 * The OBJECT-level policy: may this caller act on THIS document? Roles
 * answer "may users like you do things like this"; ownership answers
 * "may you do it to THIS row". Both questions exist because neither
 * subsumes the other — a staff role without ownership must not edit a
 * stranger's private document, and an owner must not need the staff role
 * to edit their own.
 *
 * The three rules, in precedence order:
 * - OWNER: the caller IS the ownerId — full control over their rows.
 * - ADMIN: the admin role reads and publishes anything (the escape hatch
 *   every real system needs, and every audit log must show).
 * - TEAM + TEAM VISIBILITY: the team role ("staff" here) may READ
 *   documents explicitly shared with the team — a narrower grant than
 *   it looks: it never reaches PRIVATE rows and never grants PUBLISH.
 *
 * PUBLIC visibility is not an ownership rule but a read rule: anyone
 * authenticated may read. Reason strings ride every verdict so the demo
 * can show WHY, not just whether.
 */
public final class OwnershipPolicy {

    /** The role the TEAM visibility shares with. */
    public static final String TEAM_ROLE = "staff";

    public boolean canRead(UserPrincipal caller, OwnedDocument document) {
        return switch (document.visibility()) {
            case PUBLIC -> true;
            case PRIVATE, TEAM -> caller.username().equals(document.ownerId())
                    || caller.hasRole("admin")
                    || (document.visibility() == OwnedDocument.Visibility.TEAM
                            && caller.hasRole(TEAM_ROLE));
        };
    }

    /** Publish is owner-or-admin, FULL STOP — the team role never grants it. */
    public boolean canPublish(UserPrincipal caller, OwnedDocument document) {
        return caller.username().equals(document.ownerId()) || caller.hasRole("admin");
    }

    /** The why behind a read verdict — the demo renders these verbatim. */
    public String readReason(UserPrincipal caller, OwnedDocument document) {
        if (document.visibility() == OwnedDocument.Visibility.PUBLIC) {
            return "public document — readable by any authenticated caller";
        }
        if (caller.username().equals(document.ownerId())) {
            return "caller owns this document";
        }
        if (caller.hasRole("admin")) {
            return "admin may read any document (audited escape hatch)";
        }
        if (document.visibility() == OwnedDocument.Visibility.TEAM && caller.hasRole(TEAM_ROLE)) {
            return "team visibility + the " + TEAM_ROLE + " role";
        }
        return switch (document.visibility()) {
            case PRIVATE -> "private to " + document.ownerId() + " (and admins) — caller is neither";
            case TEAM -> "team-shared, but the caller lacks the " + TEAM_ROLE + " role";
            case PUBLIC -> "unreachable";
        };
    }

    /** The why behind a publish verdict. */
    public String publishReason(UserPrincipal caller, OwnedDocument document) {
        if (caller.username().equals(document.ownerId())) {
            return "caller owns this document";
        }
        if (caller.hasRole("admin")) {
            return "admin may publish any document (audited escape hatch)";
        }
        return "publish is owner-or-admin — " + caller.username() + " is neither on '"
                + document.id() + "'";
    }
}
