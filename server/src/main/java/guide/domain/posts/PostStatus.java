package guide.domain.posts;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A post's lifecycle state. Drafts are private works-in-progress; published
 * posts are visible to readers. Serialized on the wire as the lowercase word
 * ("draft" / "published") — the enum's Java name never leaks.
 */
public enum PostStatus {

    DRAFT("draft"),
    PUBLISHED("published");

    private final String wireName;

    PostStatus(String wireName) {
        this.wireName = wireName;
    }

    /** Serialized on the wire as the lowercase word, e.g. "published". */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** Parses the wire spelling; unknown values throw (callers at the edge turn that into a validation failure). */
    public static PostStatus fromWire(String wire) {
        for (PostStatus status : values()) {
            if (status.wireName.equals(wire)) {
                return status;
            }
        }
        throw new IllegalArgumentException(
                "unknown post status \"" + wire + "\" — expected \"draft\" or \"published\"");
    }
}
