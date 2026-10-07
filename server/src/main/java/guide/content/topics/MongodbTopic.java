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
 * Topic 19 — mongodb. Group data, order 4. Document storage taught from
 * THIS repository's own visits substrate: page-view analytics as the
 * honest case for documents (event-shaped writes, aggregate reads), the
 * optional-service discipline that keeps Mongo off the boot path,
 * MongoTemplate's Criteria and Aggregation fluent APIs quoted pipeline by
 * pipeline, TTL indexes, and the layering that keeps the domain
 * Mongo-free. One live demo: db:mongo-console (record, aggregate, explain
 * the pipelines, flip the area live).
 */
public final class MongodbTopic {

    private MongodbTopic() {
    }

    public static Topic topic() {
        return new Topic("mongodb", "MongoDB and Spring Data",
                "Document thinking: page-view analytics in MongoDB — a store chosen by read "
                        + "pattern, not fashion — wired as an optional service this app boots "
                        + "without, queried through MongoTemplate's Criteria and Aggregation "
                        + "APIs with the pipelines rendered inspectable, and kept behind a "
                        + "domain-owned port so the teaching stays storage-agnostic.",
                TopicGroupId.DATA, 4,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("document-thinking", "Document thinking",
                        "Schema per read pattern versus schema per entity; embedding versus referencing; why this guide's visits live in a collection while its posts stay in a table.",
                        text("doc:model", """
                                A relational schema normalizes around ENTITIES — one table per kind of thing, relationships expressed as keys, shape decided up front and policed on every write. A document database inverts the emphasis: the COLLECTION is shaped around a READ PATTERN, documents are JSON-like trees that can embed their context, and the shape is a convention the application maintains rather than a wall the database enforces. Neither is the upgrade of the other; they answer different questions. When data is ENTITY-SHAPED — a post, its status history, its comments; many relationships; correctness that must hold across rows — the relational model wins, and topics 16 through 18 spent their words saying exactly how. When data is EVENT-SHAPED — written once, never updated, never joined, and read almost exclusively through aggregates ("how many", "which most", "how long on average") — a document store is genuinely the cheaper tool, and forcing it into rows mostly buys you an append-only table plus the nagging feeling that you are paying for constraints nothing will ever exercise. This guide runs BOTH stores deliberately: PostgreSQL owns the entities (posts), MongoDB owns the events (page-view analytics) — not as a polyglot-fashion statement, but because the two substrates have genuinely different shapes."""),
                        text("doc:visits", """
                                THIS topic's substrate is the guide's own analytics: every page view a reader generates becomes one document — which topic, which path, how long they stayed, a pseudonymized visitor hash, when it happened, in which locale. Study the shape, because the design decisions are all visible in the signature:"""),
                        code("doc:record", "java",
                                "guide/domain/mongo/VisitDocument.java — the guide_visits document: a plain record, zero MongoDB imports",
                                "server/src/main/java/guide/domain/mongo/VisitDocument.java", """
                                public record VisitDocument(String id, String topicId, String path, String visitorHash,
                                        long durationMs, Instant occurredAt, String locale) {
                                """),
                        text("doc:why-this-shape", """
                                A visit has no relationships to defend — it references a topic by SLUG STRING, not by a foreign key, because analytics does not need referential integrity (a visit for a topic that later gets renamed is still a fact about a read that happened; renaming is an entity-side concern). It is immutable — there is no update path anywhere in the store port, because a page view never changes. It carries its read context EMBEDDED — path and locale ride inside the document, not in lookup tables — because no query ever asks for "the paths table"; every query asks for counts and averages OVER the document's own fields. And it is anonymous by construction: the API takes a visitor token and stores only its sha-256, so the raw identifier never reaches any storage at all (the privacy seam the security topic revisits properly). The honest contrast with posts-the-table is worth stating plainly: posts earned PRIMARY KEY, CHECK constraints, foreign-key-shaped growth, and optimistic-concurrency versions because entities need all four. Visits need none of them — and a schema that cannot enforce them costs nothing when nothing depends on them. That is the whole design argument, and the rest of this topic is engineering discipline around it.""")),
                section("the-optional-service-discipline", "The optional-service discipline",
                        "The app boots and stays healthy without Mongo; lazy once-guarded activation; typed unavailable envelopes; the compose service and the scenario flip — this repo's own pattern, quoted.",
                        text("opt:rule", """
                                MongoDB is this app's THIRD optional store (PostgreSQL and Redis came first), and the discipline is now a settled family rule: NO optional dependency may gate application boot, mark the process unhealthy, or crash a request with an unclassified exception. Every store pays the same three-part price. LAZY CONNECTIONS: the autoconfigured MongoClient builds without opening a socket (driver pools connect on first operation), the MongoTemplate bean is inert until queried, and this topic's live beans are @Lazy behind ObjectProviders — a test slice never constructs them at all. TIMED-OUT FAILURE: the connection URI carries the client timeouts that make an absent server BEARABLE — serverSelectionTimeoutMS bounds how long a live call waits (2 seconds here; the driver's 30-second default would feel like a hang in a demo) — declared where everyone can read them:"""),
                        code("opt:uri", "yaml",
                                "server/src/main/resources/application.yml — the connection string, twelve-factor env override, timeouts in the URI",
                                "server/src/main/resources/application.yml", """
                                data:
                                  mongodb:
                                    uri: ${SPRING_DATA_MONGODB_URI:mongodb://localhost:27017/guide?serverSelectionTimeoutMS=2000&connectTimeoutMS=2000}
                                """),
                        text("opt:health", """
                                The health verdict gets the same treatment the db and redis indicators got — with ONE spelling lesson this repo learned the hard way. The flag is management.health.MONGODB.enabled=false (indicator name "mongodb"), because a health endpoint that reports DOWN (after a server-selection wait) whenever an OPTIONAL service is absent is lying about the process — the app is up, serving its seeded console, and one area is degraded; the typed 503 from the live path is the honest per-call answer, and a liveness flip would be an operational overreaction. The lesson's teeth: Boot 4's spring-boot-mongodb registers the indicator under @ConditionalOnEnabledHealthIndicator(value="mongodb"), so the CLASSIC management.health.MONGO.enabled spelling (no d) is SILENTLY IGNORED — the property binds nothing, the indicator mounts anyway, and /actuator/health answers 503 with a Mongo timeout in its components while every log line looks fine. This repo proved it with a show-components diagnostic before believing the flag; when a disable-property "doesn't work", print the health body and read the indicator's actual name. (The observability topic revisits all of this with the honest readiness version.) The second half of the discipline is DEPLOYMENT-SIDE: the dev and prod compose files carry the mongo service — this app's development reality — healthchecked the way every sibling service is:"""),
                        code("opt:compose", "yaml",
                                ".docker/docker-compose.yml — the compose service: mongo:8, published port, named volume, mongosh ping healthcheck",
                                ".docker/docker-compose.yml", """
                                mongo:
                                  image: mongo:8
                                  container_name: springboot_developers_guide_mongo_dev
                                  restart: unless-stopped
                                  ports:
                                    - 27017:27017
                                  volumes:
                                    - sbdg-mongo-data:/data/db
                                  healthcheck:
                                    test: ["CMD", "mongosh", "--quiet", "--eval", "db.runCommand({ ping: 1 }).ok"]
                                """),
                        text("opt:activation", """
                                And the flip itself is PROGRAMMATIC and once-guarded — LiveMongoArea, the exact shape LiveDbArea established for PostgreSQL. The first live-mode call activates under a monitor: ping the server, create the indexes, and only then mark the 'mongo' area LIVE in the ScenarioStore (the public PATCH endpoint moves only the GLOBAL mode; per-area activation is the data layer's own deliberate call). Failure refuses the flip without throwing through any boot path — a Failed Result the two entry points expose in the two shapes callers need:"""),
                        code("opt:area", "java",
                                "guide/data/mongo/LiveMongoArea.java — the once-guarded activation: ping + indexes, flip the area LIVE only on success",
                                "server/src/main/java/guide/data/mongo/LiveMongoArea.java", """
                                public Result<String> activate() {
                                    if (liveActivated) {
                                        return Result.success(activatedDatabaseName);
                                    }
                                    synchronized (activationMonitor) {
                                        if (liveActivated) {
                                            return Result.success(activatedDatabaseName);
                                        }
                                        Result<String> activated = liveStore.activate();
                                        if (activated instanceof Failed<String> failed) {
                                            return failed;
                                        }
                                        scenarioStore.setDataMode(VisitStore.AREA, ScenarioDataMode.LIVE);
                                        activatedDatabaseName = activated instanceof Success<String> success
                                                ? success.value()
                                                : null;
                                        liveActivated = true;
                                        return activated;
                                    }
                                }
                                """),
                        text("opt:classifier", """
                                When Mongo is unreachable, the driver speaks in exceptions (server-selection timeouts, socket failures); the taxonomy speaks in Failure kinds. The classifier between them mirrors SchemaMigrator's SQLSTATE-class-08 walk — dependency-down (UNAVAILABLE, 503, "dependency": "mongo" in the details) versus our-bug (INTERNAL, 500) — because a malformed aggregation must not wear an outage's clothes:"""),
                        code("opt:classify", "java",
                                "guide/data/mongo/MongoVisitStore.java — the classifier: the driver's timeout/socket families are the Mongo spelling of SQLSTATE class 08",
                                "server/src/main/java/guide/data/mongo/MongoVisitStore.java", """
                                private static Failure unavailableOrInternal(String context, RuntimeException failure) {
                                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                                        if (cause instanceof MongoTimeoutException || cause instanceof MongoSocketException) {
                                            return unavailable(context, cause);
                                        }
                                        // …
                                    }
                                    return Failure.of(FailureKind.INTERNAL,
                                            context + ": " + failure.getMessage(),
                                            Map.of("cause", String.valueOf(failure.getClass().getSimpleName())));
                                }
                                """)),
                section("documents-and-repositories", "Documents and repositories",
                        "@Document/@Field mapping, MongoRepository versus MongoTemplate — where the repository abstraction stops fitting — and the store port this app puts in front of both.",
                        text("repo:mapping", """
                                Spring Data MongoDB maps classes to collections the same way JPA maps classes to tables, minus most of the ceremony. @Document names the collection; @Id marks the string key stored as _id; @Field spells each property's stored key (short, snake-y, mongo-idiomatic — wire names are storage's business, not the domain's). The mapping record for the visits looks like this — and notice WHERE it lives: in the data adapter, one package deep, not on the domain record:"""),
                        code("repo:doc", "java",
                                "guide/data/mongo/VisitDoc.java — the mapping record: the ONE class that knows what BSON looks like",
                                "server/src/main/java/guide/data/mongo/VisitDoc.java", """
                                @Document(collection = "guide_visits")
                                @CompoundIndex(name = "topic_recency_idx", def = "{'topicId': 1, 'occurredAt': -1}")
                                public record VisitDoc(

                                        @Id
                                        String id,

                                        @Field("topicId")
                                        String topicId,

                                        // …
                                        @Field("occurredAt")
                                        Instant occurredAt,
                                """),
                        text("repo:repository-vs-template", """
                                On top of that mapping Spring Data offers TWO access styles, and choosing between them honestly is this topic's first real decision. MONGOREPOSITORY (interface, derived queries: List<VisitDoc> findByTopicId(String topicId)) is the abstraction you already know from topic 18 — spectacular for CRUD-by-field on single collections, zero boilerplate. MONGOTEMPLATE is the hands-on engine: Criteria queries, Aggregation pipelines, hints, bulk ops, upserts, collection and index administration. The repository stops fitting exactly where THIS app's questions live: "top five topics by visit count, ties broken deterministically" is a $group/$sort/$limit pipeline, not a derived method name (findByAnything cannot express an average), and the moment you reach for @Query with hand-written JSON stages to compensate, you are using the repository abstraction as a decorator around the template anyway. The guide's rule of thumb, earned across both SQL topics and this one: derived repositories for entity CRUD, the template when the READ is the application — analytics, reports, search-shaped access. Because our substrate is nothing BUT read-pattern-shaped access, the port in front of it is the contract, and the template is the only adapter engine:"""),
                        code("repo:port", "java",
                                "guide/domain/mongo/VisitStore.java — the port: record plus the aggregate questions; note what it does NOT have (update, delete, version)",
                                "server/src/main/java/guide/domain/mongo/VisitStore.java", """
                                public interface VisitStore {

                                    String AREA = "mongo";

                                    VisitDocument record(VisitDraft draft);

                                    List<VisitDocument> recent(int limit);

                                    long countAll();

                                    long countByTopic(String topicId);

                                    List<TopicTally> topTopics(int limit);

                                    long avgDurationMs(String topicId);

                                    List<LocaleTally> byLocaleHistogram();
                                }
                                """),
                        text("repo:coords", """
                                The coordinates themselves, flagged like every Boot 4 / Testcontainers 2.x rename this repo has collected. The starter is the classic name (BOM-managed); the TEST module is the renamed 2.x artifact — org.testcontainers:testcontainers-mongodb, NOT the 1.x mongodb (which stops at 1.21.3 and does not exist in the 2.x line) — verified against Central's metadata:"""),
                        code("repo:pom", "xml",
                                "server/pom.xml — the two flagged coordinates: the BOM starter, and Testcontainers 2.x's renamed Mongo module",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-data-mongodb</artifactId>
                                </dependency>
                                <!-- … -->
                                <dependency>
                                    <groupId>org.testcontainers</groupId>
                                    <artifactId>testcontainers-mongodb</artifactId>
                                    <scope>test</scope>
                                </dependency>
                                """)),
                section("validation-in-the-app", "Validation in the app",
                        "Documents are schemaless-ish: the invariants live in the domain records' compact constructors (quoted), the boundary judges every field at once, and MongoDB's JSON Schema validator gets an honest mention.",
                        text("val:no-wall", """
                                PostgreSQL rejects a bad row at the door; MongoDB mostly shrugs. A collection will store any BSON shape you hand it — wrong types coerce or fail at query time, business rules simply do not exist. Topic 10 taught the three layers of validation (boundary, domain, storage); in a document store the storage layer is missing its wall, so the first two layers carry ALL the weight, and the discipline transfers exactly. The domain record's compact constructor is the load-bearing wall — every invariant a visit promises, enforced at every door that can build one:"""),
                        code("val:ctor", "java",
                                "guide/domain/mongo/VisitDocument.java — the compact constructor: the schema MongoDB will not enforce",
                                "server/src/main/java/guide/domain/mongo/VisitDocument.java", """
                                public VisitDocument {
                                    if (id == null || id.isBlank()) {
                                        throw new IllegalArgumentException("visit id must be a non-blank string");
                                    }
                                    String problem = topicIdProblem(topicId);
                                    if (problem != null) {
                                        throw new IllegalArgumentException("visit topicId " + problem);
                                    }
                                    // …
                                    problem = visitorHashProblem(visitorHash);
                                    if (problem != null) {
                                        throw new IllegalArgumentException("visit visitorHash " + problem);
                                    }
                                    // …
                                    problem = localeProblem(locale);
                                    if (problem != null) {
                                        throw new IllegalArgumentException("visit locale " + problem);
                                    }
                                }
                                """),
                        text("val:boundary", """
                                Those predicates are reusable and shared with the BOUNDARY's judge — VisitDraft.of takes what JSON binding actually hands a controller (nulls for absent fields), applies the documented defaults (visitorToken "anonymous", locale "en"), and names EVERY offending field in one typed answer, the exact shape PostDraft.of established in topic 11. Same stance, second substrate: judge everything at once, fail loudly at the edge, keep the constructors as the last wall:"""),
                        code("val:draft", "java",
                                "guide/domain/mongo/VisitDraft.java — the boundary judge: every problem named in one typed Failure",
                                "server/src/main/java/guide/domain/mongo/VisitDraft.java", """
                                public static Result<VisitDraft> of(String topicId, String path, String visitorToken,
                                        Long durationMs, String locale) {
                                    Map<String, String> fields = new LinkedHashMap<>();
                                    // …
                                    if (!fields.isEmpty()) {
                                        return Result.failed(Failure.of(FailureKind.VALIDATION,
                                                "request body failed validation", Map.of("fields", fields)));
                                    }
                                    return Result.success(new VisitDraft(
                                            topicId, path,
                                            visitorToken == null || visitorToken.isBlank() ? "anonymous" : visitorToken.strip(),
                                            durationMs,
                                            locale == null || locale.isBlank() ? "en" : locale.strip()));
                                }
                                """),
                        text("val:server-side", """
                                For completeness, MongoDB CAN police shape — $jsonSchema validators on createCollection enforce type and presence on insert — and for multi-writer collections (several services, or analytics pipelines you do not fully trust) they are worth the ceremony. This repo declines them for the same reason it declines database-side CHECKs it already owns in records: one writer, one code path, one source of truth, tested at the layer that can be unit-tested. The honest sentence is the one the audit-ledger topic already wrote: server-side validation is defense in depth for when the writer is not you.""")),
                section("queries-and-the-criteria-api", "Queries and the Criteria API",
                        "Criteria building, sort/limit, and the Aggregation pipeline ($group, $sort, $avg) — quoted from the real adapter — with the demo's /explain endpoint rendering the server-ready BSON.",
                        text("q:criteria", """
                                The template's fluent API is a THIN veneer over server-side JSON: a Criteria builds a query filter, Query adds sort/limit/skip, and every builder method has a one-line spelling in the mongo shell. The recent-visits feed is the canonical find — filter, sort descending, limit:"""),
                        code("q:recent", "java",
                                "guide/data/mongo/MongoVisitStore.java — the shaped find: Criteria, Sort, limit",
                                "server/src/main/java/guide/data/mongo/MongoVisitStore.java", """
                                @Override
                                public List<VisitDocument> recent(int limit) {
                                    return guarded(() -> {
                                        Query newestFirst = Query.query(Criteria.where("occurredAt").exists(true))
                                                .with(Sort.by(Sort.Direction.DESC, "occurredAt"))
                                                .limit(limit);
                                        return template.find(newestFirst, VisitDoc.class).stream()
                                                .map(VisitDoc::toDomain)
                                                .toList();
                                    });
                                }

                                @Override
                                public long countByTopic(String topicId) {
                                    return guarded(() -> template.count(
                                            Query.query(Criteria.where("topicId").is(topicId)), VisitDoc.class));
                                }
                                """),
                        text("q:aggregation", """
                                The aggregates are AGGREGATION PIPELINES: documents flowing through stages, each stage transforming the stream — $match filters, $group folds by a key and computes accumulators ($sum, $avg, $min, $max), $sort and $limit do what they say, $project reshapes. The topTopics question is the textbook pipeline, and its stages are shared verbatim with the explain endpoint further down — one list, two consumers:"""),
                        code("q:stages", "java",
                                "guide/data/mongo/MongoVisitStore.java — the topTopics pipeline: $group → ONE compound $sort → $limit",
                                "server/src/main/java/guide/data/mongo/MongoVisitStore.java", """
                                private List<AggregationOperation> topTopicsStages(int limit) {
                                    return List.of(
                                            Aggregation.group("topicId").count().as("visits"),
                                            // ONE compound $sort, not two adjacent sorts: a $sort is a
                                            // blocking stage, so a second sort re-sorts the first one's
                                            // output completely — last sort wins, ties broken by nothing.
                                            // The parity test caught exactly this mistake.
                                            Aggregation.sort(Sort.by(Sort.Order.desc("visits"), Sort.Order.asc("_id"))),
                                            Aggregation.limit(limit));
                                }
                                """),
                        code("q:run", "java",
                                "guide/data/mongo/MongoVisitStore.java — running it: $group's _id IS the grouped key, read by hand",
                                "server/src/main/java/guide/data/mongo/MongoVisitStore.java", """
                                @Override
                                public List<TopicTally> topTopics(int limit) {
                                    return guarded(() -> {
                                        AggregationResults<Document> results = template.aggregate(
                                                Aggregation.newAggregation(topTopicsStages(limit)),
                                                VisitDoc.class, Document.class);
                                        return results.getMappedResults().stream()
                                                // $sum emits int32 for small counts — read as Number, never assume Long.
                                                .map(row -> new TopicTally(
                                                        row.getString("_id"), ((Number) row.get("visits")).longValue()))
                                                .toList();
                                    });
                                }
                                """),
                        text("q:explain", """
                                Two habits in that adapter repay the attention. First, $group's output _id IS the grouped key — the adapter reads it by hand from a raw Document instead of hoping reflection maps it onto a record component, because explicit beats magical at the seam. Second, TRANSPARENCY AS AN ARTIFACT: the console's /explain endpoint converts the very same stage builders to the BSON Documents the driver would send — what Mongo WILL run, inspectable from the demo, with no server round trip:"""),
                        code("q:explain-pipeline", "java",
                                "guide/data/mongo/MongoVisitStore.java — the transparency artifact: each fluent stage rendered as its BSON Document",
                                "server/src/main/java/guide/data/mongo/MongoVisitStore.java", """
                                public List<Document> explainPipelines() {
                                    AggregationOperationContext context = Aggregation.DEFAULT_CONTEXT;
                                    return List.of(
                                            new Document("name", "topTopics").append("stages",
                                                    topTopicsStages(ReadVisitStats.TOP_TOPICS_LIMIT).stream()
                                                            .map(stage -> stage.toDocument(context))
                                                            .toList()),
                                            // …
                                            new Document("name", "avgDurationMs").append("stages",
                                                    List.of(Aggregation.group().avg("durationMs").as("avgMs")
                                                            .toDocument(context))));
                                }
                                """),
                        demo("q:console-demo", "db:mongo-console",
                                "The mongo console — record, aggregate, explain, flip live",
                                "Record a page visit and watch it land in the aggregates; read the seeded stats (top topics, average duration, locale histogram); press explain to see the $group/$sort/$limit pipelines rendered as the BSON the server would run; then flip the area live — with MongoDB running (docker compose up) records persist in the real collection, and without it the flip answers an honest hint while the fake keeps serving."),
                        text("q:parity", """
                                The numbers themselves are pinned by a PARITY contract: the pure functions in VisitAggregate (fixed-point half-up averaging, count-descending-then-id-ascending tie-break, locale-ascending histogram order) define the math, the in-memory fake computes with them directly, and the Testcontainers test replays the same seed into real MongoDB and asserts the $group pipelines answer IDENTICALLY — because an API whose aggregate results changed with the storage mode would be lying to somebody. That contract is not decorative: the parity test's first run CAUGHT A REAL PIPELINE BUG — two adjacent $sort stages (count, then id), where the second blocking $sort silently re-sorted away the first, so Mongo answered alphabetically while the fake answered by count. The fix was one compound $sort; the lesson is that a second implementation of the same question is the cheapest reviewer a pipeline will ever have. The live flip the console performs is deliberately boring — the same endpoints, the same envelopes, a different store answering:"""),
                        code("q:client-flip", "ts",
                                "client/src/ui/demos/db/mongo_console_demo.tsx — the console's flip: POST /mode, render the live database or the honest hint",
                                "client/src/ui/demos/db/mongo_console_demo.tsx", """
                                const flip = (dataMode: 'fake' | 'live') => {
                                  setModePanel({ kind: 'flipping' });
                                  void safeRepositoryCall(async () =>
                                    parseMode(
                                      await exchange(config, '/demo/mongo/mode', {
                                        method: 'POST',
                                        body: JSON.stringify({ dataMode }),
                                      }),
                                    ),
                                  ).then((result) => {
                                    setModePanel(
                                      result.ok
                                        ? { kind: 'done', view: result.value }
                                        : { kind: 'error', failure: result.failure },
                                    );
                                    refresh();
                                  });
                                };
                                """),
                        text("q:mode-edge", """
                                The server edge behind that flip is one switch on a Result — success answers the live database name, failure answers the hint and leaves the area fake (the optional-service discipline, observable from the console):"""),
                        code("q:mode-server", "java",
                                "guide/presentation/demo/MongoDemoController.java — the mode edge: live database name, or the honest hint",
                                "server/src/main/java/guide/presentation/demo/MongoDemoController.java", """
                                return switch (visitStore.attemptLive()) {
                                    case Success<String> success -> ResponseEntity.ok(new ModeView(
                                            VisitStore.AREA, "live", "live", success.value(), null));
                                    case Failed<String> failed -> ResponseEntity.ok(new ModeView(
                                            VisitStore.AREA, "fake", "live", null,
                                            "live requested but the area stayed fake: " + failed.failure().message()));
                                };
                                """)),
                section("indexes-including-ttl", "Indexes — including TTL",
                        "createIndex at activation, the compound index shaped like the query, and the TTL index that auto-expires visits — the canonical TTL use case.",
                        text("idx:createindex", """
                                Indexes are the same bargain in MongoDB as in SQL — a second write per insert, bought back on specific reads — with two twists worth teaching. First, creation: there is no migration tool standing over you (Flyway's Mongo support exists but this app declines it for the same lazy-once reason as always), so the indexes are created at ACTIVATION, programmatically, idempotently — ensureIndex maps to createIndex, a no-op for an identically-shaped existing index:"""),
                        code("idx:activate", "java",
                                "guide/data/mongo/MongoVisitStore.java — activation: ping, then the compound query index and the TTL index",
                                "server/src/main/java/guide/data/mongo/MongoVisitStore.java", """
                                public Result<String> activate() {
                                    try {
                                        template.getDb().runCommand(new Document("ping", 1));
                                        IndexOperations indexes = template.indexOps(VisitDoc.class);
                                        indexes.ensureIndex(new Index()
                                                .on("topicId", Sort.Direction.ASC)
                                                .on("occurredAt", Sort.Direction.DESC)
                                                .named("topic_recency_idx"));
                                        indexes.ensureIndex(new Index()
                                                .on("occurredAt", Sort.Direction.DESC)
                                                .expire(Duration.ofSeconds(TTL_SECONDS))
                                                .named("occurredAt_ttl_idx"));
                                        return Result.success(template.getDb().getName());
                                    } catch (RuntimeException failure) {
                                        return Result.failed(unavailableOrInternal(
                                                "the mongo area could not activate", failure));
                                    }
                                }
                                """),
                        text("idx:compound-and-ttl", """
                                The compound index is the query shape wearing b-tree clothes — topicId equality first, occurredAt descending second, exactly the leftmost-prefix discipline topic 16 taught for SQL (it serves countByTopic's equality AND recent-by-topic's sort; it does nothing for locale alone — nothing asks it to). The TTL INDEX is MongoDB's small gift to exactly this kind of data: expireAfterSeconds on a date field, and a background sweeper (roughly every 60 seconds) deletes documents whose timestamp is older than the TTL — analytics that expires itself, no cron, no delete job, no midnight script that forgot the WHERE. Thirty days is this collection's answer (TTL_SECONDS); the honest footnotes are that TTL deletes are best-effort and lazy (a document can outlive its expiry briefly), that the field must be a BSON date (Instant maps to one), and that the TTL runs PER INDEX — the same field can carry different expiries in different collections. The @CompoundIndex annotation on VisitDoc documents the same shape in code for readers; the programmatic path stays authoritative because it must not gate boot.""")),
                section("the-spring-data-mongodb-layering", "The Spring Data MongoDB layering",
                        "How this app's layers keep the domain Mongo-free: the port in domain, the template in data — the seam quoted.",
                        text("layer:rule", """
                                The dependency rule does not bend for a second database: presentation → domain ← data, and the MongoDB imports stop at the adapter boundary. The proof is mechanical — guide.domain.mongo contains ZERO org.springframework and ZERO org.mongodb imports (the domain record you met first is a plain record; the mapping lives one package deeper), and the composition root wires the seam explicitly so nobody downstream can reach past it:"""),
                        code("layer:config", "java",
                                "guide/config/MongoDemoConfig.java — the composition root: one router port, @Lazy live beans through ObjectProviders",
                                "server/src/main/java/guide/config/MongoDemoConfig.java", """
                                @Bean
                                VisitStore visitStore(ScenarioStore scenarioStore) {
                                    scenarioStore.setDataMode(VisitStore.AREA, ScenarioDataMode.FAKE);
                                    return new ScenarioRoutingVisitStore(
                                            scenarioStore, inMemoryVisitStore(), liveArea::getObject, liveStore::getObject);
                                }

                                @Lazy
                                @Bean
                                MongoVisitStore mongoVisitStore(MongoTemplate mongoTemplate) {
                                    return new MongoVisitStore(mongoTemplate);
                                }

                                @Lazy
                                @Bean
                                LiveMongoArea liveMongoArea(ScenarioStore scenarioStore, MongoVisitStore mongoVisitStore) {
                                    return new LiveMongoArea(scenarioStore, mongoVisitStore);
                                }
                                """),
                        text("layer:payoff", """
                                The router completes the seam — every call reads the area's mode AT CALL TIME and lands on whichever adapter answers, which is what makes the console's flip a runtime event instead of a restart:"""),
                        code("layer:router", "java",
                                "guide/data/mongo/ScenarioRoutingVisitStore.java — the call-time delegate read",
                                "server/src/main/java/guide/data/mongo/ScenarioRoutingVisitStore.java", """
                                private VisitStore delegate() {
                                    return scenarioStore.dataModeFor(AREA) == ScenarioDataMode.LIVE
                                            ? liveActivated()
                                            : fake;
                                }
                                """),
                        text("layer:why", """
                                What this buys is the same payoff the posts substrate cashed in Phase 6, restated for documents: the use cases (RecordVisit, ReadVisitStats, ReadRecentVisits) were written against the in-memory fake and serve MongoDB today without one changed line; the MockMvc suite exercises the whole console with Mongo pointed at a dead port; and the Testcontainers class replays the same seed into a real server and pins the pipelines' answers to the fake's — the port is the contract, the storage is a detail, and the layering is what keeps it one. The teaching point is not "always hide your database" — it is that HIDING IS CHEAP here (one interface, one router, one config file) and the tests it unlocks are worth far more than the indirection costs.""")),
                section("sql-vs-mongo-honest-table", "SQL versus Mongo — the honest table",
                        "Joins, transactions, schema, maturity, hiring: where each store wins, and why this app runs both deliberately.",
                        text("vs:where-relational-wins", """
                                JOINS AND MULTI-ROW FACTS: the relational model's crown. "Posts with their authors and comment counts, sorted by author" is one SELECT in SQL and a client-side stitching exercise in Mongo ($lookup exists, but it is the wrong tool's impression of a join — slower, clumsier, and a sign you wanted the other database). TRANSACTIONS ACROSS ENTITIES: MongoDB HAS multi-document transactions (since 4.0, with caveats — a replica set, performance cost), but the document model's whole bet is that you rarely need them: aggregate-in-one-document avoids the transaction, and when your writes genuinely span aggregates, the relational database does it natively and cheaper. SCHEMA AS CONTRACT: types, constraints, and referential integrity policed at the door versus conventions policed by your code — topic 16's whole argument, and the reason entities live in PostgreSQL here."""),
                        text("vs:where-mongo-wins", """
                                FLEXIBLE SHAPE: collections of documents whose fields vary and evolve (event payloads, provider responses, content drafts) absorb change without migration ceremonies — adding a field is writing it; the readers that do not know it ignore it. READ-PATTERN SHAPING: embed the context a read needs and answer it in one document fetch or one pipeline — no joins because the shape already IS the answer. HUGE HORIZONTAL WRITE SCALE: sharding is built into the product's bones (though the honest caveat belongs here: most applications never reach the scale where it matters, and a well-indexed PostgreSQL is astonishingly far). OPERATIONAL SIMPLICITY for the event-shaped workloads: TTL expiry, capped collections, tail-able cursors — features aimed squarely at logs, metrics, and analytics. MATURITY AND HIRING, honestly stated: both ecosystems are two decades deep; MongoDB's tooling and driver story are excellent, SQL's analytic talent pool and BI surface are broader. This app's verdict is the one worth copying: PostgreSQL for entities (posts, audit ledger — constraints, transactions, joins), MongoDB for events (visits — append-only, aggregate-read, self-expiring). Not both for everything — each for the shape it wins.""")),
                section("the-nosql-family-survey", "The NoSQL family survey",
                        "Mongo, Redis, Cassandra, Elasticsearch, Dynamo — one line each on when you would reach for it.",
                        text("survey:family", """
                                "NoSQL" is a negation, not a design — the family members share only the rejection of relational dominance, and each optimizes a different question. MONGODB (document): flexible-shaped trees read by their own fields and aggregated in pipelines — this topic, event- and content-shaped data. REDIS (key-value, in-memory): microseconds-to-answer ephemera — caches, sessions, rate counters, leaderboards, pub/sub — topic 20 builds it as this app's second store. CASSANDRA (wide-column): unfathomable WRITE throughput at massive row counts with linear scale-out, tolerant of node loss, at the price of query-shapes-decided-at-design-time (you model tables per query, no joins, no ad-hoc) — telemetry and time-series at serious scale. ELASTICSEARCH (inverted index): full-text search with relevance scoring, facets, and highlighting — when users type words into a box and expect ranked answers (PostgreSQL's tsvector, topic 17's honest take, carries you surprisingly far first). DYNAMODB (managed key-value/document): AWS-native single-digit-millisecond lookups at any scale with zero ops, bought with rigid access patterns, per-capacity pricing, and the weakest query story of the five. The selection rule that survives contact with production: pick by ACCESS PATTERN, not by fashion — the store whose native operations ARE your workload's questions is the one that fits; everything else is impedance you will pay for daily. And the zeroth rule: start with PostgreSQL, add a second store only when a measured access pattern demands it — which is exactly the sequence this guide's own architecture followed.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole topic through curl: record a visit, read the feed, ask the aggregates, explain the pipelines, flip the area live — with mongosh alongside.",
                        text("term:setup", """
                                The server carries everything this topic taught, live. The tour speaks to a server on localhost:8080 (docker compose up also starts MongoDB — flip the area live on the last step to feel the difference; without it, the flip answers the honest hint and everything else still works). jq pretty-prints. mongosh users get the same facts natively — use guide, db.guide_visits.find().sort({occurredAt: -1}).limit(5), db.guide_visits.aggregate([{$group: {_id: "$topicId", visits: {$sum: 1}}}, {$sort: {visits: -1}}]) — but the curl tour works from anywhere the API does."""),
                        code("term:record-recent", "bash",
                                "record a visit, then read the newest feed", null, """
                                # record one page view (visitorToken optional — only its sha-256 is stored)
                                curl -s -X POST localhost:8080/api/demo/mongo/visits -H 'Content-Type: application/json' \\
                                  -d '{"topicId":"mongodb","path":"/topics/mongodb","durationMs":91000,"locale":"en"}' | jq '.visit | {id, topicId, visitorHash}'

                                # the newest visits, newest first
                                curl -s 'localhost:8080/api/demo/mongo/visits/recent?limit=3' | jq '.visits[] | {topicId, durationMs, locale}'
                                """),
                        code("term:stats-explain", "bash",
                                "the aggregates, then the pipelines that answer them", null, """
                                # totals, top topics, average duration, locale histogram (fake mode answers from the seed)
                                curl -s 'localhost:8080/api/demo/mongo/stats' | jq '{totalVisits, avgDurationMs, topTopics, localeHistogram}'

                                # narrow to one topic: topicVisits + avg describe that topic alone
                                curl -s 'localhost:8080/api/demo/mongo/stats?topicId=jdbc-sql' | jq '{filteredByTopic, topicVisits, avgDurationMs}'

                                # the transparency artifact: the $group/$sort/$limit stages the live store would run
                                curl -s localhost:8080/api/demo/mongo/explain | jq '.pipelines[] | {name, stages}'
                                """),
                        code("term:mode", "bash",
                                "flip the area live (or collect the honest hint when MongoDB is absent)", null, """
                                # with docker compose up (MongoDB healthy): the flip activates lazily and reports the database
                                curl -s -X POST localhost:8080/api/demo/mongo/mode -H 'Content-Type: application/json' \\
                                  -d '{"dataMode":"live"}' | jq '{dataMode, database, hint}'

                                # without MongoDB: same call, the area stays fake, the hint says why — nothing errors
                                # back to the seeded console anytime
                                curl -s -X POST localhost:8080/api/demo/mongo/mode -H 'Content-Type: application/json' \\
                                  -d '{"dataMode":"fake"}' | jq '.dataMode'
                                """),
                        text("term:what-to-notice", """
                                Three things to notice on the tour. The recorded visit's visitorHash is 64 hex characters — the token you sent never appears anywhere again, by design. The stats after a record change IMMEDIATELY (the fake's aggregates are computed over its window on every call), and after a live flip they RESET to the real collection's truth — two stores, never pretending to share data. And the flip-without-Mongo response is a 200 with a hint, while a normal endpoint hit while the area is live-and-down would serve the typed 503 envelope — two different honest answers for two different questions, which is the optional-service discipline in one round trip.""")));
    }
}
