package guide.domain.posts;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;
import java.util.Objects;

/**
 * Use case: replace a post's content under optimistic concurrency. The caller
 * states the version it READ (from the representation or its ETag); the
 * repository accepts only if that is still the current version. The three
 * outcomes map one-to-one onto the wire: Updated → 200 with the new
 * representation, NotFound → 404 envelope, StaleVersion → 409 envelope
 * carrying both versions so the client can decide to re-read and retry.
 */
public class UpdatePost {

    private final PostRepository repository;

    public UpdatePost(PostRepository repository) {
        this.repository = repository;
    }

    public Result<Post> execute(String id, long expectedVersion, PostDraft draft) {
        if (id == null || id.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "post id must be a non-empty string", Map.of("parameter", "id")));
        }
        if (expectedVersion < 1) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "expectedVersion must be at least 1", Map.of("parameter", "expectedVersion")));
        }
        Objects.requireNonNull(draft, "draft");
        return switch (repository.update(id, expectedVersion, draft)) {
            case PostRepository.Updated updated -> Result.success(updated.post());
            case PostRepository.NotFound notFound -> Result.failed(Failure.of(FailureKind.NOT_FOUND,
                    "no post with id \"" + id + "\"", Map.of("postId", id)));
            case PostRepository.StaleVersion stale -> Result.failed(Failure.of(FailureKind.CONFLICT,
                    "post \"" + id + "\" changed since you read it: expected version "
                            + expectedVersion + ", current version " + stale.currentVersion(),
                    Map.of("expectedVersion", expectedVersion, "currentVersion", stale.currentVersion())));
        };
    }
}
