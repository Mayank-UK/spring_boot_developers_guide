package guide.data.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.domain.messaging.EventCounters;
import guide.domain.messaging.OutboxStore;
import guide.domain.messaging.TeachingEventPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The messaging demos against the REAL application context, REAL
 * PostgreSQL (@ServiceConnection, postgres:17) and REAL Redis Streams —
 * redis:7-alpine via the plain-GenericContainer fallback topic 20
 * established (Testcontainers ships no first-class Redis module).
 *
 * What is asserted, not trusted — the topic's claims as executables:
 * - The XADD → XREADGROUP → XACK round trip crosses the real group, and
 *   every key the server saw stays under guide:demo: (namespace discipline).
 * - The consumer is idempotent: the SAME entry delivered twice increments
 *   the projection ONCE (replayPending is the staged redelivery).
 * - The crash window: an un-acked delivery sits in XPENDING with its
 *   consumer, idle time and delivery count; XCLAIM (min-idle 0, no sleeps)
 *   takes it over, reprocesses it as a DUPLICATE, and acks it out.
 * - The poison story: an entry delivered past the threshold dead-letters —
 *   DLQ stream entry + XACK, deterministically (delivery counts, not
 *   sleeps).
 * - The outbox proofs, BOTH directions: a committed transaction's event is
 *   delivered by the relay even after a staged AFTER-COMMIT crash; a
 *   rolled-back transaction leaves NO event anywhere. And the direct
 *   publisher in the same crash window LOSES the event — the dual-write
 *   demonstration, live.
 * - The relay under concurrency: eight simultaneous batches over four rows
 *   publish each row EXACTLY once (single-flight + SKIP LOCKED), and poison
 *   rows (attempts past the threshold) dead-letter instead of publishing.
 */
@Testcontainers
@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
class MessagingDemoIntegrationTest {

    /** A posts-seed id the transactional demo can publish (V1 seed). */
    private static final String SEED_POST = "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    /** The honest fallback (verified: no Testcontainers Redis module exists). */
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate template;

    @Autowired
    private ScenarioStore scenarioStore;

    @Autowired
    private MsgDemoActivation activation;

    @Autowired
    private RedisStreamMessaging stream;

    @Autowired
    private OutboxStore outboxStore;

    @Autowired
    private EventCounters counters;

    @Autowired
    private ObjectMapper objectMapper;

    /** Pay the activation bill up front, then hold the ticker still: every
     * test asserts counts the background poller must not perturb (the area
     * flip is exactly what the demo UI does to pause the drain). */
    @BeforeEach
    void readyAndQuiet() {
        activation.ensureReady();
        scenarioStore.setDataMode(TeachingEventPublisher.AREA, ScenarioDataMode.FAKE);
        counters.reset();
        jdbc.update("DELETE FROM outbox");
        try (RedisConnection connection = template.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
        // flushAll removed the stream AND its group — recreate idempotently.
        stream.ensureGroup();
    }

    @AfterEach
    void resetScenario() {
        scenarioStore.reset();
    }

    // ---- ground truth helpers (no adapter in between) ----

    private List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>>
            mainStreamEntries() {
        return streamRows("guide:demo:stream:events");
    }

    private List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>>
            dlqStreamEntries() {
        return streamRows("guide:demo:stream:dead");
    }

    private List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>>
            streamRows(String key) {
        var records = template.opsForStream().range(key, Range.unbounded());
        return records == null ? List.of() : records;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult publish(String eventType) throws Exception {
        return mockMvc.perform(post("/api/demo/msg/stream/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"" + eventType + "\",\"payload\":{\"note\":\"hi\"}}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    // ---- demo msg:stream ----

    @Test
    void xaddReadGroupXackRoundTripsOnTheLiveStream() throws Exception {
        MvcResult published = publish("demo.ping");
        JsonNode view = body(published);
        assertTrue(view.path("streamId").asText().matches("\\d+-\\d+"),
                "XADD answers a millis-sequence id: " + view.path("streamId"));
        assertEquals("guide:demo:stream:events", view.path("stream").asText());
        String eventId = view.path("eventId").asText();

        var entries = mainStreamEntries();
        assertEquals(1, entries.size(), "the event is really in the stream");
        assertEquals(eventId, entries.getFirst().getValue().get("eventId"));

        mockMvc.perform(post("/api/demo/msg/stream/consume?max=10&ack=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries.length()").value(1))
                .andExpect(jsonPath("$.deliveries[0].duplicate").value(false))
                .andExpect(jsonPath("$.acked").value(1))
                .andExpect(jsonPath("$.counters['demo.ping']").value(1));

        mockMvc.perform(get("/api/demo/msg/stream/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void namespaceDisciplineHoldsEveryKeyUnderTheDemoPrefix() throws Exception {
        publish("demo.ping");
        var keys = template.keys("guide:demo:*");
        assertTrue(keys != null && keys.stream().allMatch(k -> k.startsWith("guide:demo:")),
                "no bare key ever reached the server: " + keys);
        assertTrue(keys.contains("guide:demo:stream:events"),
                "the stream key is namespaced: " + keys);
    }

    @Test
    void consumerIsIdempotentTheSameDeliveryTwiceCountsOnce() throws Exception {
        publish("demo.ping");
        // Stage the crash window: delivered to worker-1, never acked.
        mockMvc.perform(post("/api/demo/msg/stream/consume?max=10&ack=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries[0].duplicate").value(false))
                .andExpect(jsonPath("$.counters['demo.ping']").value(1));
        // The restart read (offset "0"): the SAME entry again.
        mockMvc.perform(post("/api/demo/msg/stream/consume?max=10&ack=false&replayPending=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries.length()").value(1))
                .andExpect(jsonPath("$.deliveries[0].duplicate").value(true))
                .andExpect(jsonPath("$.counters['demo.ping']").value(1))
                .andExpect(jsonPath("$.duplicatesSeen").value(1));
        // Clean up the window.
        mockMvc.perform(post("/api/demo/msg/stream/consume?max=10&ack=true&replayPending=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acked").value(1));
    }

    @Test
    void pendingEntriesAndClaimRoundTripWithoutSleeps() throws Exception {
        publish("demo.ping");
        mockMvc.perform(post("/api/demo/msg/stream/consume?max=10&ack=false"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/demo/msg/stream/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.perConsumer['worker-1']").value(1))
                .andExpect(jsonPath("$.entries[0].consumer").value("worker-1"))
                .andExpect(jsonPath("$.entries[0].deliveryCount").value(1))
                .andExpect(jsonPath("$.entries[0].idleMs").isNumber());

        // The takeover: min-idle 0 claims immediately (deterministic, no
        // visibility-timeout wait) — worker-2 reprocesses and acks.
        mockMvc.perform(post("/api/demo/msg/stream/claim?minIdleMs=0&ack=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimedBy").value("worker-2"))
                .andExpect(jsonPath("$.reprocessed.length()").value(1))
                .andExpect(jsonPath("$.reprocessed[0].duplicate").value(true))
                .andExpect(jsonPath("$.deadLettered.length()").value(0))
                .andExpect(jsonPath("$.acked").value(1));

        mockMvc.perform(get("/api/demo/msg/stream/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void poisonDeliveriesDeadLetterToTheDlqDeterministically() throws Exception {
        String eventId = body(publish("demo.ping")).path("eventId").asText();
        // Delivery 1: read, no ack (the crash window).
        mockMvc.perform(post("/api/demo/msg/stream/consume?max=10&ack=false"))
                .andExpect(status().isOk());
        // Deliveries 2 and 3: claimed, processed, still not acked — the
        // worker keeps dying on THIS entry (min-idle 0: no sleeps).
        mockMvc.perform(post("/api/demo/msg/stream/claim?minIdleMs=0&ack=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reprocessed.length()").value(1))
                .andExpect(jsonPath("$.deadLettered.length()").value(0));
        mockMvc.perform(post("/api/demo/msg/stream/claim?minIdleMs=0&ack=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadLettered.length()").value(0));
        // Delivery 4: past the threshold — poison. Dead-lettered and acked.
        mockMvc.perform(post("/api/demo/msg/stream/claim?minIdleMs=0&ack=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reprocessed.length()").value(0))
                .andExpect(jsonPath("$.deadLettered.length()").value(1))
                .andExpect(jsonPath("$.acked").value(1));

        mockMvc.perform(get("/api/demo/msg/stream/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/demo/msg/stream/dlq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].eventId").value(eventId))
                .andExpect(jsonPath("$.entries[0].reason").value(
                        org.hamcrest.Matchers.containsString("delivered 4 times")));
        assertEquals(1, dlqStreamEntries().size(), "the DLQ entry is really in the dead stream");
    }

    // ---- demo msg:outbox — the proofs ----

    @Test
    void committedTransactionDeliversItsEventEvenAfterTheAfterCommitCrash() throws Exception {
        mockMvc.perform(post("/api/demo/msg/outbox/transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + SEED_POST
                                + "\",\"publisher\":\"outbox\",\"failAfterPublish\":true}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"));

        // DB truth: the mutation COMMITTED (the crash was after), and the
        // outbox row committed WITH it — that is the entire pattern.
        assertEquals("published",
                jdbc.queryForObject("SELECT status FROM posts WHERE id = ?",
                        String.class, java.util.UUID.fromString(SEED_POST)));
        mockMvc.perform(get("/api/demo/msg/outbox/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.pending").value(1))
                .andExpect(jsonPath("$.recent[0].attempts").value(0));

        // The relay delivers what the crash interrupted.
        mockMvc.perform(post("/api/demo/msg/outbox/relay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(1))
                .andExpect(jsonPath("$.published").value(1))
                .andExpect(jsonPath("$.skippedInFlight").value(false))
                .andExpect(jsonPath("$.streamIds.length()").value(1));

        mockMvc.perform(get("/api/demo/msg/outbox/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.pending").value(0))
                .andExpect(jsonPath("$.stats.delivered").value(1));
        assertEquals(1, mainStreamEntries().size(), "the event really reached the stream");
        assertEquals("post.published",
                mainStreamEntries().getFirst().getValue().get("eventType"));
    }

    @Test
    void rolledBackTransactionLeavesNoEventAnywhere() throws Exception {
        String statusBefore = jdbc.queryForObject("SELECT status FROM posts WHERE id = ?",
                String.class, java.util.UUID.fromString(SEED_POST));
        int streamBefore = mainStreamEntries().size();

        mockMvc.perform(post("/api/demo/msg/outbox/transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + SEED_POST
                                + "\",\"publisher\":\"outbox\",\"failBeforeCommit\":true}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"));

        assertEquals(statusBefore, jdbc.queryForObject("SELECT status FROM posts WHERE id = ?",
                        String.class, java.util.UUID.fromString(SEED_POST)),
                "the mutation rolled back with the event append");
        mockMvc.perform(get("/api/demo/msg/outbox/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.pending").value(0));
        mockMvc.perform(post("/api/demo/msg/outbox/relay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(0))
                .andExpect(jsonPath("$.published").value(0));
        assertEquals(streamBefore, mainStreamEntries().size(), "nothing reached the stream");
    }

    @Test
    void directPublisherLosesTheEventInTheDualWriteWindow() throws Exception {
        int streamBefore = mainStreamEntries().size();
        mockMvc.perform(post("/api/demo/msg/outbox/transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + SEED_POST
                                + "\",\"publisher\":\"direct\",\"failAfterPublish\":true}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"));

        // The mutation committed; the event is nowhere — stream, outbox, nowhere.
        assertEquals("published",
                jdbc.queryForObject("SELECT status FROM posts WHERE id = ?",
                        String.class, java.util.UUID.fromString(SEED_POST)));
        assertEquals(streamBefore, mainStreamEntries().size(), "THE LOST EVENT: never published");
        mockMvc.perform(get("/api/demo/msg/outbox/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.pending").value(0));
    }

    @Test
    void happyPathDirectPublishesAndOutboxQueuesAnswerHonestly() throws Exception {
        mockMvc.perform(post("/api/demo/msg/outbox/transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + SEED_POST + "\",\"publisher\":\"direct\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publisher").value("direct"))
                .andExpect(jsonPath("$.streamId").isNotEmpty())
                .andExpect(jsonPath("$.note").value(
                        org.hamcrest.Matchers.containsString("two systems")));
        mockMvc.perform(post("/api/demo/msg/outbox/transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + SEED_POST + "\",\"publisher\":\"outbox\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publisher").value("outbox"))
                .andExpect(jsonPath("$.streamId").doesNotExist())
                .andExpect(jsonPath("$.note").value(
                        org.hamcrest.Matchers.containsString("one transaction")));
    }

    @Test
    void concurrentRelaysPublishEachRowExactlyOnce() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/demo/msg/outbox/transactional")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"postId\":\"" + SEED_POST + "\",\"publisher\":\"outbox\"}"))
                    .andExpect(status().isOk());
        }
        AtomicInteger publishedTotal = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    MvcResult result = mockMvc.perform(post("/api/demo/msg/outbox/relay"))
                            .andExpect(status().isOk()).andReturn();
                    return body(result).path("published").asInt();
                }));
            }
            for (Future<Integer> future : futures) {
                publishedTotal.addAndGet(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(4, publishedTotal.get(), "every row published, none twice (single-flight)");
        var eventIds = mainStreamEntries().stream()
                .map(row -> String.valueOf(row.getValue().get("eventId")))
                .toList();
        assertEquals(4, eventIds.size(), "four events in the stream");
        assertEquals(4, eventIds.stream().distinct().count(), "four DISTINCT ids — no double delivery");
        mockMvc.perform(get("/api/demo/msg/outbox/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.pending").value(0))
                .andExpect(jsonPath("$.stats.delivered").value(4));
    }

    @Test
    void relayDeadLettersPoisonRowsPastTheAttemptThreshold() throws Exception {
        String eventId = mockMvc.perform(post("/api/demo/msg/outbox/transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + SEED_POST + "\",\"publisher\":\"outbox\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(eventId).path("eventId").asText();

        // Three claims that never complete — the relay that keeps crashing
        // between claim and publish (attempts 1, 2, 3; still unprocessed).
        for (int i = 0; i < 3; i++) {
            outboxStore.claimPending(16);
        }
        mockMvc.perform(post("/api/demo/msg/outbox/relay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(1))
                .andExpect(jsonPath("$.published").value(0))
                .andExpect(jsonPath("$.deadLettered").value(1));
        mockMvc.perform(get("/api/demo/msg/outbox/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.pending").value(0))
                .andExpect(jsonPath("$.stats.dead").value(1));
        mockMvc.perform(get("/api/demo/msg/stream/dlq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].eventId").value(id))
                .andExpect(jsonPath("$.entries[0].reason").value(
                        org.hamcrest.Matchers.containsString("4 times")));
    }

    // ---- edges: validation, unknown post, failure injection ----

    @Test
    void hostileInputAnswersTypedValidationEnvelopes() throws Exception {
        mockMvc.perform(post("/api/demo/msg/stream/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
        mockMvc.perform(post("/api/demo/msg/outbox/transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"not-a-uuid\",\"publisher\":\"outbox\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
        mockMvc.perform(post("/api/demo/msg/outbox/transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + SEED_POST + "\",\"publisher\":\"bicycle\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
        mockMvc.perform(post("/api/demo/msg/outbox/transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"00000000-0000-0000-0000-000000000000\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void injectFailureGuardsEveryEndpointWithTheTypedUnavailableEnvelope() throws Exception {
        mockMvc.perform(patch("/api/demo/scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"injectFailure\":true}"))
                .andExpect(status().isOk());
        try {
            mockMvc.perform(post("/api/demo/msg/stream/publish")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventType\":\"demo.ping\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("service_unavailable"));
            mockMvc.perform(get("/api/demo/msg/outbox/state"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("service_unavailable"));
        } finally {
            mockMvc.perform(patch("/api/demo/scenario")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"injectFailure\":false}"))
                    .andExpect(status().isOk());
        }
    }
}
