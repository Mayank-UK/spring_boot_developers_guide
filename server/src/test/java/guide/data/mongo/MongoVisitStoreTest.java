package guide.data.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import guide.core.errors.AppException;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.mongo.VisitDocument;
import guide.domain.mongo.VisitDraft;
import java.time.Duration;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * MongoVisitStore against REAL MongoDB (Testcontainers, mongo:8 — the same
 * image the compose files run). No Spring context: the template is built
 * straight from the container's connection string, mirroring
 * PgPostRepositoryTest's shape. Docker is REQUIRED; no skips-green-here.
 *
 * Three proofs live here: (1) ACTIVATION creates the compound query index
 * AND the TTL index exactly as documented; (2) the $group pipelines answer
 * the SAME numbers the in-memory fake computes over the same seed — the
 * parity contract that keeps the HTTP surface mode-independent; (3) an
 * unreachable server classifies as the typed UNAVAILABLE failure (a
 * Failed Result from activate(), a typed AppException from query methods)
 * — never a crash, never an INTERNAL misfile.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoVisitStoreTest {

    /** The NEW 2.x container class (org.testcontainers.mongodb) — flagged rename, verified API.
     * Hard memory cap: mongo sizes its wiredTiger cache from AVAILABLE memory, so an uncapped
     * container on a shared 7GB CI runner targets ~3.5GB and the OOM killer reaps mongod
     * mid-suite (observed as startup failure / mid-test connection refused). */
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8")
            .withCreateContainerCmdModifier(cmd -> cmd.withMemory(768 * 1024 * 1024L)
                    .withMemorySwap(768 * 1024 * 1024L))
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    private static final String DATABASE = "guide";

    private static final InMemoryVisitStore FAKE = new InMemoryVisitStore();

    private static MongoClient client;
    private static MongoVisitStore store;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(MONGO.getConnectionString());
        store = new MongoVisitStore(new MongoTemplate(client, DATABASE));
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void activationPingsCreatesBothIndexesAndFlipsToSuccess() {
        Result<String> activated = store.activate();
        assertTrue(activated instanceof Success<String>, "activation against a live server");
        assertEquals(DATABASE, ((Success<String>) activated).value());

        List<Document> indexes = new MongoTemplate(client, DATABASE)
                .getCollection("guide_visits").listIndexes().into(new java.util.ArrayList<>());

        Document compound = indexes.stream()
                .filter(index -> "topic_recency_idx".equals(index.getString("name")))
                .findFirst().orElseThrow();
        assertEquals(new Document("topicId", 1).append("occurredAt", -1),
                compound.get("key", Document.class),
                "the query shape's index: topic equality first, recency second");

        Document ttl = indexes.stream()
                .filter(index -> "occurredAt_ttl_idx".equals(index.getString("name")))
                .findFirst().orElseThrow();
        assertEquals(MongoVisitStore.TTL_SECONDS,
                ttl.getInteger("expireAfterSeconds").longValue(),
                "30 days, then the background sweeper deletes the visit");

        // Idempotent: a second activation is a no-op that still succeeds.
        assertTrue(store.activate() instanceof Success<String>);
    }

    @Test
    @Order(2)
    void theSeedReplayedIntoMongoAnswersExactlyWhatTheFakeAnswers() {
        MongoTemplate template = new MongoTemplate(client, DATABASE);
        template.remove(new org.springframework.data.mongodb.core.query.Query(),
                VisitDoc.class);
        SeedVisits.list().forEach(visit -> template.insert(VisitDoc.from(visit)));

        assertEquals(FAKE.countAll(), store.countAll());
        assertEquals(FAKE.countByTopic("jdbc-sql"), store.countByTopic("jdbc-sql"));
        assertEquals(FAKE.topTopics(5), store.topTopics(5),
                "same order, same counts, same tie-break as the fake");
        assertEquals(FAKE.byLocaleHistogram(), store.byLocaleHistogram());
        assertEquals(FAKE.avgDurationMs(null), store.avgDurationMs(null),
                "the $avg pipeline rounds to the fake's fixed-point average");
        assertEquals(FAKE.avgDurationMs("aop"), store.avgDurationMs("aop"));
    }

    @Test
    @Order(3)
    void recordingMintsObjectIdsAndServesNewestFirstWithHashedVisitors() {
        VisitDocument first = store.record(new VisitDraft(
                "mongodb", "/topics/mongodb", "reader-ten", 120_000, "ja"));
        VisitDocument second = store.record(new VisitDraft(
                "mongodb", "/topics/mongodb", "reader-ten", 30_000, "ja"));

        assertEquals(24, first.id().length(), "ObjectId hex");
        assertEquals(first.visitorHash(), second.visitorHash(),
                "the same token hashes identically — returning traffic is visible");
        assertTrue(second.occurredAt().isAfter(first.occurredAt()));

        List<VisitDocument> recent = store.recent(3);
        assertEquals("mongodb", recent.getFirst().topicId());
        assertTrue(recent.getFirst().occurredAt().isAfter(recent.get(1).occurredAt()),
                "newest first");
    }

    @Test
    @Order(4)
    void theExplainArtifactRendersServerReadyBsonPipelines() {
        List<Document> pipelines = store.explainPipelines();
        assertEquals(3, pipelines.size());
        assertEquals("topTopics", pipelines.getFirst().getString("name"));
        List<Document> stages = pipelines.getFirst().getList("stages", Document.class);
        assertTrue(stages.getFirst().containsKey("$group"),
                "the first stage is the $group the section quotes");
        assertTrue(stages.stream().anyMatch(stage -> stage.containsKey("$sort")));
        assertTrue(stages.stream().anyMatch(stage -> stage.containsKey("$limit")));
    }

    @Test
    @Order(5)
    void anUnreachableServerClassifiesAsTypedUnavailableNeverACrash() {
        // A client pointed at a port nothing will ever answer, with the SAME
        // short timeouts the application.yml URI carries.
        MongoClient dead = MongoClients.create(
                "mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=300&connectTimeoutMS=300");
        try {
            MongoVisitStore unreachable =
                    new MongoVisitStore(new MongoTemplate(dead, DATABASE));

            Result<String> refused = unreachable.activate();
            assertTrue(refused instanceof Failed<String>,
                    "activation answers a Failed Result, never an exception");
            Failed<String> failed = (Failed<String>) refused;
            assertEquals(FailureKind.UNAVAILABLE, failed.failure().kind());
            assertEquals("mongo", failed.failure().details().get("dependency"));

            AppException typed = assertThrows(AppException.class, unreachable::countAll);
            assertEquals(FailureKind.UNAVAILABLE, typed.kind(),
                    "query methods throw the typed exception the advice renders");
            assertTrue(typed.failure().details().containsKey("dependency"));
        } finally {
            dead.close();
        }
    }

    @Test
    @Order(6)
    void emptyPopulationsAreZerosNotFailures() {
        // A separate database on the same container: activation creates its
        // collections and indexes, and every aggregate answers the honest 0.
        MongoVisitStore empty = new MongoVisitStore(new MongoTemplate(client, "guide_empty"));
        assertTrue(empty.activate() instanceof Success);
        assertEquals(0, empty.countAll());
        assertEquals(0, empty.avgDurationMs(null), "no group row → honest 0");
        assertEquals(List.of(), empty.topTopics(5));
        assertEquals(List.of(), empty.byLocaleHistogram());
    }
}
