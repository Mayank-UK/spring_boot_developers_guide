package guide.data.caching;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The caching demos against the REAL application context and REAL Redis —
 * redis:7-alpine via the plain GenericContainer fallback topic 20
 * established (Testcontainers ships no first-class Redis module; the core
 * jar arrives transitively). What is asserted at the server boundary, not
 * trusted: BOTH caching paths round-trip with live mode on the response,
 * their entries live under the guide:cache: namespace the adapter owns
 * (explicit: stats:rest, annotation: topicStats:rest — sibling keys,
 * never colliding), the 60 s TTL is exact arithmetic on the server
 * (59–60 band, determinism without sleep-races), invalidation drops the
 * keys on the REAL server and the next read recomputes, and negative
 * caching holds on the live backend too.
 */
@Testcontainers
@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
class CachingDemoIntegrationTest {

    private static final String EXPLICIT = "/api/demo/caching/stats/rest";
    private static final String ANNOTATION = "/api/demo/caching/stats/rest?mode=annotation";

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
    private StringRedisTemplate template;

    @Autowired
    private ScenarioStore scenarioStore;

    @BeforeEach
    void pinAreaLive() {
        scenarioStore.setDataMode(ScenarioRoutingCacheBackend.AREA, ScenarioDataMode.LIVE);
    }

    /** Every test starts from an empty server and the default scenario state. */
    @AfterEach
    void flushAndUnpin() {
        try (RedisConnection connection = template.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
        scenarioStore.reset();
    }

    @Test
    void explicitPathRoundTripsOnLiveRedisUnderTheCacheNamespace() throws Exception {
        mockMvc.perform(get(EXPLICIT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cache").value("MISS"))
                .andExpect(jsonPath("$.backendMode").value("live"))
                .andExpect(jsonPath("$.key").value("stats:rest"));
        mockMvc.perform(get(EXPLICIT))
                .andExpect(jsonPath("$.cache").value("HIT"));

        assertTrue(Boolean.TRUE.equals(template.hasKey("guide:cache:stats:rest")),
                "the explicit path's entry lives under the adapter's namespace");
        Long ttl = template.getExpire("guide:cache:stats:rest");
        assertTrue(ttl != null && ttl >= 59 && ttl <= 60,
                "the 60 s TTL is exact arithmetic right after the put: " + ttl);
    }

    @Test
    void annotationPathCachesUnderItsOwnLiveKey() throws Exception {
        mockMvc.perform(get(ANNOTATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cache").value("MISS"))
                .andExpect(jsonPath("$.key").value("topicStats:rest"))
                .andExpect(jsonPath("$.backendMode").value("live"));
        mockMvc.perform(get(ANNOTATION))
                .andExpect(jsonPath("$.cache").value("HIT"));

        assertTrue(Boolean.TRUE.equals(template.hasKey("guide:cache:topicStats:rest")),
                "@Cacheable's entry is on the real server, through the port-backed manager");
    }

    @Test
    void invalidationDropsBothKeysOnTheRealServer() throws Exception {
        mockMvc.perform(get(EXPLICIT)).andExpect(jsonPath("$.cache").value("MISS"));
        mockMvc.perform(get(ANNOTATION)).andExpect(jsonPath("$.cache").value("MISS"));

        mockMvc.perform(post("/api/demo/caching/invalidate/rest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evictedKeys[0]").value("stats:rest"))
                .andExpect(jsonPath("$.evictedKeys[1]").value("topicStats:rest"));

        assertFalse(Boolean.TRUE.equals(template.hasKey("guide:cache:stats:rest")));
        assertFalse(Boolean.TRUE.equals(template.hasKey("guide:cache:topicStats:rest")));
        mockMvc.perform(get(EXPLICIT)).andExpect(jsonPath("$.cache").value("MISS"));
    }

    @Test
    void negativeCachingHoldsOnTheLiveBackend() throws Exception {
        mockMvc.perform(get("/api/demo/caching/stats/ghost-tag"))
                .andExpect(jsonPath("$.cache").value("MISS"))
                .andExpect(jsonPath("$.result.postCount").value(0));
        mockMvc.perform(get("/api/demo/caching/stats/ghost-tag"))
                .andExpect(jsonPath("$.cache").value("HIT"))
                .andExpect(jsonPath("$.result.postCount").value(0));
    }
}
