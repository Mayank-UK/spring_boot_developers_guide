package guide.content.topics;

import static guide.content.topics.TopicBlocks.code;
import static guide.content.topics.TopicBlocks.demo;
import static guide.content.topics.TopicBlocks.section;
import static guide.content.topics.TopicBlocks.text;

import guide.content.ContentSection;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;

/**
 * Topic 13 — GraphQL. Group apis, order 3. The client-driven API: one
 * schema, one endpoint, one round trip — built schema-first with Spring for
 * GraphQL over the SAME posts substrate the REST API serves. The SDL
 * dissected, annotated controllers wired by Boot, queries and mutations
 * (variables, aliases, fragments), the always-200 error envelope with the
 * app's taxonomy in extensions, and the N+1 problem with its batch-loader
 * fix — proven by a repository call counter, live.
 */
public final class GraphqlTopic {

    private GraphqlTopic() {
    }

    public static Topic topic() {
        return new Topic("graphql", "GraphQL",
                "One schema, one endpoint, one round trip: the client names "
                        + "the shape and the server answers exactly that. The "
                        + "posts API you already know, re-expressed as a type "
                        + "graph — resolvers reusing the same use cases, errors "
                        + "that stay 200 and stay typed, and the N+1 problem "
                        + "fixed by batching with the receipts to prove it.",
                TopicGroupId.APIS, 3,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("the-problem-graphql-solves", "The problem GraphQL solves",
                        "Over-fetching, under-fetching, and endpoint sprawl — and the honest cases where REST is already the right answer.",
                        text("why:over-under", """
                                Every REST API meets the same two complaints from the client side, and they are mirror images. OVER-FETCHING: a list screen asks GET /posts and receives full bodies, timestamps, tags — kilobytes of fields it will never render; the server decided the shape years ago and every screen since has paid for it. UNDER-FETCHING: a detail screen needs a post AND its comments, so the client makes two round trips — or the team builds GET /posts/{id}?include=comments, and then ?include=comments,tags,author, and soon the query string is an ad-hoc field-selection language with none of the safety. Mobile teams feel this worst (every kilobyte is battery and latency), but the cost is real everywhere: round trips are the dominant latency unit on a network.

                                GraphQL's answer inverts who decides the shape. The server publishes a SCHEMA — a typed graph of what CAN be asked (posts, their fields, their comments) — and every request carries a DOCUMENT saying what IS being asked, field by field. The answer's shape mirrors the query's shape: ask for titles only and you receive titles only; ask for the post and its comments' authors and you receive exactly that tree, in ONE round trip. No bespoke include-parameters, no /v2/ endpoint born because one screen needed one extra field (topic 11 built exactly that endpoint; GraphQL's answer to that problem is "the client stops asking for summary")."""),
                        text("why:tradeoffs", """
                                What it costs, honestly. The server gives up control of response shape and therefore of response SIZE — a client can legally ask for posts { comments { post { comments { … } } } } unless you cap depth and complexity, which production GraphQL servers must. HTTP caching gets harder: everything is one POST endpoint, so the CDN/ETag/304 machinery from the caching topic does not apply per-query the way it does per-Resource (you cache in front of the schema, or you accept caching inside the client). Batching writes and file uploads need protocol conventions REST gets for free. And the schema becomes a public contract with its own evolution discipline — the same "promise you cannot take back" gravity topic 11 taught for URLs, now applied to every field.

                                WHEN REST IS ALREADY RIGHT — this guide's own position, demonstrated by construction: this app's CONTENT API (the thing you are reading right now) is plain REST+JSON and stays that way, because its screens are known, its shape is stable, and cacheability and debuggability-by-curl are worth more than client-driven shape. GraphQL earns its keep where MANY clients with DIFFERENT needs read the SAME graph — public product APIs, mobile + web + partner feeds over one backend, aggregation gateways — and where the association graph (post → comments → authors → their posts) is deep enough that round trips, not bandwidth, are the bottleneck. It is a tool with a sharp edge, not a religion; the rest of this topic teaches you to hold the handle.""")),
                section("schema-types-and-resolvers", "Schema, types, resolvers",
                        "SDL anatomy: types, non-null markers, enums, input objects, pagination as a type — our real posts.graphql, quoted.",
                        text("sdl:contract", """
                                GraphQL is SCHEMA-FIRST: the contract is a text file in the Schema Definition Language, and everything else — server wiring, client tooling, generated types — derives from it. Ours is a real file in this repo, served by this app; here is its read/write surface, exactly as it ships:"""),
                        code("sdl:operations", "graphql",
                                "server/src/main/resources/graphql/posts.graphql — the operations surface: every argument a client can pass, every nullability decision you must honor",
                                "server/src/main/resources/graphql/posts.graphql", """
                                enum PostStatus {
                                  DRAFT
                                  PUBLISHED
                                }

                                type Query {
                                  post(id: ID!): Post
                                  posts(page: Int, size: Int, status: PostStatus): PostPage
                                }

                                type Mutation {
                                  createPost(input: PostInput!): Post
                                  updatePost(id: ID!, expectedVersion: Int!, input: PostInput!): Post
                                  deletePost(id: ID!): Boolean
                                }"""),
                        text("sdl:nullability", """
                                Read the punctuation as CONTRACT, because that is what it is. The `!` NON-NULL marker is a promise: `posts(...)` always answers a PostPage (maybe an empty one), `title: String!` never null, `comments: [Comment!]!` is never a null list and never contains a null entry — a post may have ZERO comments, which is a different fact from "we could not load comments". Fields WITHOUT the marker are honest uncertainty: `post(id: ID!)` may answer null (though this API prefers a typed error — the errors section shows why). Getting `!` wrong is the classic schema-versioning mistake: slapping it on later is a breaking change for every client that already defends against null, and taking it off silently breaks clients that trusted the promise. Choose nullability like you choose status codes: once published, it is load-bearing.

                                The scalars are deliberately few — ID (opaque identity, serializes like a string), String, Int, Boolean — and ENUMS are closed sets with UPPERCASE names by convention: our PostStatus spells DRAFT/PUBLISHED (the Java enum's names; note the REST wire spells them lowercase — GraphQL enum names are their own contract, not Jackson's). One more idiom hides in plain sight: `page` and `size` are plain nullable Int arguments with server-side defaults (0 and 10, the resolver decides), so the schema documents the knobs while keeping them optional — the same defaults contract as the REST query string."""),
                        code("sdl:types", "graphql",
                                "posts.graphql — the object graph: Post carries its association, PostPage carries pagination as fields instead of headers",
                                "server/src/main/resources/graphql/posts.graphql", """
                                type Post {
                                  id: ID!
                                  title: String!
                                  body: String!
                                  tags: [String!]!
                                  status: PostStatus!
                                  version: Int!
                                  comments: [Comment!]!
                                }

                                type Comment {
                                  id: ID!
                                  postId: ID!
                                  author: String!
                                  body: String!
                                }

                                type PostPage {
                                  items: [Post!]!
                                  page: Int!
                                  size: Int!
                                  totalElements: Int!
                                  totalPages: Int!
                                }"""),
                        text("sdl:inputs-and-pagination", """
                                Two shapes deserve a second look. First, INPUT TYPES: mutations receive structured data, and GraphQL separates the receiving shape (`input PostInput`) from the returning shape (`type Post`) because they evolve differently — ours mirrors PostDraft exactly, and its nullability ENCODES THE DEFAULTS: title and body are non-null (absent is a validation error), while tags is a nullable list (omit it, get []) and status is a nullable enum (omit it, get DRAFT — the safe default, the same rule the REST POST body follows). The schema itself rejects what `!` forbids, before any resolver runs; what the schema cannot see (a 121-character title) is judged by PostDraft — validation layered exactly like topic 10 taught.

                                Second, PAGINATION AS A TYPE: REST expresses paging metadata in Link headers and a total header; GraphQL has no headers to put it in — every byte the client gets is a field it asked for — so the page ITSELF becomes a type (PostPage with items + page + size + totalElements + totalPages). The client that wants totals asks for them; the client that only scrolls asks for items and a cursor-shaped next hint and pays for nothing else. Same contract as REST's paging, different housing — and one more example of the topic's one law: the schema is the shape of every answer.""")),
                section("spring-for-graphql-wiring", "Spring for GraphQL wiring",
                        "Schema files on the classpath, annotated resolvers as controllers, Boot's autoconfiguration — quote ours, bean by bean.",
                        text("wire:discovery", """
                                The integration story is pleasingly small: drop the starter in, put schema files under src/main/resources/graphql/, write @Controller classes for the resolvers — Boot assembles the engine. Under the hood Boot's GraphQL autoconfiguration builds a GraphQlSource: it parses every schema file it finds (classpath:graphql/** by default, extensions .graphqls/.gqls), wires each schema field to a resolver it discovers (annotated controller methods), and mounts an HTTP endpoint at /graphql that speaks the GraphQL-over-JSON protocol — one POST, body {query, variables}, answer {data, errors}. This app's whole GraphQL configuration beyond that, one property list:"""),
                        code("wire:yml", "yaml",
                                "server/src/main/resources/application.yml — the schema location config: our file says .graphql, so the extension list says so too",
                                "server/src/main/resources/application.yml", """
                                spring:
                                  graphql:
                                    schema:
                                      fileExtensions:
                                        - .graphqls
                                        - .graphql"""),
                        text("wire:annotations", """
                                Resolvers are annotated controller methods — the same mental model as @GetMapping, applied to schema fields. @QueryMapping and @MutationMapping bind methods to fields on the Query and Mutation types (they are @SchemaMapping with the typeName preset); @SchemaMapping binds to any type's field (ours: Post.comments); @BatchMapping is @SchemaMapping's batching sibling — the N+1 section gives it a section of its own. Method arguments bind with @Argument by name — same discipline as handler methods, including the same -parameters compiler flag Boot enables. Our query resolvers, verbatim:"""),
                        code("wire:queries", "java",
                                "guide/presentation/graphql/PostsGraphqlController.java — the read side: two @QueryMapping methods, both delegating to the REST API's own use cases",
                                "server/src/main/java/guide/presentation/graphql/PostsGraphqlController.java", """
                                @Controller
                                public class PostsGraphqlController {

                                    private final GetPost getPost;
                                    private final ListPosts listPosts;
                                    // …

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
                                }"""),
                        text("wire:composition-root", """
                                THE PORT PAYOFF, LIVE: look at what those resolvers did NOT do. No repository access, no pagination math, no status-filter parsing — each method binds arguments, applies defaults, and delegates to GetPost/ListPosts — the SAME use case beans the REST controllers call. The dependency rule holds unchanged (presentation → domain ← data), the composition root is unchanged (PostsDemoConfig still wires the one PostRepository bean and the five use cases), and the Phase 6 PostgreSQL swap will flip the storage under BOTH protocols on the same day without this file noticing. GraphQL here is a new DOOR into the same house, not a second house.

                                What this topic ADDS to the composition root is its own small config — the comments substrate (a read-only association port with a batch-shaped method, built for the N+1 lesson), the failure resolver that carries the error taxonomy into GraphQL errors, and the use case behind the playground demo:"""),
                        code("wire:config", "java",
                                "guide/config/GraphqlDemoConfig.java — topic 13's own composition root: comment port, failure resolver, execute use case",
                                "server/src/main/java/guide/config/GraphqlDemoConfig.java", """
                                @Configuration
                                public class GraphqlDemoConfig {

                                    @Bean
                                    CommentRepository commentRepository() {
                                        return new InMemoryCommentRepository();
                                    }

                                    @Bean
                                    DataFetcherExceptionResolver graphqlFailureResolver() {
                                        return new GraphqlFailureResolver();
                                    }

                                    @Bean
                                    ExecuteGraphqlQuery executeGraphqlQuery(ExecutionGraphQlService graphQlService) {
                                        return new ExecuteGraphqlQuery(graphQlService);
                                    }
                                }"""),
                        text("wire:graphiql", """
                                Boot's autoconfiguration is also where the tooling mounts: GraphiQL — the in-browser schema explorer and query runner — ships with the starter and activates on ONE property. We enable it in a DEV-ONLY yaml document keyed to the dev profile (docker-compose sets SPRING_PROFILES_ACTIVE=dev), so the guide's container serves /graphiql while a default- or prod-profile boot does not map the route at all — the topic's default-profile test pins that (via the app's typed unrouted-path 404 envelope — the framework ErrorResponse behavior the exception-handling topic brought into the envelope machinery; see the introspection section). GraphiQL in prod is not a security feature to negotiate; it is a dev tool that leaks your schema's ergonomics to whoever can reach it, and profile-gating it keeps that decision boring:"""),
                        code("wire:graphiql-yml", "yaml",
                                "server/src/main/resources/application.yml — the dev-only document: GraphiQL mounts only when the dev profile is active",
                                "server/src/main/resources/application.yml", """
                                spring:
                                  config:
                                    activate:
                                      on-profile: dev
                                  graphql:
                                    graphiql:
                                      enabled: true""")),
                section("queries-and-mutations", "Queries and mutations",
                        "Variables, aliases, fragments — the client-side language features — and mutations that reuse the REST API's use cases unchanged.",
                        text("q:variables", """
                                A GraphQL document is a TEMPLATE. The operation declares its holes as $variables with types, and the request carries a separate variables object that fills them — the same document reruns against any post id without string surgery, and the server can cache parse-and-validate work per document. Our playground ships this as a preset (the catalog lives in domain/graphql/GraphqlQueryCatalog.java — pure data, quoted for real):"""),
                        code("q:variables-preset", "java",
                                "guide/domain/graphql/GraphqlQueryCatalog.java — the single-post preset: an operation template plus its default variables",
                                "server/src/main/java/guide/domain/graphql/GraphqlQueryCatalog.java", """
                                query OnePost($id: ID!) {
                                  post(id: $id) {
                                    id title body tags status version
                                  }
                                }\"\"\",
                                // …the second text block's delimiter elided:
                                {"id": "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01"}\"\"\"),"""),
                        text("q:aliases-fragments", """
                                Two more client-side features buy reuse without repetition. ALIASES rename a field's answer: call posts twice under different names (drafts:, published:) with different arguments, and ONE round trip returns both — the comparison screen's two REST calls become one document. FRAGMENTS are named field selections reusable across the places a type appears: define once what "the shape I want from a PostPage" means, spread it (...pageShape) wherever a PostPage is asked for, and the selection lives in exactly one place. The playground's specimen, which the demo and the terminal companion both run for real:"""),
                        code("q:aliases-preset", "java",
                                "guide/domain/graphql/GraphqlQueryCatalog.java — aliases + fragments: same field, two arguments, one round trip, one shared selection",
                                "server/src/main/java/guide/domain/graphql/GraphqlQueryCatalog.java", """
                                query CompareStatuses {
                                  drafts: posts(page: 0, size: 2, status: DRAFT) { ...pageShape }
                                  published: posts(page: 0, size: 2, status: PUBLISHED) { ...pageShape }
                                }

                                fragment pageShape on PostPage {
                                  totalElements
                                  items { title status }
                                }"""),
                        text("q:mutations", """
                                Mutations are fields like any other — the MUTATION keyword is a convention that says "this one writes", letting clients and servers reason about ordering and caching, not a different execution engine. The mutation methods on our controller should look eerily familiar, because they ARE familiar: each binds its input, judges it through PostDraft.of — the same per-field validation the REST POST body passes — and delegates to the same CreatePost/UpdatePost/DeletePost use cases. Trace one request through the whole app and the diagram is the REST one with a different first box: document → /graphql → @MutationMapping createPost → PostDraft.of judges → CreatePost executes → PostRepository (the ONE bean PostsDemoConfig wires) → the answer reshaped to the queried fields; failures take the throw → resolver → errors[] path the next section dissects. Optimistic concurrency survives the protocol change intact: updatePost's expectedVersion argument is the same number the REST PUT demands via If-Match, and a stale write is the same CONFLICT failure — rendered as a GraphQL error instead of a 409:"""),
                        code("q:mutations-code", "java",
                                "guide/presentation/graphql/PostsGraphqlController.java — the write side: judge through PostDraft, delegate to the same use cases REST uses",
                                "server/src/main/java/guide/presentation/graphql/PostsGraphqlController.java", """
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
                                        // …
                                        return unwrap(updatePost.execute(id,
                                                expectedVersion == null ? 0 : expectedVersion, value(draft)));
                                    }"""),
                        text("q:input-binding", """
                                One wiring detail worth naming: `@Argument PostInput` binds the schema's input object to a record through its primary constructor — title to title, tags to tags, and the DRAFT/PUBLISHED enum name to the PostStatus constant. The record is declared at the presentation edge (it is a WIRE shape, not a domain one), mirrors PostInput in the SDL field-for-field, and converts to a PostDraft one step later — the same DTO-at-the-edge discipline every REST controller in this guide follows. If binding fails (a string where a list belongs), spring-graphql reports it as field errors before the method body ever runs — yet another validation layer at yet another boundary.""")),
                section("errors", "Errors: always 200, always typed",
                        "GraphQL's {data, errors} envelope, partial answers, and our taxonomy carried into extensions by one resolver adapter.",
                        text("err:envelope", """
                                The rule that surprises every REST-trained engineer: GraphQL answers HTTP 200 for application-level failures — unknown ids, validation refusals, conflicts — because the HTTP status describes the TRANSPORT, and the operation's verdict lives inside the body: {data, errors}. data carries whatever resolved (possibly null at the failed field's path, possibly the OTHER fields of a multi-field query that succeeded — PARTIAL ANSWERS are a feature: one field failing does not burn the round trip); errors[] carries one entry per failure with the message, the PATH where it happened, and EXTENSIONS — a free-form map where machines look for codes. A transport-level disaster (body unparseable) still answers 4xx/5xx; everything your application decides answers 200.

                                That design forces one question this app answers the same way it always has: if the HTTP status no longer carries the taxonomy, WHAT DOES? Extensions. Resolvers throw the SAME typed AppException a REST controller would let reach the advice, and one adapter maps it into a GraphQL error whose extensions carry the one error envelope's keys — code, requestId, plus the failure's structured details. The adapter is the GraphQL world's @ControllerAdvice:"""),
                        code("err:resolver", "java",
                                "guide/presentation/graphql/GraphqlFailureResolver.java — the taxonomy into errors[].extensions: code + requestId + details, per failure",
                                "server/src/main/java/guide/presentation/graphql/GraphqlFailureResolver.java", """
                                public class GraphqlFailureResolver extends DataFetcherExceptionResolverAdapter {

                                    @Override
                                    @Nullable
                                    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
                                        if (!(exception instanceof AppException appException)) {
                                            return null;
                                        }
                                        Failure failure = appException.failure();
                                        Map<String, Object> extensions = new LinkedHashMap<>();
                                        if (failure.details() != null) {
                                            extensions.putAll(failure.details());
                                        }
                                        extensions.put("code", failure.kind().errorCode());
                                        extensions.put("requestId", currentRequestId());
                                        return GraphQLError.newError()
                                                .message(failure.message())
                                                .path(environment.getExecutionStepInfo().getPath())
                                                .extensions(extensions)
                                                .build();
                                    }
                                }"""),
                        text("err:taxonomy-preserved", """
                                Read what that preserves. not_found for an unknown post — with the postId detail in extensions. conflict for a stale update — carrying expectedVersion AND currentVersion, so a GraphQL client can re-read and retry exactly like the REST client that got the 409 envelope. validation_failed for a title that never satisfied PostDraft — with the per-field reasons. And requestId everywhere: the same MDC key the request-id filter stamps for HTTP, read by the same fallback chain, so a GraphQL error in a support ticket greps the same log line a REST error does. ERROR TAXONOMY PRESERVED ACROSS PROTOCOLS is the quiet payoff of "failures are values with kinds" — the protocol changes, the envelope's keys do not, and every client (curl, playground, future codegen) reads the same extensions map.

                                Note also what the adapter does NOT do: it returns null for anything that is not an AppException, handing binding glitches and framework faults to spring-graphql's default handling — an unrecognized exception must not be laundered into a fake taxonomy kind. GraphQLError.newError() places the failure at the field's path, which is how a multi-field query degrades partially: the failing branch nulls, its siblings answer, and the client sees precisely which leaf died.""")),
                section("n-plus-one-and-batch-loaders", "N+1 and batch loaders",
                        "The classic trap: association fields fired per-item. The fix: batch the keys into ONE repository call — with a call-count receipt.",
                        text("n1:trap", """
                                Here is the failure mode that gives GraphQL its "slow by default" reputation, and it is a RESOLVER design error, not a protocol property. Association fields — Post.comments in our schema — resolve PER PARENT OBJECT: six posts in a page means six separate invocations of the comments resolver. If each invocation walks to the repository on its own, a one-request page query became SEVEN repository round trips (one for the page, six for comments); over SQL that is six SELECTs where one IN-clause would do, and over a network dependency it is six RPC round trips of pure latency. It is invisible in dev (one post, one lookup, everything fast) and murderous at page scale. The trap, verbatim — real code in this controller, deliberately NOT registered as a data fetcher:"""),
                        code("n1:naive", "java",
                                "guide/presentation/graphql/PostsGraphqlController.java — THE TRAP: one lookup per post; annotating this would make it serve the field",
                                "server/src/main/java/guide/presentation/graphql/PostsGraphqlController.java", """
                                    public Map<Post, List<Comment>> commentsTheNaiveWay(List<Post> posts) {
                                        Map<Post, List<Comment>> byPost = new HashMap<>();
                                        for (Post post : posts) {
                                            byPost.put(post, commentRepository.listForPost(post.id()));
                                        }
                                        return byPost;
                                    }"""),
                        text("n1:fix-at-the-edge", """
                                The honest fix is not a cleverer resolver — it is a BATCH QUERY AT THE DATA EDGE, which means the PORT must offer one. A repository that can only answer "one post's comments" makes every layer above it structurally incapable of batching; a repository that can answer "these N posts' comments in one call" makes batching a one-liner anywhere. So the comments port ships batch-shaped from day one (one SQL IN clause, one Redis MGET, one multi-get — whatever the Phase 6 adapter speaks), and the per-item method stays for genuinely-single fetches:"""),
                        code("n1:port", "java",
                                "guide/domain/comments/CommentRepository.java — the port carries the batch contract: listForPosts is the shape that fixes N+1",
                                "server/src/main/java/guide/domain/comments/CommentRepository.java", """
                                public interface CommentRepository {

                                    /** The comments of one post, oldest first; empty when it has none. */
                                    List<Comment> listForPost(String postId);

                                    /**
                                     * The comments of MANY posts in one call — the batch-shaped contract a
                                     * batch resolver (or DataLoader) keys its per-post answers from. Every
                                     * requested id is present in the answer (empty list = no comments);
                                     * duplicate ids collapse to one entry.
                                     */
                                    Map<String, List<Comment>> listForPosts(List<String> postIds);
                                }"""),                        text("n1:batch-mapping", """
                                With the port batch-shaped, the resolver fix is one annotation. @BatchMapping registers the method as Post.comments' data fetcher, BATCHED: during one execution, spring-graphql collects every Post that needs comments (six from a page query, one from a single-fetch) and hands the whole list to ONE invocation; your method returns a Map from each post to its comments (records hash by value, which is what makes them usable as map keys here). The framework stands on java-dataloader underneath — loads are queued as fields resolve, then dispatched together — so the batching composes across the whole request, not just within one field. The resolver that ACTUALLY serves the field:"""),
                        code("n1:batch", "java",
                                "guide/presentation/graphql/PostsGraphqlController.java — THE FIX: @BatchMapping hands you every parent in one call; one repository round trip serves the page",
                                "server/src/main/java/guide/presentation/graphql/PostsGraphqlController.java", """
                                    @BatchMapping
                                    public Map<Post, List<Comment>> comments(List<Post> posts) {
                                        Map<String, List<Comment>> byPostId = commentRepository.listForPosts(
                                                posts.stream().map(Post::id).toList());
                                        Map<Post, List<Comment>> byPost = new HashMap<>();
                                        for (Post post : posts) {
                                            byPost.put(post, byPostId.get(post.id()));
                                        }
                                        return byPost;
                                    }"""),
                        text("n1:proof", """
                                Claims about batching are cheap; this repo makes them checkable. The in-memory comment adapter counts its calls BY SHAPE — listForPost ticks one counter, listForPosts the other — and the topic's test resets them, runs the six-post page query through the real /graphql endpoint, and asserts the receipt. This is the assertion, exactly as it runs in the suite:"""),
                        code("n1:proof-test", "java",
                                "guide/presentation/graphql/PostsGraphqlControllerTest.java — the N+1 proof: counters before, real query, counts after",
                                "server/src/test/java/guide/presentation/graphql/PostsGraphqlControllerTest.java", """
                                void theNPlusOneProofOneBatchCallServesASixPostPage() throws Exception {
                                    commentsAdapter().resetCallCounts();
                                    mockMvc.perform(run(\"\"\"
                                                    query {
                                                      posts(page: 0, size: 6) {
                                                        items { title comments { author } }
                                                      }
                                                    }\"\"\", \"{}\"))
                                            // …
                                    // THE PROOF: six posts' comments, ONE repository call, ZERO per-post lookups.
                                    assertEquals(1, commentsAdapter().batchCallCount(),
                                            "six posts' comments must resolve in ONE batched repository call");
                                    assertEquals(0, commentsAdapter().singleLookupCount(),
                                            "the per-item lookup shape must never fire for a page query");
                                }"""),
                        text("n1:loaders-survey", """
                                @BatchMapping covers the common case (the associated data sits behind a repository your controller can reach), but spring-graphql's batching toolbox goes further, and the survey is worth having. BatchLoaderRegistry (inject it like any bean) registers load functions for a KEY→VALUE pair when the resolver cannot be a controller method — the source is a remote client, or the keys are ids rather than parent objects: registry.forTypePair(String.class, Comment.class).registerBatchLoader((ids, env) -> …) pairs with a @SchemaMapping that takes a DataLoader<Comment's-key> and returns loader.load(post.id()) — a CompletableFuture the framework queues and dispatches as a batch. registerMappedBatchLoader answers a Map (sparse results, some keys legitimately absent) where registerBatchLoader must return values in key order. Loaders are also per-request CACHES: the same key loaded twice in one execution hits the cache, which is what makes deeply-nested, repeated associations (post → author → her posts → their authors…) collapse instead of re-firing. The rule of thumb that survives all of it: batching is a DATA-EDGE capability first and a resolver annotation second — build the batch-shaped port method, and every protocol above it (GraphQL today, whatever comes next) gets N+1 immunity for one line.""")),
                section("introspection-and-tooling", "Introspection and tooling",
                        "The schema that serves itself: introspection queries, GraphiQL in dev, and codegen for typed clients.",
                        text("intro:serves-itself", """
                                GraphQL's tooling story rests on one trick: the schema answers questions ABOUT ITSELF. The reserved __schema and __type fields let any client ask which types exist, which fields they carry, which arguments they take, which enums spell what — GraphQL's own reflection API, and the reason every GraphQL tool you have ever used (GraphiQL, Apollo devtools, code generators) needed zero bespoke metadata: it asked the running server. Our test suite asks the same questions the tooling does — the drift check that the served schema really exposes Post.comments:"""),
                        code("intro:test", "java",
                                "guide/presentation/graphql/PostsGraphqlControllerTest.java — introspection through the real endpoint: the tooling's own question, asked by a test",
                                "server/src/test/java/guide/presentation/graphql/PostsGraphqlControllerTest.java", """
                                void introspectionAnswersTheSchemaBack() throws Exception {
                                    mockMvc.perform(run(\"\"\"
                                                    query {
                                                      __schema { queryType { name } }
                                                      postType: __type(name: "Post") {
                                                        fields { name }
                                                      }
                                                    }\"\"\", \"{}\"))
                                            .andExpect(status().isOk())
                                            .andExpect(jsonPath("$.data.__schema.queryType.name").value("Query"))
                                            .andExpect(jsonPath("$.data.postType.fields[?(@.name == 'comments')].name")
                                                    .value(org.hamcrest.Matchers.hasItem("comments")));
                                }"""),
                        text("intro:graphiql-and-codegen", """
                                On that foundation: GRAPHIQL (dev-only here — the wiring section showed the profile-gated property, and this default-profile suite pins /graphiql answering the app's typed 404 envelope: the unrouted-path gotcha the routing topic documented, now rendered honestly since the exception-handling topic's ErrorResponse branch) gives you autocomplete from the live schema, inline field docs, and a query runner — the fastest way to explore this app's graph is docker compose up and open /graphiql. CODEGEN closes the loop on the client: point a generator (GraphQL Code Generator, Apollo tooling) at the schema — or at a documents file — and it emits typed clients whose functions RETURN the exact shape each document asks for; a TS client for posts(page:0,size:3){items{title}} gets back {posts:{items:{title:string}[]}} with nullability matching the SDL's ! markers. The same schema-first move the .proto made in the gRPC topic, made here in SDL. And the PLAYGROUND below is the browser version of this whole topic: the preset picker loads the catalog (including the N+1 probe and the error demo), the editors take any document and variables, and Run executes against the same engine /graphql serves — errors rendered with their extensions.code and requestId, the bit the guide's own client cares about:"""),
                        code("intro:client", "tsx",
                                "client/src/ui/demos/api/graphql_playground_demo.tsx — the playground renders errors[] with the taxonomy as data: extensions.code + requestId per error",
                                "client/src/ui/demos/api/graphql_playground_demo.tsx", """
                                const code = extensionText(error, 'code');
                                const requestId = extensionText(error, 'requestId');
                                // …
                                <span data-testid="graphql-error-code" className={`font-mono text-caption ${code === null ? 'text-on-surface-variant' : 'text-error'}`}>
                                  {code ?? 'no code in extensions'}
                                </span>
                                <span data-testid="graphql-error-request-id" className="font-mono text-caption text-on-surface-variant">
                                  requestId: {requestId ?? 'absent'}
                                </span>"""),
                        demo("intro:demo", "api:graphql-playground", "The GraphQL playground, live",
                                "Pick a preset (page query, variables fetch, the N+1 probe, aliases + fragments, a mutation, the error demo) or edit any document, then Run against the REAL schema through the same engine /graphql serves. The viewer renders {data, errors} — every error with its extensions.code and requestId — and the GraphiQL link opens the dev-only IDE beside it.")),
                section("terminal-companion", "The graph from a terminal",
                        "curl the /graphql endpoint with the preset documents — a page, the N+1 probe, a mutation, and a typed error.",
                        text("term:tour", """
                                GraphQL is delightfully curl-able: one endpoint, POST, JSON in, JSON out — no special client, no headers beyond the content type. The tour below runs the same documents the playground presets carry (the catalog endpoint serves them as JSON if you want to script against it: curl -s localhost:8080/api/demo/graphql/queries | jq). Start the server (docker compose up — the dev profile also mounts /graphiql), then take the tour; note every failure arrives HTTP 200 with the taxonomy in extensions."""),
                        code("term:page", "bash", "one round trip, exactly the asked-for shape — pagination as fields", null, """
                                # A page of posts: titles, statuses, versions — nothing else crosses the wire:
                                curl -s -X POST localhost:8080/graphql \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"query":"query { posts(page: 0, size: 3) { totalElements totalPages items { title status version } } }"}' | jq
                                # → {"data":{"posts":{"totalElements":12,"totalPages":4,
                                #     "items":[{"title":"The uniform interface is the contract that scales",
                                #                "status":"PUBLISHED","version":1}, …]}}}

                                # Variables: the same document, any id — no string surgery:
                                curl -s -X POST localhost:8080/graphql \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"query":"query One($id: ID!) { post(id: $id) { title comments { author } } }",
                                       "variables":{"id":"5b481618-f93a-4b46-572d-280949083b05"}}' | jq '.data.post.comments | length'
                                # → 3"""),
                        code("term:n-plus-one", "bash", "the N+1 probe: six posts, each with comments — ONE batched repository call behind it", null, """
                                # The query that would cost 7 calls with a naive resolver — served here by @BatchMapping:
                                curl -s -X POST localhost:8080/graphql \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"query":"query { posts(page: 0, size: 6) { items { title comments { author body } } } }"}' \\
                                  | jq '.data.posts.items | map({title, comments: (.comments | length)})'
                                # → [{"title":"The uniform interface is the contract that scales","comments":3},
                                #     {"title":"Nouns, not verbs: designing the resource first","comments":2}, …]
                                # (the suite proves the counter: 1 batch call, 0 single lookups)"""),
                        code("term:mutation-and-error", "bash", "a mutation through the same draft rules as REST; then the always-200 typed error", null, """
                                # Create — judged by PostDraft exactly as the REST POST body is:
                                curl -s -X POST localhost:8080/graphql \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"query":"mutation Create($input: PostInput!) { createPost(input: $input) { id title status version } }",
                                       "variables":{"input":{"title":"Hello from the terminal",
                                                             "body":"One door, two protocols.",
                                                             "tags":["graphql"]}}}' | jq '.data.createPost'
                                # → {"id":"<new-uuid>","title":"Hello from the terminal","status":"DRAFT","version":1}
                                #   (status omitted in the input ⇒ DRAFT)

                                # And the error demo: HTTP 200, taxonomy in extensions —
                                curl -s -X POST localhost:8080/graphql \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"query":"query { post(id: \\"does-not-exist\\") { title } }"}' \\
                                  | jq '{code: .errors[0].extensions.code, requestId: .errors[0].extensions.requestId, path: .errors[0].path}'
                                # → {"code":"not_found","requestId":"<the filter's id>","path":["post"]}""")));
    }
}
