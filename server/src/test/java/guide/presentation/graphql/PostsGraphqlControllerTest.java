package guide.presentation.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.result.Result;
import guide.core.result.Success;
import guide.core.scenario.ScenarioStore;
import guide.data.comments.InMemoryCommentRepository;
import guide.data.posts.InMemoryPostRepository;
import guide.domain.comments.CommentRepository;
import guide.domain.posts.ListPosts;
import guide.domain.posts.Post;
import guide.domain.posts.PostSort;
import guide.domain.posts.PostFilter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Topic 13's drift test — the schema must match reality. Every query below
 * goes through the REAL /graphql HTTP endpoint (schema → resolvers → posts
 * use cases → in-memory adapters), raw JSON over MockMvc: the same bytes a
 * curl sends. The load-bearing assertion is the N+1 PROOF: a page of six
 * posts, each with its comments, must cost EXACTLY ONE batched repository
 * call and ZERO single lookups — if the count is six, the @BatchMapping is
 * not wired and the suite says so.
 *
 * Both repositories are reset to their deterministic seed before every
 * test, so counts and totals assert exactly, order-independent of tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostsGraphqlControllerTest {

    private static final String GRAPHQL = "/graphql";

    /** The first seed post — newest first, so it is posts(page:0)[0]. */
    private static final String SEED_ID = "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryPostRepository postRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private ScenarioStore scenarioStore;

    @Autowired
    private PostsGraphqlController controller;

    @Autowired
    private ListPosts listPosts;

    @BeforeEach
    void resetDeterministicSeed() {
        // Phase 6: the exposed PostRepository bean is the router; the fake is
        // reset through its own bean, its concrete type.
        postRepository.reset();
        ((InMemoryCommentRepository) commentRepository).resetCallCounts();
        scenarioStore.reset();
    }

    private InMemoryCommentRepository commentsAdapter() {
        return (InMemoryCommentRepository) commentRepository;
    }

    /** Builds the GraphQL-over-HTTP body: {"query": "...", "variables": {...}}. */
    private static String body(String query, String variablesJson) {
        String escaped = query.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "").replace("\n", "\\n");
        return "{\"query\":\"" + escaped + "\",\"variables\":" + variablesJson + "}";
    }

    private MockHttpServletRequestBuilder run(String query, String variablesJson) {
        return post(GRAPHQL).contentType(MediaType.APPLICATION_JSON)
                .content(body(query, variablesJson));
    }

    @Test
    void postsPageMatchesTheSchemaShape() throws Exception {
        mockMvc.perform(run("""
                        query {
                          posts(page: 0, size: 3) {
                            page size totalElements totalPages
                            items { id title tags status version }
                          }
                        }""", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posts.page").value(0))
                .andExpect(jsonPath("$.data.posts.size").value(3))
                .andExpect(jsonPath("$.data.posts.totalElements").value(12))
                .andExpect(jsonPath("$.data.posts.totalPages").value(4))
                .andExpect(jsonPath("$.data.posts.items.length()").value(3))
                .andExpect(jsonPath("$.data.posts.items[0].id").value(SEED_ID))
                .andExpect(jsonPath("$.data.posts.items[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.posts.items[0].tags.length()").value(2))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void statusFilterFunnelsThroughTheSameUseCaseAsRest() throws Exception {
        mockMvc.perform(run("""
                        query {
                          posts(page: 0, size: 20, status: DRAFT) {
                            totalElements
                            items { title status }
                          }
                        }""", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posts.totalElements").value(2))
                .andExpect(jsonPath("$.data.posts.items.length()").value(2))
                .andExpect(jsonPath("$.data.posts.items[0].status").value("DRAFT"));
    }

    @Test
    void singlePostWithCommentsAnswersThroughTheBatchPath() throws Exception {
        mockMvc.perform(run("""
                        query One($id: ID!) {
                          post(id: $id) {
                            title version
                            comments { id postId author body }
                          }
                        }""", "{\"id\": \"" + SEED_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.post.title").value(
                        "The uniform interface is the contract that scales"))
                .andExpect(jsonPath("$.data.post.version").value(1))
                .andExpect(jsonPath("$.data.post.comments.length()").value(3))
                .andExpect(jsonPath("$.data.post.comments[0].postId").value(SEED_ID))
                .andExpect(jsonPath("$.data.post.comments[0].author").value("Mira"));
    }

    @Test
    void theNPlusOneProofOneBatchCallServesASixPostPage() throws Exception {
        commentsAdapter().resetCallCounts();
        mockMvc.perform(run("""
                        query {
                          posts(page: 0, size: 6) {
                            items { title comments { author } }
                          }
                        }""", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posts.items.length()").value(6))
                .andExpect(jsonPath("$.data.posts.items[0].comments.length()").value(3));
        // THE PROOF: six posts' comments, ONE repository call, ZERO per-post lookups.
        assertEquals(1, commentsAdapter().batchCallCount(),
                "six posts' comments must resolve in ONE batched repository call");
        assertEquals(0, commentsAdapter().singleLookupCount(),
                "the per-item lookup shape must never fire for a page query");
    }

    @Test
    void theNaivePerItemResolverWouldCostOneLookupPerPost() {
        List<Post> sixPosts = value(listPosts.execute(0, 6, PostSort.DEFAULT, PostFilter.NONE)).items();
        commentsAdapter().resetCallCounts();
        var byPost = controller.commentsTheNaiveWay(sixPosts);
        assertEquals(6, byPost.size());
        assertEquals(3, byPost.get(sixPosts.getFirst()).size());
        assertEquals(6, commentsAdapter().singleLookupCount(),
                "the trap costs N lookups for N posts — exactly what @BatchMapping removes");
        assertEquals(0, commentsAdapter().batchCallCount());
    }

    private static <T> T value(guide.core.result.Result<T> result) {
        if (result instanceof Success<T> success) {
            return success.value();
        }
        throw new IllegalStateException("expected a successful listing");
    }

    @Test
    void mutationCreateRoundTripsThroughTheSameRulesAsRest() throws Exception {
        String createdId = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(run("""
                                mutation Create($input: PostInput!) {
                                  createPost(input: $input) { id title status version }
                                }""",
                        """
                        {"input": {
                          "title": "GraphQL creates through the same draft rules",
                          "body": "One door, two protocols.",
                          "tags": ["graphql"],
                          "status": "DRAFT"
                        }}"""))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.createPost.title").value(
                                "GraphQL creates through the same draft rules"))
                        .andExpect(jsonPath("$.data.createPost.status").value("DRAFT"))
                        .andExpect(jsonPath("$.data.createPost.version").value(1))
                        .andReturn().getResponse().getContentAsString(), "$.data.createPost.id");

        mockMvc.perform(run("""
                        query One($id: ID!) {
                          post(id: $id) { title tags }
                        }""", "{\"id\": \"" + createdId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.post.title").value(
                        "GraphQL creates through the same draft rules"))
                .andExpect(jsonPath("$.data.post.tags[0]").value("graphql"));
    }

    @Test
    void anInvalidDraftIsAValidationErrorNotASilentCreate() throws Exception {
        mockMvc.perform(run("""
                        mutation {
                          createPost(input: { title: "", body: "absent title" }) {
                            id
                          }
                        }""", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createPost").doesNotExist())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].extensions.requestId").isNotEmpty());
    }

    @Test
    void unknownIdIsATypedErrorInTheEnvelopeShape() throws Exception {
        mockMvc.perform(run("""
                        query {
                          post(id: "does-not-exist") { id title }
                        }""", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.post").doesNotExist())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("not_found"))
                .andExpect(jsonPath("$.errors[0].extensions.postId").value("does-not-exist"))
                .andExpect(jsonPath("$.errors[0].extensions.requestId").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].path[0]").value("post"));
    }

    @Test
    void aStaleUpdateCarriesTheConflictTaxonomyIntoExtensions() throws Exception {
        mockMvc.perform(run("""
                        mutation Update($id: ID!) {
                          updatePost(id: $id, expectedVersion: 999,
                                     input: { title: "too late", body: "stale write" }) {
                            id version
                          }
                        }""", "{\"id\": \"" + SEED_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatePost").doesNotExist())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("conflict"))
                .andExpect(jsonPath("$.errors[0].extensions.currentVersion").value(1))
                .andExpect(jsonPath("$.errors[0].extensions.requestId").isNotEmpty());
    }

    @Test
    void introspectionAnswersTheSchemaBack() throws Exception {
        mockMvc.perform(run("""
                        query {
                          __schema { queryType { name } }
                          postType: __type(name: "Post") {
                            fields { name }
                          }
                        }""", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__schema.queryType.name").value("Query"))
                .andExpect(jsonPath("$.data.postType.fields[?(@.name == 'comments')].name")
                        .value(org.hamcrest.Matchers.hasItem("comments")));
    }

    @Test
    void graphiqlIsNotMountedOutsideTheDevProfile() throws Exception {
        // The once-app-wide gotcha (routing topic), FIXED by topic 30's
        // ErrorResponse branch: an unmapped GET throws
        // NoResourceFoundException, which the advice now renders with the
        // framework's own status — the typed 404 envelope, not the 500 the
        // catch-all used to give it. What THIS test pins is the dev-only
        // decision: outside the dev profile /graphiql serves no IDE, no
        // redirect — only that envelope.
        mockMvc.perform(get("/graphiql"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }
}
