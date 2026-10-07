package guide.domain.posts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The posts use cases over a SCRIPTED fake port: each test drives one port
 * outcome and asserts the Result mapping the edge will lean on — tri-state
 * update becomes 404/409-shaped failures, bounds become validation failures,
 * and nothing below the presentation layer ever invents a status number.
 */
class PostsUseCasesTest {

    /** A fake whose every answer the test sets — the port contract, verbatim. */
    private static final class ScriptedRepository implements PostRepository {
        PagedPosts listAnswer = PagedPosts.of(List.of(), 0, 10, 0);
        Optional<Post> findAnswer = Optional.empty();
        Post createAnswer;
        Update updateAnswer = new NotFound();
        boolean deleteAnswer;

        @Override
        public PagedPosts list(int page, int size, PostSort sort, PostFilter filter) {
            return listAnswer;
        }

        @Override
        public Optional<Post> findById(String id) {
            return findAnswer;
        }

        @Override
        public Post create(PostDraft draft) {
            return createAnswer;
        }

        @Override
        public Update update(String id, long expectedVersion, PostDraft draft) {
            return updateAnswer;
        }

        @Override
        public boolean delete(String id) {
            return deleteAnswer;
        }
    }

    private static final PostDraft DRAFT =
            new PostDraft("T", "B", List.of(), PostStatus.DRAFT);
    private static final Post POST = Post.initial("p1", DRAFT, Instant.EPOCH);

    private final ScriptedRepository repository = new ScriptedRepository();

    // ---- ListPosts ----

    @Test
    void listJudgesPagingBoundsAsValidationFailures() {
        ListPosts listPosts = new ListPosts(repository);

        assertTrue(listPosts.execute(-1, 10, PostSort.DEFAULT, PostFilter.NONE)
                instanceof Failed<?>);
        assertTrue(listPosts.execute(0, 0, PostSort.DEFAULT, PostFilter.NONE)
                instanceof Failed<?>);
        assertTrue(listPosts.execute(0, ListPosts.MAX_PAGE_SIZE + 1, PostSort.DEFAULT,
                PostFilter.NONE) instanceof Failed<?>);

        Result<PagedPosts> sizeAtLimit = listPosts.execute(0, ListPosts.MAX_PAGE_SIZE,
                PostSort.DEFAULT, PostFilter.NONE);
        assertTrue(sizeAtLimit instanceof Success<PagedPosts>, "size = the ceiling is legal");

        Failed<PagedPosts> tooBig = (Failed<PagedPosts>) listPosts.execute(0, 101,
                PostSort.DEFAULT, PostFilter.NONE);
        assertEquals(FailureKind.VALIDATION, tooBig.failure().kind());
        assertTrue(tooBig.failure().details().get("fields").toString().contains("size"));
    }

    // ---- GetPost ----

    @Test
    void getPostMapsAbsenceToANotFoundFailureCarryingTheId() {
        GetPost getPost = new GetPost(repository);
        repository.findAnswer = Optional.empty();

        Result<Post> result = getPost.execute("missing");
        assertTrue(result instanceof Failed<Post>);
        Failed<Post> failed = (Failed<Post>) result;
        assertEquals(FailureKind.NOT_FOUND, failed.failure().kind());
        assertEquals("missing", failed.failure().details().get("postId"));
    }

    @Test
    void getPostReturnsThePostWhenThePortFindsIt() {
        GetPost getPost = new GetPost(repository);
        repository.findAnswer = Optional.of(POST);

        assertEquals(POST, ((Success<Post>) getPost.execute("p1")).value());
    }

    // ---- CreatePost ----

    @Test
    void createReturnsWhatTheAdapterStored() {
        repository.createAnswer = POST;

        assertEquals(POST, ((Success<Post>) new CreatePost(repository).execute(DRAFT)).value());
        assertTrue(new CreatePost(repository).execute(null) instanceof Failed<?>);
    }

    // ---- UpdatePost: the tri-state mapping ----

    @Test
    void staleVersionBecomesAConflictFailureNamingBothVersions() {
        repository.updateAnswer = new PostRepository.StaleVersion(7);

        Result<Post> result = new UpdatePost(repository).execute("p1", 3, DRAFT);
        assertTrue(result instanceof Failed<Post>);
        Failed<Post> failed = (Failed<Post>) result;
        assertEquals(FailureKind.CONFLICT, failed.failure().kind());
        assertEquals(3L, failed.failure().details().get("expectedVersion"));
        assertEquals(7L, failed.failure().details().get("currentVersion"));
    }

    @Test
    void missingPostBecomesNotFoundAndUpdatedPassesThrough() {
        repository.updateAnswer = new PostRepository.NotFound();
        UpdatePost updatePost = new UpdatePost(repository);

        Failed<Post> missing = (Failed<Post>) updatePost.execute("nope", 1, DRAFT);
        assertEquals(FailureKind.NOT_FOUND, missing.failure().kind());

        Post revised = POST.revised(DRAFT, Instant.EPOCH.plusSeconds(1));
        repository.updateAnswer = new PostRepository.Updated(revised);
        assertEquals(revised, ((Success<Post>) updatePost.execute("p1", 1, DRAFT)).value());
    }

    // ---- DeletePost ----

    @Test
    void deleteSucceedsWithNoValueAndFailsNotFoundWhenAbsent() {
        DeletePost deletePost = new DeletePost(repository);

        repository.deleteAnswer = true;
        Result<Void> deleted = deletePost.execute("p1");
        assertTrue(deleted instanceof Success<Void>);
        assertNull(((Success<Void>) deleted).value());

        repository.deleteAnswer = false;
        Failed<Void> missing = (Failed<Void>) deletePost.execute("p1");
        assertEquals(FailureKind.NOT_FOUND, missing.failure().kind());
    }
}
