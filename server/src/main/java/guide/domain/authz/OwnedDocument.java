package guide.domain.authz;

import java.util.Locale;

/**
 * The authorization substrate's resource: a document OWNED by one user,
 * with a VISIBILITY that decides who else may see it. Every object-level
 * check in this topic (OwnershipPolicy, the @authz bean, the filtered
 * list) reasons over exactly these five fields — the point of the
 * substrate is that "may X act on Y" questions have ONE honest answer
 * shape: the principal, the action, the object, the verdict.
 *
 * Visibility is the row-level knob: PRIVATE = owner (and admin) only,
 * TEAM = shareable with the team role, PUBLIC = any authenticated caller.
 */
public record OwnedDocument(String id, String ownerId, String title, String body, Visibility visibility) {

    public OwnedDocument {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(ownerId, "ownerId");
        java.util.Objects.requireNonNull(title, "title");
        java.util.Objects.requireNonNull(body, "body");
        java.util.Objects.requireNonNull(visibility, "visibility");
    }

    /** The row-level visibility knob; wire form is the lowercase name. */
    public enum Visibility {
        PRIVATE, TEAM, PUBLIC;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Parse the wire form; null-safe and case-insensitive, null when unknown. */
        public static Visibility fromWire(String value) {
            if (value == null) {
                return null;
            }
            for (Visibility visibility : values()) {
                if (visibility.wireName().equals(value.toLowerCase(Locale.ROOT))) {
                    return visibility;
                }
            }
            return null;
        }
    }

    /** A copy with a new visibility — publish is a visibility transition. */
    public OwnedDocument withVisibility(Visibility next) {
        return new OwnedDocument(id, ownerId, title, body, next);
    }
}
