package guide.presentation.demo;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * POST /api/demo/reset against the REAL application context, REAL
 * PostgreSQL (@ServiceConnection, postgres:17) and REAL Redis Streams
 * (redis:7-alpine via the plain-GenericContainer fallback the messaging
 * integration test established) — because the sweep's headline area,
 * messaging, resets state that lives in exactly those servers.
 *
 * What is asserted, not trusted:
 * - the happy path answers one row per registered area, sorted by id,
 *   every row reset=true (live PG + Redis are up here, so even messaging
 *   succeeds);
 * - the ?areas= filter resets ONLY the named areas — a saga started on
 *   purpose survives a jobs-only sweep, and the jobs board it emptied
 *   proves the positive half;
 * - an unknown area is the typed 400 envelope carrying the DERIVED known
 *   list, and it resets NOTHING (a pending async ticket survives it);
 * - the sweep ACTUALLY resets: messaging and jobs are driven into
 *   mutated state through their own demo endpoints first (a dead-lettered
 *   job with its sent mail, a finished async ticket, a committed outbox
 *   row, an un-acked stream delivery with its projection), then the sweep
 *   runs and every console reads back its constructed state.
 */
@Testcontainers
@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
class SharedDemoResetControllerTest {

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

    /**
     * Every test starts from the clean slate it asserts about —
     * order-independent. The msg area's schema migrates lazily at its
     * FIRST activation, so a cheap read pays that here: otherwise the
     * reset-all report's messaging row would honestly say reset=false
     * (no outbox table yet) in whichever test runs first.
     */
    @BeforeEach
    void resetAllAreas() throws Exception {
        mockMvc.perform(get("/api/demo/msg/stream/dlq")).andExpect(status().isOk());
        mockMvc.perform(post("/api/demo/reset")).andExpect(status().isOk());
    }

    // ---- the report ------------------------------------------------------------

    @Test
    void resetAllAnswersOneSortedRowPerRegisteredArea() throws Exception {
        mockMvc.perform(post("/api/demo/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset.length()").value(6))
                .andExpect(jsonPath("$.reset[0].area").value("jobs"))
                .andExpect(jsonPath("$.reset[0].reset").value(true))
                .andExpect(jsonPath("$.reset[1].area").value("messaging"))
                .andExpect(jsonPath("$.reset[1].reset").value(true))
                .andExpect(jsonPath("$.reset[2].area").value("patterns"))
                .andExpect(jsonPath("$.reset[2].reset").value(true))
                .andExpect(jsonPath("$.reset[3].area").value("reliability"))
                .andExpect(jsonPath("$.reset[3].reset").value(true))
                .andExpect(jsonPath("$.reset[4].area").value("scaling"))
                .andExpect(jsonPath("$.reset[4].reset").value(true))
                .andExpect(jsonPath("$.reset[5].area").value("thirdparty"))
                .andExpect(jsonPath("$.reset[5].reset").value(true));
    }

    // ---- the filter ------------------------------------------------------------

    @Test
    void aFilteredSweepTouchesOnlyTheNamedAreas() throws Exception {
        // mutate BOTH patterns and jobs, then ask for jobs only
        mockMvc.perform(post("/api/demo/arch/saga/start")
                        .queryParam("items", "2").queryParam("amountCents", "1999"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/demo/jobs/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delayMs\":10000}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/demo/jobs/async"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/demo/reset").queryParam("areas", "jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset.length()").value(1))
                .andExpect(jsonPath("$.reset[0].area").value("jobs"))
                .andExpect(jsonPath("$.reset[0].reset").value(true));

        // jobs was reset; patterns was NOT — the saga survives the sweep
        mockMvc.perform(get("/api/demo/jobs/async"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/demo/arch/saga/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sagas.length()").value(1));
    }

    @Test
    void anUnknownAreaIsTheValidationEnvelopeAndResetsNothing() throws Exception {
        // a live ticket proves the typo sweep reset NOTHING when it 400s
        mockMvc.perform(post("/api/demo/jobs/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delayMs\":10000}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/demo/reset").queryParam("areas", "messaging,nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value(containsString("unknown area")))
                .andExpect(jsonPath("$.error.message").value(containsString("nope")))
                .andExpect(jsonPath("$.error.message").value(containsString("known areas")))
                .andExpect(jsonPath("$.error.message").value(containsString("jobs")));

        mockMvc.perform(get("/api/demo/jobs/async"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ---- the proof: mutated state actually returns to the clean slate ------------

    @Test
    void messagingAndJobsReadBackCleanAfterTheSweep() throws Exception {
        mutateJobsConsole();
        mutateMessagingMachinery();

        // the sweep, filtered to the two mutated areas
        mockMvc.perform(post("/api/demo/reset").queryParam("areas", "messaging,jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset.length()").value(2))
                .andExpect(jsonPath("$.reset[0].area").value("jobs"))
                .andExpect(jsonPath("$.reset[0].reset").value(true))
                .andExpect(jsonPath("$.reset[1].area").value("messaging"))
                .andExpect(jsonPath("$.reset[1].reset").value(true));

        // the jobs console is its constructed self again
        mockMvc.perform(get("/api/demo/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depth").value(0))
                .andExpect(jsonPath("$.deadLetters").value(0));
        mockMvc.perform(get("/api/demo/jobs/emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/demo/jobs/async"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // the messaging machinery is empty on every surface
        mockMvc.perform(get("/api/demo/msg/outbox/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.pending").value(0))
                .andExpect(jsonPath("$.stats.delivered").value(0))
                .andExpect(jsonPath("$.stats.dead").value(0))
                .andExpect(jsonPath("$.recent.length()").value(0));
        mockMvc.perform(get("/api/demo/msg/stream/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/demo/msg/stream/dlq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
        mockMvc.perform(post("/api/demo/msg/stream/consume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries.length()").value(0))
                .andExpect(jsonPath("$.counters['demo.ping']").value(0));
    }

    // ---- mutation helpers --------------------------------------------------------

    /**
     * Jobs: one full retry episode to DEAD (failAttempts=3 burns the whole
     * budget, ~3s of backoff — polled to terminal so no in-flight claim can
     * race the sweep's clear) and one finished async ticket. The dead
     * episode sends NO mail (all three attempts fail), so the read-back's
     * empty email pane pins the queue's clear, and the cleared board pins
     * the @Async specimen's.
     */
    private void mutateJobsConsole() throws Exception {
        mockMvc.perform(post("/api/demo/jobs/enqueue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"send-welcome-email",
                                 "payload":{"to":"reader@example.com","name":"Reader"},
                                 "failAttempts":3}"""))
                .andExpect(status().isOk());

        // terminal BEFORE the sweep: dead (3 failures, budget 3) — polled, not slept
        assertTrueEventually(() -> {
            MvcResult result = mockMvc.perform(get("/api/demo/jobs")).andReturn();
            String body = result.getResponse().getContentAsString();
            return body.contains("\"deadLetters\":1") && body.contains("\"depth\":0");
        }, "the injected failure episode reaches the dead-letter queue");

        // a finished async ticket — the @Async contrast specimen's residue
        mockMvc.perform(post("/api/demo/jobs/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delayMs\":1}"))
                .andExpect(status().isOk());
        assertTrueEventually(() -> {
            MvcResult result = mockMvc.perform(get("/api/demo/jobs/async")).andReturn();
            return result.getResponse().getContentAsString().contains("\"state\":\"done\"");
        }, "the async ticket finishes");
    }

    /**
     * Messaging: a committed outbox transaction (waited until the relay has
     * DELIVERED it — claim → XADD → mark is one batch, so delivered=1 means
     * the stream side-effect already landed and cannot race the sweep's
     * DEL), the stream then drained so the stream assertions below see
     * exactly their own entry, and finally an un-acked delivery: a pending-
     * ledger row plus a non-zero consumer projection.
     */
    private void mutateMessagingMachinery() throws Exception {
        mockMvc.perform(post("/api/demo/msg/outbox/transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"" + SEED_POST + "\",\"publisher\":\"outbox\"}"))
                .andExpect(status().isOk());
        assertTrueEventually(() -> {
            MvcResult result = mockMvc.perform(get("/api/demo/msg/outbox/state")).andReturn();
            return result.getResponse().getContentAsString().contains("\"delivered\":1");
        }, "the relay delivers the committed outbox row (XADD side-effect included)");

        // drain the relayed entry so the stream proves exactly its own residue
        mockMvc.perform(post("/api/demo/msg/stream/consume")
                        .queryParam("max", "10").queryParam("ack", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries.length()").value(1))
                .andExpect(jsonPath("$.acked").value(1));

        mockMvc.perform(post("/api/demo/msg/stream/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"demo.ping\",\"payload\":{\"hello\":true}}"))
                .andExpect(status().isOk());
        // consume WITHOUT ack: the entry sits in the pending ledger, the
        // projection counts it, the DLQ endpoint still answers its empty self
        mockMvc.perform(post("/api/demo/msg/stream/consume").queryParam("ack", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries.length()").value(1))
                .andExpect(jsonPath("$.counters['demo.ping']").value(1));
        mockMvc.perform(get("/api/demo/msg/stream/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    /** Poll a condition until it holds or the 20s budget is spent — asserts never sleep. */
    private static void assertTrueEventually(Condition condition, String what)
            throws Exception {
        for (int attempt = 0; attempt < 80; attempt++) {
            if (condition.holds()) {
                return;
            }
            Thread.sleep(250);
        }
        org.junit.jupiter.api.Assertions.fail("timed out waiting for: " + what);
    }

    /** A pollable condition — may run MockMvc, so it may throw. */
    @FunctionalInterface
    interface Condition {

        boolean holds() throws Exception;
    }
}
