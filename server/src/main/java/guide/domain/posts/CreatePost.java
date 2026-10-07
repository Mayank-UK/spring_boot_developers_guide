package guide.domain.posts;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Objects;

/**
 * Use case: create a post from a draft. The draft has already been judged at
 * the edge (PostDraft.of) and re-judged at birth (its constructor), so this
 * use case's job is the transactional seam: identity, version 1, and first
 * timestamps are assigned exactly once, by the adapter, behind the port.
 */
public class CreatePost {

    private final PostRepository repository;

    public CreatePost(PostRepository repository) {
        this.repository = repository;
    }

    public Result<Post> execute(PostDraft draft) {
        if (draft == null) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "a creation needs a draft (title, body)", null));
        }
        return Result.success(repository.create(draft));
    }
}
