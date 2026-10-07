package guide.presentation.graphql;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.data.comments.InMemoryCommentRepository;
import guide.domain.comments.Comment;
import guide.domain.comments.CommentRepository;
import guide.domain.posts.CreatePost;
import guide.domain.posts.DeletePost;
import guide.domain.posts.GetPost;
import guide.domain.posts.ListPosts;
import guide.domain.posts.PagedPosts;
import guide.domain.posts.Post;
import guide.domain.posts.PostDraft;
import guide.domain.posts.PostFilter;
import guide.domain.posts.PostSort;
import guide.domain.posts.PostStatus;
import guide.domain.posts.UpdatePost;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * The posts GRAPH — the same substrate the REST API serves, expressed as
 * GraphQL resolvers. Every rule from topic 11 carries over unchanged because
 * nothing below this class changed: queries and mutations delegate to the
 * SAME use cases (ListPosts, GetPost, CreatePost, UpdatePost, DeletePost),
 * failures surface as AppException which the {@link GraphqlFailureResolver}
 * turns into errors[].extensions (code + requestId — the taxonomy preserved
 * across protocols), and the ONLY new idea here is association loading:
 * Post.comments, served by a {@code @BatchMapping}.
 *
 * THE N+1 LESSON LIVES IN THIS FILE, as two resolvers side by side:
 * - {@link #comments} — the {@code @BatchMapping} that ACTUALLY serves the
 *   field: one call for the whole page of posts, keys batched.
 * - {@link #commentsTheNaiveWay} — the per-item trap, deliberately NOT
 *   annotated (annotating it would register a second data fetcher for the
 *   same field). The prose dissects it; the tests invoke it directly to
 *   show its cost — N posts, N repository round trips.
 */
@Controller
public class PostsGraphqlController {

    private final GetPost getPost;
    private final ListPosts listPosts;
    private final CreatePost createPost;
    private final UpdatePost updatePost;
    private final DeletePost deletePost;
    private final CommentRepository commentRepository;

    public PostsGraphqlController(GetPost getPost, ListPosts listPosts, CreatePost createPost,
            UpdatePost updatePost, DeletePost deletePost, CommentRepository commentRepository) {
        this.getPost = getPost;
        this.listPosts = listPosts;
        this.createPost = createPost;
        this.updatePost = updatePost;
        this.deletePost = deletePost;
        this.commentRepository = commentRepository;
    }

    // ---- Query: the read side, two fields, two use cases ----

    /** post(id) — a nullable field in the schema, but THIS edge prefers the typed error. */
    @QueryMapping
    public Post post(@Argument String id) {
        return unwrap(getPost.execute(id));
    }

    /** posts(page, size, status) — the same listing contract as GET /api/demo/api/posts. */
    @QueryMapping
    public PagedPosts posts(@Argument Integer page, @Argument Integer size,
            @Argument PostStatus status) {
        return unwrap(listPosts.execute(
                page == null ? 0 : page,
                size == null ? 10 : size,
                PostSort.DEFAULT,
                status == null ? PostFilter.NONE : new PostFilter(status, null)));
    }

    // ---- Post.comments: the batch resolver that serves the field ----

    /**
     * THE FIX. @BatchMapping registers this as the data fetcher for
     * Post.comments, batched: spring-graphql collects every Post that needs
     * comments during one execution (six posts in a page query, one in a
     * single-fetch) and hands them over in ONE call. The port answers keyed
     * by post id (a storage key); @BatchMapping hands back a Map keyed by
     * the Post objects themselves, so the re-key below is the whole glue.
     * The adapter's call counters (see
     * {@link InMemoryCommentRepository#batchCallCount}) are how the test
     * proves one repository round trip served the whole page.
     */
    @BatchMapping
    public Map<Post, List<Comment>> comments(List<Post> posts) {
        Map<String, List<Comment>> byPostId = commentRepository.listForPosts(
                posts.stream().map(Post::id).toList());
        Map<Post, List<Comment>> byPost = new HashMap<>();
        for (Post post : posts) {
            byPost.put(post, byPostId.get(post.id()));
        }
        return byPost;
    }

    /**
     * THE TRAP — deliberately NOT a data fetcher (no @SchemaMapping): the
     * per-item shape. Had this carried the annotation, posts { comments }
     * would fire one repository lookup PER POST — the N+1 problem, invisible
     * in every single-post query and devastating at page scale (12 posts =
     * 12 round trips; a real SQL adapter would feel each one). It stays,
     * unregistered and dissected, because the honest way to teach the fix is
     * to keep the bug where you can point at it — the test calls it directly
     * and counts the lookups.
     */
    public Map<Post, List<Comment>> commentsTheNaiveWay(List<Post> posts) {
        Map<Post, List<Comment>> byPost = new HashMap<>();
        for (Post post : posts) {
            byPost.put(post, commentRepository.listForPost(post.id()));
        }
        return byPost;
    }

    // ---- Mutation: the write side, the SAME use cases as REST ----

    /** createPost(input) — PostDraft.of judges, exactly as the REST POST body is judged. */
    @MutationMapping
    public Post createPost(@Argument PostInput input) {
        Result<PostDraft> draft = PostDraft.of(input.title(), input.body(), input.tags(),
                input.status() == null ? null : input.status().wireName());
        if (draft instanceof Failed<PostDraft> failed) {
            throw AppException.of(failed.failure());
        }
        return unwrap(createPost.execute(value(draft)));
    }

    /** updatePost(id, expectedVersion, input) — optimistic concurrency, the 409 story as a GraphQL error. */
    @MutationMapping
    public Post updatePost(@Argument String id, @Argument Integer expectedVersion,
            @Argument PostInput input) {
        Result<PostDraft> draft = PostDraft.of(input.title(), input.body(), input.tags(),
                input.status() == null ? null : input.status().wireName());
        if (draft instanceof Failed<PostDraft> failed) {
            throw AppException.of(failed.failure());
        }
        return unwrap(updatePost.execute(id,
                expectedVersion == null ? 0 : expectedVersion, value(draft)));
    }

    /** deletePost(id) — true when deleted; the repeat is a typed not_found error, never a silent null. */
    @MutationMapping
    public Boolean deletePost(@Argument String id) {
        unwrap(deletePost.execute(id));
        return true;
    }

    // ---- the GraphQL mirror of PostDraft, bound at this edge ----

    /** The input type's Java shape: binding goes through this primary constructor. */
    record PostInput(String title, String body, List<String> tags, PostStatus status) {
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }

    private static <T> T value(Result<T> result) {
        if (result instanceof Success<T> success) {
            return success.value();
        }
        throw new IllegalStateException("value() on a Failed result — handle the failure first");
    }
}
