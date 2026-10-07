package guide.domain.posts;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * Use case: fetch one post by id. Unknown ids come back as a Failed Result
 * carrying a not-found failure — the presentation edge decides that this means
 * 404 plus the standard envelope, exactly as GetTopicById does for topics.
 */
public class GetPost {

    private final PostRepository repository;

    public GetPost(PostRepository repository) {
        this.repository = repository;
    }

    public Result<Post> execute(String id) {
        if (id == null || id.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "post id must be a non-empty string", Map.of("parameter", "id")));
        }
        return repository.findById(id)
                .map(Result::<Post>success)
                .orElseGet(() -> Result.failed(Failure.of(FailureKind.NOT_FOUND,
                        "no post with id \"" + id + "\"", Map.of("postId", id))));
    }
}
