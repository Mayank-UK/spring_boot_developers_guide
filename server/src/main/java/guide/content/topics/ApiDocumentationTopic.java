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
 * Topic 12 — api-documentation. Group apis, order 2. The contract made
 * executable: why machine-readable docs beat handwritten ones, the anatomy of
 * an OpenAPI 3.1 document, generated-versus-spec-first and why this guide
 * chose generated, Swagger UI at /docs (one property), the drift test that
 * keeps the whole thing honest, and the typed-contract siblings (GraphQL
 * schema, .proto) the later topics build on — all against THIS app, whose
 * real spec springdoc-openapi generates at /v3/api-docs from the very
 * controllers the previous topics built.
 */
public final class ApiDocumentationTopic {

    private ApiDocumentationTopic() {
    }

    public static Topic topic() {
        return new Topic("api-documentation", "API documentation",
                "The contract as code: an OpenAPI 3.1 document generated from the real "
                        + "controllers, curated with light annotations, rendered by Swagger UI at "
                        + "/docs, and pinned by a drift test — so the docs cannot rot, because "
                        + "they ARE the build.",
                TopicGroupId.APIS, 2,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("why-machine-readable-docs", "Why machine-readable docs",
                        "Handwritten docs rot; a contract the build produces does not — three consumers make it worth it.",
                        text("machine:rot", """
                                Every API has documentation; most of it is fiction. The wiki page listing endpoints, the README's curl examples, the Confluence table of parameters — all of it was true once, on the day somebody wrote it, and every week since, the code drifted further from the description. The failure mode is structural, not moral: documentation written BESIDE code changes at a different speed than code, by different people, with no mechanism forcing agreement. A rename in a controller takes thirty seconds and one commit; updating every prose page that mentions the old name is a task nobody owns. The docs rot because nothing breaks when they lie. The fix is not discipline. The fix is to stop handwriting the thing the build can produce: describe the API in a MACHINE-READABLE CONTRACT that is generated from (or verified against) the code, so that agreement is enforced by a compiler or a test rather than by memory."""),
                        text("machine:consumers", """
                                Three kinds of consumer make that contract worth more than its prose equivalent. HUMANS get it rendered: Swagger UI turns the document into an interactive reference page where every operation is grouped, every parameter explained, and every endpoint is executable against the running server — try-it-out, not trust-me. GENERATORS get it compiled: openapi-generator turns one document into typed clients for TypeScript, Kotlin, Python, Go — your API's consumers import Post types and listPosts operations instead of hand-writing fetch wrappers against prose, and a breaking change becomes THEIR compile error, not their 3 a.m. incident. CI gets it as a CONTRACT TO VERIFY: the document can be linted (naming rules, must-have error responses), diffed against last release's document to alarm on breaking changes, and — the approach this topic builds — tested against the live server so the served spec can never silently contradict the code. That is the phrase to keep: CONTRACT-AS-CODE. The document is not about the API; it is a build artifact OF the API, exactly as real as the jar."""),
                        text("machine:this-guide", """
                                This guide's stack for that contract: springdoc-openapi, the Spring-idiomatic generator that derives an OpenAPI document from the controllers this app really ships, enriched by a deliberately SMALL set of annotations; Swagger UI mounted at /docs (the family convention the README already promises); one drift test that GETs the live document and pins the core of the documented surface, so a renamed route fails the suite before it fails a client. The whole apparatus is three files of actual code plus one dependency and one configuration property — which is itself the topic's first lesson: documentation infrastructure should be a byproduct of the architecture you already have, not a parallel one you now must staff.""")),
                section("openapi-anatomy", "OpenAPI anatomy",
                        "The document's shape: paths and operations, components and schemas, parameters versus requestBody — read from this app's real spec.",
                        text("anatomy:document", """
                                The OpenAPI Specification (currently 3.1) is a JSON Schema vocabulary for describing HTTP APIs: a single document — JSON or YAML, same shape — that names every operation, parameter, body, and response. The top level is a map with a handful of keys. openapi pins the spec version ("3.1.0" — 3.1's headline change: schemas are now full JSON Schema 2020-12, so a schema document is portable to non-OpenAPI tooling). info identifies the API: title, description, version. servers lists base URLs. tags declares the groups the UI renders. And the two big ones carry the substance: paths (every operation, keyed by path template) and components (the reusable schemas those operations reference). This app's document is generated at runtime by springdoc 3.1.1 — the version line built against Spring Boot 4.1 / Framework 7, which is why it is the one in the pom; the 2.x line ends at Boot 3 and does not load here."""),
                        text("anatomy:paths", """
                                paths is the heart. Each key is a PATH TEMPLATE exactly as the router knows it — /api/demo/api/posts/{id}, braces and all; if the document's paths disagree with the router's paths, every generator downstream inherits the lie. Each value is a PATH ITEM holding one object per HTTP method — get, post, put, delete — and each of those is an OPERATION: operationId (the operation's stable name; client generators turn it into a function name, which is why you must own it rather than let it default to the Java method name), summary and description for humans, parameters (everything OUTSIDE the body: path, query, header, cookie — each with a name, an in location, and a schema), requestBody (one body, one schema), and responses (status code → description + content schema). Here is the real thing this build serves, excerpted — the posts collection as springdoc derived it from the previous topic's controller:"""),
                        code("anatomy:spec-excerpt", "text",
                                "an excerpt of GET /v3/api-docs on this very server — the posts collection, derived from ApiDesignDemoController",
                                null, """
                                {
                                  "openapi": "3.1.0",
                                  "info": {
                                    "title": "Spring Boot Developer's Guide API",
                                    "version": "1"
                                  },
                                  "paths": {
                                    "/api/demo/api/posts": {
                                      "get": {
                                        "tags": ["Posts"],
                                        "summary": "List posts — paged, filtered, sorted",
                                        "operationId": "listPosts",
                                        "parameters": [
                                          { "name": "page", "in": "query", "required": false,
                                            "schema": { "type": "integer", "format": "int32", "default": 0 } },
                                          // … size (default 10), sort, status, tag — every query knob, typed
                                        ],
                                        "responses": {
                                          "200": {
                                            "description": "One page of the collection",
                                            "content": { "application/json": { "schema": { "$ref": "#/components/schemas/PagedPosts" } } }
                                          }
                                        }
                                      }
                                      // "post": create — requestBody CreatePostRequest, 201 → Post
                                    }
                                  },
                                  "components": {
                                    "schemas": {
                                      "Post": {
                                        "type": "object",
                                        "properties": {
                                          "id": { "type": "string" },
                                          "status": { "type": "string", "enum": ["draft", "published"] },
                                          "version": { "type": "integer", "format": "int64" },
                                          "createdAt": { "type": "string", "format": "date-time" }
                                          // … title, body, tags, updatedAt — the record, faithfully
                                        }
                                      }
                                    }
                                  }
                                }"""),
                        text("anatomy:components", """
                                components/schemas is the DRY half: named schemas, referenced everywhere by $ref — the excerpt's PagedPosts answer and the Post it wraps are the same objects the create and update operations return, defined once. Two details reward attention. First, the parameters-versus-requestBody split is the spec modeling HTTP honestly: parameters are small, enumerable, and usually scalar; the body is one document with its own schema — which is why the previous topic's validation story (per-field 422s) maps so cleanly onto per-property schema constraints. Second, the schemas in this document were derived from Java records — springdoc reads the components the same way Jackson does — so the schema is exactly as honest as the types: Post's eight fields appear because the record declares eight components, the status property carries the enum [draft, published] because that is what the PostStatus type allows, and nothing else ever will.""")),
                section("generated-vs-spec-first", "Generated versus spec-first",
                        "springdoc derives the document from code; spec-first reviews the document before code exists — what each buys, and why this guide chose generated.",
                        text("gen:two-worlds", """
                                There are exactly two directions to produce the contract. GENERATED (code-first): the code is the source of truth and the document falls out of it — springdoc walks every @RequestMapping, binds every parameter type, resolves every schema, and serves the result. SPEC-FIRST: the document is the source of truth — it is authored, REVIEWED as a design artifact, frozen as a contract, and then code is written (or generated) to satisfy it. Both are legitimate; they optimize for different risks. The question is which failure you fear more: documentation lying about code (generated docs solve this by construction), or code being built before the design was scrutinized (spec-first solves this by construction)."""),
                        text("gen:generated", """
                                Spring shops default to generated, and for reasons worth making explicit. The routing mappings ARE the truth of the API — the previous topics built them, the router enforces them — so a second document claiming to describe the same thing is a drift liability unless it is derived. springdoc's derivation needs NO new source of truth: the mappings produce the paths, the parameter and return types produce the schemas. What types cannot say — summaries, stable operationIds, which schema a ResponseEntity<?> wildcard actually returns — the ANNOTATIONS add, and there is the design decision this topic's code makes visible: annotations are CURATION, a budget you spend where documentation value is highest, not a tax you pay on every handler. The posts resource is the documented specimen, so it carries the budget:"""),
                        code("gen:annotations", "java",
                                "guide/presentation/demo/ApiDesignDemoController.java — the curation budget spent where it pays: tag, stable operationIds, and the response declaration a wildcard return erases",
                                "server/src/main/java/guide/presentation/demo/ApiDesignDemoController.java", """
                                @Tag(name = "Posts", description = "The api-design topic's resource: full CRUD with filtering, "
                                        + "pagination, and optimistic concurrency (If-Match/ETag).")
                                @RestController
                                @RequestMapping("/api/demo/api")
                                public class ApiDesignDemoController {

                                    // …
                                    @Operation(summary = "List posts — paged, filtered, sorted",
                                            operationId = "listPosts",
                                            description = "Defaults: page=0, size=10, sort=createdAt,desc. Bogus sort/status/tag "
                                                    + "values answer the 422 validation envelope, never a 500.",
                                            responses = @ApiResponse(responseCode = "200", description = "One page of the collection",
                                                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                                            schema = @Schema(implementation = PagedPosts.class))))
                                    @GetMapping(value = "/posts", produces = MediaType.APPLICATION_JSON_VALUE)
                                    ResponseEntity<?> list(@RequestParam(defaultValue = "0") int page,
                                            // … five typed query parameters, each becoming an operation parameter
                                    """),
                        text("gen:wildcard", """
                                Read that specimen twice for the two moves it teaches. The explicit operationId is STABILITY: springdoc otherwise derives the id from the method name, so renaming list() to search() for clarity would silently rename the generated client function every consumer compiled against — the annotation turns a quiet break into a deliberate act. And the @ApiResponse with @Schema(implementation = …) is HONESTY RESTORED: the handler returns ResponseEntity<?>, and through a wildcard the generator cannot see any schema — without the declaration, the 200 response in the excerpt would have NO content schema and PagedPosts would not exist in components at all. This is the honest limit of generated docs: the generator sees types, not behavior. It knows what a Post record looks like; it does not know a stale version answers 409 unless a declaration or a common error convention says so. Generated docs are exactly as honest as the types and annotations allow — which is why the drift test in the keeping-docs-honest section exists to check the bill. At the other end of the budget, the minimal treatment the same app gives its liveness endpoint:"""),
                        code("gen:health-light", "java",
                                "guide/presentation/HealthController.java — the other end of the budget: a tag and one summary, nothing else",
                                "server/src/main/java/guide/presentation/HealthController.java", """
                                @Tag(name = "Health", description = "Liveness of the guide server — no dependencies, on purpose.")
                                @RestController
                                public class HealthController {

                                    // …
                                    @Operation(summary = "Liveness probe", operationId = "getHealth",
                                            description = "Succeeds the moment the web layer answers — deliberately independent "
                                                    + "of every other subsystem.")
                                    @GetMapping("/api/health")
                                """),
                        text("gen:spec-first-and-choice", """
                                Spec-first earns its ceremony when the API is a PRODUCT consumed outside your team: public APIs, partner integrations, or API-first organizations where the review of the DOCUMENT is the design review — an interface disagreement caught in a 200-line YAML diff is an hour; caught in a sprint of built code it is a week. Spec-first tooling then pays twice: server stubs and client SDKs both generate from the reviewed document. Its cost is the mirrored rot: now the CODE can drift from the spec, so you need contract tests asserting the implementation satisfies the document — the same enforcement problem, moved. This guide chose GENERATED, because the code is the single source of truth the whole book is built on, and enforces the two things generation cannot give (presence and stability) with the drift test — while borrowing spec-first's best habit as process: the design review happens on the ROUTING and the RESOURCE before the handlers exist, exactly as the api-design topic did it.""")),
                section("serving-swagger-ui", "Serving Swagger UI",
                        "One starter mounts the generator, the document, and the interactive UI — one property puts it at /docs.",
                        text("ui:starter", """
                                The dependency is the whole installation. springdoc-openapi-starter-webmvc-ui brings three mounted pieces: the generator that serves the document at /v3/api-docs (JSON, always current — it is built from the live application context on request); a small swagger-config endpoint the UI reads; and Swagger UI itself as a webjar. One version note matters more here than usual: the starter's MAJOR VERSION tracks Spring Boot's — 2.x serves Boot 2/3, the 3.x line serves Boot 4 / Framework 7. This app runs Boot 4.1, so the pom pins the 3.x line:"""),
                        code("ui:starter-pom", "xml",
                                "server/pom.xml — the starter; 3.1.1 is the line built against Boot 4.1 (its own parent POM is spring-boot-starter-parent 4.1.0)",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>org.springdoc</groupId>
                                    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                                    <version>3.1.1</version>
                                </dependency>
                                """),
                        text("ui:path", """
                                Everything springdoc does is configurable through springdoc.* properties, and the guide overrides exactly ONE — where the UI mounts. The default UI path is /swagger-ui.html, which is a fine default and a terrible front door: it is the tool's name, not the product's. This guide — like its express sibling — puts the interactive docs at /docs, one property in application.yml:"""),
                        code("ui:yml", "yaml",
                                "server/src/main/resources/application.yml — the single override: Swagger UI at /docs; /v3/api-docs stays at its default",
                                "server/src/main/resources/application.yml", """
                                springdoc:
                                  swagger-ui:
                                    path: /docs
                                """),
                        text("ui:info", """
                                The other deliberate touch is the document's own identity. Derivation can discover every path and schema, but it cannot know what this API is CALLED — a title and a version are things only humans decide — so springdoc defines one extension point for them: an OpenAPI bean, merged into the generated document. It lives in the topic's config beside the demo wiring, and the drift test pins its title so even the metadata cannot silently change:"""),
                        code("ui:config-bean", "java",
                                "guide/config/ApiDocsDemoConfig.java — the one springdoc customization: the info block derivation cannot know",
                                "server/src/main/java/guide/config/ApiDocsDemoConfig.java", """
                                @Bean
                                OpenAPI guideOpenApi() {
                                    return new OpenAPI().info(new Info()
                                            .title("Spring Boot Developer's Guide API")
                                            .description("The guide server's whole HTTP surface — generated from the "
                                                    + "controllers at runtime by springdoc-openapi. Interactive docs at "
                                                    + "/docs; the raw contract at /v3/api-docs.")
                                            .version("1"));
                                }
                                """),
                        text("ui:what-you-get", """
                                What the UI gives a consumer is more than reading: every tag becomes a collapsible section, every operation renders its parameters as a form, every schema is a link you can walk, and TRY-IT-OUT turns the page into a client — fill the form, execute, and the real request hits the real server with the real envelope coming back (toggle the ScenarioStore's injectFailure first, and the documented 503 arrives exactly as described — the UI executing against a live server is also the fastest way to notice docs that lie). That interactivity is why MOUNTING the UI matters more than generating the JSON: the document serves tools, the page serves people, and /docs is where the two meet. The link panel below is the topic's demo — it opens the real mount and reports the coverage of the real document, live:"""),
                        demo("ui:demo", "api:docs-link", "The docs link, live",
                                "The real Swagger UI at /docs, plus a coverage report computed by the server from its own live /v3/api-docs document: path, operation, and schema counts, sample paths grouped by surface, and the spec version — derived, never hand-maintained."),
                        text("ui:coverage", """
                                That coverage report deserves a look as a design, because it is the topic's thesis in one endpoint. The server does not maintain a list of its endpoints — it FETCHES its own /v3/api-docs, exactly as an outside consumer would, and counts what it finds: paths, operations, schemas, and a capped sample grouped by surface. The fetch is adapter-isolated (one private method; the base URL derives from the incoming request, so it works on any port), and the counting is a pure function of the parsed document. The report can therefore never be stale or flattering — it is arithmetic over the same bytes the terminal companion reads:"""),
                        code("ui:coverage-endpoint", "java",
                                "guide/presentation/demo/ApiDocsDemoController.java — the demo endpoint: fetch the live document, count it",
                                "server/src/main/java/guide/presentation/demo/ApiDocsDemoController.java", """
                                @GetMapping(value = "/coverage", produces = MediaType.APPLICATION_JSON_VALUE)
                                CoverageReport coverage() {
                                    return CoverageReport.of(fetchLiveSpec());
                                }

                                // …
                                private JsonNode fetchLiveSpec() {
                                    URI specUri = ServletUriComponentsBuilder.fromCurrentContextPath()
                                            .path("/v3/api-docs")
                                            .build()
                                            .toUri();
                                    String body = restClient.get().uri(specUri).retrieve().body(String.class);
                                    // …
                                    return objectMapper.readTree(body);
                                }
                                """)),
                section("keeping-docs-honest", "Keeping docs honest",
                        "Generated does not mean true: the drift test pins the documented surface, annotations curate it, and CI is the enforcement point.",
                        text("honest:problem", """
                                Generated documents solve staleness — they cannot describe a route that no longer exists. They do NOT automatically solve honesty: a generated spec can still lie by OMISSION (the wildcard return that dropped PagedPosts from components until one annotation restored it), by DRIFT (the spec served by build 42 differing from build 41 in a way no client approved), and by COMPLACENCY (nobody reads page two of a generated document, so an operation that changed shape ships anyway). Honesty is not a property you have; it is a property you ENFORCE. This app enforces it with one test that deserves to be read in full, because it is the mechanical form of everything this topic argued:"""),
                        code("honest:drift-test", "java",
                                "server/src/test/java/guide/presentation/OpenApiSpecDriftTest.java — the contract, executable: rename a route, an operation, or a schema field without updating this and the suite goes red",
                                "server/src/test/java/guide/presentation/OpenApiSpecDriftTest.java", """
                                @Test
                                void theCorePathsArePresent() throws Exception {
                                    mockMvc.perform(get("/v3/api-docs"))
                                            .andExpect(status().isOk())
                                            .andExpect(jsonPath("$.paths['/api/health']").isNotEmpty())
                                            // … /api/content, /api/content/{topicId}, /api/demo/api/posts
                                            .andExpect(jsonPath("$.paths['/api/demo/api/posts/{id}']").isNotEmpty());
                                }

                                @Test
                                void postsOperationsKeepStableIdsAndTheRealSchema() throws Exception {
                                    mockMvc.perform(get("/v3/api-docs"))
                                            .andExpect(status().isOk())
                                            .andExpect(jsonPath("$.paths['/api/demo/api/posts'].get.operationId").value("listPosts"))
                                            // … createPost, getPost, updatePost, deletePost — the stable ids generators key on
                                            .andExpect(jsonPath("$.components.schemas.Post.properties.title").isNotEmpty())
                                            // … id, body, tags, status, version — the resource's real fields, pinned
                                            .andExpect(jsonPath("$.tags[?(@.name == 'Posts')]").isNotEmpty());
                                }
                                """),
                        text("honest:dissection", """
                                Dissect it assertion by assertion, because each one buys a different guarantee. The SPEC VERSION pin ("3.1.0") fails the day a dependency upgrade changes the document dialect — a visible, deliberate act instead of a silent one. The PATH assertions are the drift alarm proper: rename /api/content to /api/topics without updating this test and the suite is red BEFORE a single client notices — compare that thirty-second red bar with the prose-docs world, where the same rename ships silently and the wiki lies forever. The OPERATIONID assertions protect the generators: an id change is a breaking change to every generated client function, invisible to any compiler on YOUR side. The SCHEMA FIELD assertions protect the wire: renaming Post.version to revision breaks consumers who compiled against the generated type, in ways their tests catch and yours never will. And notice what the test does NOT pin: the dozens of sibling demo endpoints. It pins the CURATED core — the surface this topic documents and the content/health spine — because that is what the annotations promised; the rest of the app appears in the spec by derivation, documented honestly at the mechanical level (paths, types) and minimally at the human level, which is exactly the curation decision made visible in the annotations section."""),
                        text("honest:ranges", """
                                The coverage demo's test makes the complementary point: it asserts RANGES, not totals — paths >= 20, every path carrying at least one operation — because sibling topics keep adding endpoints to the same document, and a test that pinned the count would break every honest addition. That split is the whole philosophy in two files: the DRIFT test pins what the documentation PROMISES (exact, because clients depend on exact); the COVERAGE test checks what the document MEASURES (ranges, because growth is healthy):"""),
                        code("honest:ranges-test", "java",
                                "server/src/test/java/guide/presentation/demo/ApiDocsDemoControllerTest.java — ranges, not totals: growth is healthy, drift is not",
                                "server/src/test/java/guide/presentation/demo/ApiDocsDemoControllerTest.java", """
                                assertTrue(report.path("paths").asInt() >= 20,
                                        "the whole app's surface is documented, not just the posts demo");
                                assertTrue(report.path("operations").asInt() >= report.path("paths").asInt(),
                                        "every path carries at least one operation");
                                """),
                        text("honest:ci", """
                                The enforcement point is CI, and that is not a metaphor — the drift test is an ordinary @SpringBootTest living in the same suite as every other test, gated by the same mvn test the pipeline already runs. That placement is what converts documentation from an intention into an INVARIANT: nobody merges a renamed route without either updating the spec's pin (a visible, reviewable act) or explicitly deleting the pin (an even more visible one). This is the same move the whole book keeps making — the content registry validates itself at startup, the snippet checker fails on unsourced code, and now the API contract fails on undocumented change. Each is one small test standing where a human's memory used to.""")),
                section("beyond-rest-docs", "Beyond REST docs",
                        "The same problem, solved by construction, in GraphQL's schema and gRPC's .proto — plus the style-guide layer above the machine layer.",
                        text("rest:siblings", """
                                This topic's machinery exists because REST's contract is not inherent: HTTP gives you methods and statuses, but the shapes — paths, parameters, schemas — are yours to describe, so the description must be derived and defended. Two sibling protocols in this same guide make the contract native. GRAPHQL (next topic) puts the typed contract IN the protocol: the schema is the API, introspection is built in, and the playground's docs are generated from the live schema on every page load — they cannot rot because there is no second document to rot; the cost is that the schema must be designed up front, which is spec-first by construction. GRPC (topic 15) compiles its contract: the .proto file defines services and messages ONCE, and the same file generates the server skeleton, the client stubs, and the docs — the contract is not merely readable, it is load-bearing at compile time on both ends. The spectrum is worth seeing whole: REST re-derives its contract and defends it with tests; GraphQL serves its contract and defends nothing; gRPC compiles its contract and needs no defense. Each buys safety at the price of flexibility somewhere else — which is why all three exist."""),
                        text("rest:style-guides", """
                                One layer above the machine contract belongs to people: the API STYLE GUIDE — a written set of conventions (collections are plural, errors use one envelope, pagination is cursor-or-offset but ONE of them, dates are RFC 3339) enforced by LINTERS over the machine document rather than by code review arguments. Spectral rulesets and the public guides from Google and Zalando are the references worth stealing from; the value is not any single rule but the property a guide shares with everything else in this topic — it moves API quality from tribal memory into an artifact the build can check. A new engineer joins, reads the guide, and writes endpoints that look like the rest on day one; CI reads the ruleset and rejects the ones that do not. Documentation, at every altitude this topic visited, is the same idea: what the API does, written down somewhere the drift can be caught.""")),
                section("terminal-companion", "The spec from a terminal",
                        "curl and python3: count the contract, extract a schema, diff a snapshot against the live document, open /docs.",
                        text("term:tour", """
                                The document is JSON, and python3 — on virtually every dev box — makes it queryable with one-liners: the fastest documentation review there is. The tours below read the same /v3/api-docs the drift test reads; run them against a live server (docker compose up, or GuideApplication). The last one is the manual version of the drift test: save a snapshot, and later diff the normalized live document against it — any contract change shows up as a diff you can read in seconds, which is also exactly how you would review a proposed API change in a pull request."""),
                        code("term:count", "bash", "the contract, counted — paths, operations, schemas", null, """
                                # The whole surface in one number, then grouped by what owns it:
                                curl -s localhost:8080/v3/api-docs \\
                                  | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["paths"]))'

                                # Every operation, as METHOD + path pairs:
                                curl -s localhost:8080/v3/api-docs \\
                                  | python3 -c 'import json,sys; d=json.load(sys.stdin)["paths"]; print("\\n".join(f"{m.upper()} {p}" for p,ops in d.items() for m in ops if m != "parameters"))' | head

                                # The server's own coverage report (the demo endpoint's numbers):
                                curl -s localhost:8080/api/demo/api-docs/coverage
                                # → {"paths":…,"operations":…,"schemas":…,"specVersion":"3.1.0"}"""),
                        code("term:extract", "bash", "one schema, extracted — the resource's contract as JSON", null, """
                                # The Post schema exactly as generators receive it:
                                curl -s localhost:8080/v3/api-docs \\
                                  | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["components"]["schemas"]["Post"], indent=2))'

                                # Which paths reference it (the $ref web, walked backwards):
                                curl -s localhost:8080/v3/api-docs \\
                                  | python3 -c 'import json,sys; d=json.load(sys.stdin); print([p for p,ops in d["paths"].items() if "Post" in json.dumps(ops)])'

                                # The interactive front door — open it in a browser:
                                open http://localhost:8080/docs"""),
                        code("term:drift", "bash", "the manual drift alarm: snapshot once, diff forever", null, """
                                # Pin today's contract (normalized: key order cannot fake a change):
                                curl -s localhost:8080/v3/api-docs | python3 -m json.tool --sort-keys > /tmp/api-spec-pinned.json

                                # Later — after your change, or on another build — the one-line review:
                                diff <(python3 -m json.tool --sort-keys /tmp/api-spec-pinned.json) \\
                                     <(curl -s localhost:8080/v3/api-docs | python3 -m json.tool --sort-keys)
                                # → silence means the contract held; any hunk is a change your consumers must approve""")));
    }
}
