package guide.domain.graphql;

import java.util.List;

/**
 * The playground's preset query catalog — the curated tours through the
 * schema, each with a teaching purpose stated in its description (the demo
 * renders that commentary beside the picker). The presets ARE the topic's
 * queries-and-mutations section in executable form: a plain page query, a
 * variables-driven single fetch, the N+1 probe, the aliases-and-fragments
 * specimen, a mutation round trip, and the deliberate error that shows the
 * envelope in extensions.
 *
 * Pure data, no dependencies — the same contract as SeedPosts: deterministic,
 * assertable, quotable.
 */
public final class GraphqlQueryCatalog {

    private GraphqlQueryCatalog() {
    }

    /**
     * One preset: id (stable, for tests), title, teaching description, the
     * document, and default variables as JSON text (the playground's
     * variables editor starts from it; "{}" when the document takes none).
     */
    public record Preset(String id, String title, String description, String query,
            String defaultVariables) {
    }

    /** The preset catalog, in the order the playground lists them. */
    public static List<Preset> presets() {
        return List.of(
                new Preset("page-of-posts", "A page of posts",
                        "The shape YOU choose: one round trip returns the fields asked for and "
                                + "nothing else — no over-fetching, no under-fetching. Ask for "
                                + "fewer fields and watch the response shrink.",
                        """
                        query FirstPage {
                          posts(page: 0, size: 3) {
                            page
                            size
                            totalElements
                            totalPages
                            items { id title status version }
                          }
                        }""",
                        "{}"),
                new Preset("single-post", "One post, by id (variables)",
                        "The classic $variable form: the document is a template, the variables "
                                + "object fills the holes — the same document reruns against any "
                                + "id without string surgery.",
                        """
                        query OnePost($id: ID!) {
                          post(id: $id) {
                            id title body tags status version
                          }
                        }""",
                        """
                        {"id": "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01"}"""),
                new Preset("n-plus-one-probe", "The N+1 probe: posts + comments",
                        "Six posts, each with its comments — the query that would cost SEVEN "
                                + "repository calls with a naive resolver. This API serves it "
                                + "through a @BatchMapping: ONE batched call, counted live by "
                                + "the in-memory repository (the topic's test asserts exactly "
                                + "1 batch call, 0 single lookups).",
                        """
                        query PostsWithComments {
                          posts(page: 0, size: 6) {
                            items {
                              title
                              comments { author body }
                            }
                          }
                        }""",
                        "{}"),
                new Preset("aliases-and-fragments", "Aliases + fragments",
                        "Two calls of the SAME field under different ALIASES (drafts: vs "
                                + "published:) in one round trip, both shaped by one FRAGMENT "
                                + "— reuse without repetition, comparison without a second "
                                + "request.",
                        """
                        query CompareStatuses {
                          drafts: posts(page: 0, size: 2, status: DRAFT) { ...pageShape }
                          published: posts(page: 0, size: 2, status: PUBLISHED) { ...pageShape }
                        }

                        fragment pageShape on PostPage {
                          totalElements
                          items { title status }
                        }""",
                        "{}"),
                new Preset("mutation-create", "Create a post (mutation)",
                        "The write side: the SAME PostDraft rules the REST POST enforces "
                                + "(title required, status defaults to DRAFT), reached through "
                                + "the same use case — GraphQL here is a different door into "
                                + "the same house, not a second API.",
                        """
                        mutation CreateOne($input: PostInput!) {
                          createPost(input: $input) {
                            id title status version
                          }
                        }""",
                        """
                        {"input": {
                          "title": "Hello from the playground",
                          "body": "Created through the GraphQL mutation, judged by the same PostDraft rules as the REST POST.",
                          "tags": ["graphql"],
                          "status": "DRAFT"
                        }}"""),
                new Preset("error-envelope", "The error envelope (unknown id)",
                        "GraphQL always answers HTTP 200 — failures ride the errors[] array, "
                                + "and THIS app's taxonomy lands in extensions: code "
                                + "(not_found), requestId, and the failing details. This is "
                                + "the not-found error, as data.",
                        """
                        query NoSuchPost {
                          post(id: "does-not-exist") {
                            id title
                          }
                        }""",
                        "{}"));
    }
}
