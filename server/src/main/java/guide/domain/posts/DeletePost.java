package guide.domain.posts;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * Use case: delete a post. Idempotent at the resource level (a second delete
 * of the same id finds nothing), but NOT silently: the first delete answers
 * 204, the second answers 404 — a client that cannot tell those apart cannot
 * tell "I deleted it" from "I never could".
 */
public class DeletePost {

    private final PostRepository repository;

    public DeletePost(PostRepository repository) {
        this.repository = repository;
    }

    public Result<Void> execute(String id) {
        if (id == null || id.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "post id must be a non-empty string", Map.of("parameter", "id")));
        }
        return repository.delete(id)
                ? Result.success(null)
                : Result.failed(Failure.of(FailureKind.NOT_FOUND,
                        "no post with id \"" + id + "\"", Map.of("postId", id)));
    }
}
