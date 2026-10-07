package guide.domain.posts;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * A posts listing filter: which statuses, which tag. Null means "no
 * constraint" — a filter that constrains nothing is {@link #NONE}, and
 * repositories skip null checks rather than inventing an ALL sentinel. Tag
 * matching is exact (tags are lowercase by convention in the seed; the prose
 * says so, because whether filtering is case-sensitive is exactly the kind of
 * thing an API must document rather than let clients discover).
 */
public record PostFilter(PostStatus status, String tag) {

    /** Passes every post — the filter an unfiltered listing uses. */
    public static final PostFilter NONE = new PostFilter(null, null);

    public PostFilter {
        if (tag != null) {
            tag = tag.strip();
            if (tag.isEmpty()) {
                tag = null;
            }
        }
    }

    /** Parses the query params; an unknown status spelling is a Failed Result, not a silent empty listing. */
    public static Result<PostFilter> of(String statusWire, String tag) {
        if (statusWire == null || statusWire.isBlank()) {
            return Result.success(new PostFilter(null, tag));
        }
        try {
            return Result.success(new PostFilter(PostStatus.fromWire(statusWire.strip()), tag));
        } catch (IllegalArgumentException e) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "status must be \"draft\" or \"published\"",
                    Map.of("fields", Map.of("status", "must be \"draft\" or \"published\""))));
        }
    }

    /** The filter's executable meaning — the in-memory adapter's entire filtering step. */
    public boolean matches(Post post) {
        if (status != null && post.status() != status) {
            return false;
        }
        return tag == null || post.tags().contains(tag);
    }
}
