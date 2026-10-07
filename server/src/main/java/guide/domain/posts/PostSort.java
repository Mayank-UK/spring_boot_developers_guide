package guide.domain.posts;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Comparator;
import java.util.Map;

/**
 * A typed sort order: which field, which direction. The URL spelling
 * ("?sort=title,asc") parses HERE — a value object owns its own wire format —
 * so the controller binds a string and receives either a typed order or a
 * per-field validation failure. The comparator (with the id tie-break that
 * keeps equal keys deterministically ordered) is part of the contract, which
 * is why the in-memory adapter and a future SQL adapter can share the field
 * list yet speak their own dialect.
 */
public record PostSort(Field field, boolean descending) {

    /** The fields posts may be ordered by — anything else is a validation failure, not a 500. */
    public enum Field {
        TITLE("title"),
        CREATED_AT("createdAt"),
        UPDATED_AT("updatedAt");

        private final String wireName;

        Field(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /** The API's default order: newest first. Every list endpoint that omits ?sort gets this. */
    public static final PostSort DEFAULT = new PostSort(Field.CREATED_AT, true);

    private static final String FIELDS_HELP = "must be one of title, createdAt, updatedAt "
            + "(optionally \",asc\" or \",desc\" — default asc)";

    /** Parses "?sort=field,direction"; null/blank yields the default order, anything malformed a Failed Result. */
    public static Result<PostSort> parse(String wire) {
        if (wire == null || wire.isBlank()) {
            return Result.success(DEFAULT);
        }
        String[] parts = wire.strip().split(",", 2);
        Field field = fieldFor(parts[0]);
        if (field == null) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "sort " + FIELDS_HELP, Map.of("fields", Map.of("sort", FIELDS_HELP))));
        }
        boolean descending = false;
        if (parts.length == 2 && !parts[1].isBlank()) {
            String direction = parts[1].strip().toLowerCase(java.util.Locale.ROOT);
            if (!direction.equals("asc") && !direction.equals("desc")) {
                return Result.failed(Failure.of(FailureKind.VALIDATION,
                        "sort direction must be asc or desc", Map.of("fields",
                                Map.of("sort", "direction must be \"asc\" or \"desc\""))));
            }
            descending = direction.equals("desc");
        }
        return Result.success(new PostSort(field, descending));
    }

    private static Field fieldFor(String wire) {
        for (Field field : Field.values()) {
            if (field.wireName.equals(wire.strip())) {
                return field;
            }
        }
        return null;
    }

    /** The total order: the chosen field, then id as the tie-break so equal keys stay deterministic. */
    public Comparator<Post> comparator() {
        Comparator<Post> primary = switch (field) {
            case TITLE -> Comparator.comparing(Post::title, String.CASE_INSENSITIVE_ORDER);
            case CREATED_AT -> Comparator.comparing(Post::createdAt);
            case UPDATED_AT -> Comparator.comparing(Post::updatedAt);
        };
        Comparator<Post> withTieBreak = primary.thenComparing(Post::id);
        return descending ? withTieBreak.reversed() : withTieBreak;
    }
}
