package guide.domain.posts;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Use case: list posts — one page of a filtered, sorted listing. The paging
 * bounds (page 0-based, size 1..100) are this use case's own contract: an
 * unbounded size is a denial-of-service invitation, and "which page" beyond a
 * sane ceiling is a client bug, so both are judged here as validation
 * failures rather than trusted from the wire.
 */
public class ListPosts {

    /** The ceiling on ?size — a listing API must decide one, and document it. */
    public static final int MAX_PAGE_SIZE = 100;

    private final PostRepository repository;

    public ListPosts(PostRepository repository) {
        this.repository = repository;
    }

    public Result<PagedPosts> execute(int page, int size, PostSort sort, PostFilter filter) {
        Objects.requireNonNull(sort, "sort");
        Objects.requireNonNull(filter, "filter");
        Map<String, String> problems = new LinkedHashMap<>();
        if (page < 0) {
            problems.put("page", "is 0-based and cannot be negative");
        }
        if (size < 1) {
            problems.put("size", "must be at least 1");
        } else if (size > MAX_PAGE_SIZE) {
            problems.put("size", "must be at most " + MAX_PAGE_SIZE);
        }
        if (!problems.isEmpty()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "listing parameters failed validation", Map.of("fields", problems)));
        }
        return Result.success(repository.list(page, size, sort, filter));
    }
}
