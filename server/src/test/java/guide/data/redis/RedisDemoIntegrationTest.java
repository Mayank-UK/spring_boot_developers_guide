package guide.data.redis;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.domain.redis.CacheBackend;
import guide.domain.redis.RedisPlayground;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The redis demos against the REAL application context and REAL Redis —
 * redis:7-alpine, the compose image. FLAGGED HONEST FALLBACK (verified on
 * Central 2026-10-06): Testcontainers ships no first-class Redis module in
 * either its 1.x or renamed 2.x line, so this is a plain GenericContainer
 * on exposed port 6379 wired through @DynamicPropertySource — no extra
 * coordinates (the core testcontainers jar arrives transitively via
 * testcontainers-junit-jupiter).
 *
 * What is asserted, not trusted: every op family round-trips through the
 * same HTTP surface the console uses with mode "live"; EVERY key the
 * server ever saw starts guide:demo: (the namespace owned inside the
 * adapter, proven at the server boundary, not in our own types); TTL is
 * exact arithmetic immediately after SET EX / EXPIRE (59–60 and 44–45
 * bands — determinism without sleep-races); pub/sub really crosses the
 * broker (publish via convertAndSend, delivery via the subscription
 * container into the ring); and the CacheBackend seam topic 21 consumes
 * round-trips under its own guide:cache: namespace.
 */
@Testcontainers
@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
class RedisDemoIntegrationTest {

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

    @Autowired
    private CacheBackend cacheBackend;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void pinAreaLive() {
        scenarioStore.setDataMode(RedisPlayground.AREA, ScenarioDataMode.LIVE);
    }

    /** Every test starts from an empty server and the default scenario state. */
    @AfterEach
    void flushAndUnpin() {
        try (RedisConnection connection = template.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
        scenarioStore.reset();
    }

    private MvcResult op(String body) throws Exception {
        return mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** Parses op responses with the app's own autoconfigured Jackson 3 mapper. */
    private JsonNode resultOf(MvcResult mvcResult) throws Exception {
        String json = mvcResult.getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    @Test
    void opsRoundTripOnTheLiveServerWithNamespacedKeysAndModeLive() throws Exception {
        MvcResult set = op("{\"op\":\"set\",\"key\":\"greeting\",\"value\":\"hello live\"}");
        JsonNode view = resultOf(set);
        assertTrue(view.path("ok").asBoolean());
        assertTrue(view.path("namespacedKey").asText().equals("guide:demo:greeting"),
                "the server stores under the adapter's namespace: " + view.path("namespacedKey"));
        assertTrue(view.path("mode").asText().equals("live"));

        MvcResult got = op("{\"op\":\"get\",\"key\":\"greeting\"}");
        assertTrue(resultOf(got).path("result").asText().equals("hello live"));

        // ground truth straight from the server, no adapter in between
        assertTrue(Boolean.TRUE.equals(template.hasKey("guide:demo:greeting")),
                "the namespaced key exists on the real server");
        op("{\"op\":\"del\",\"key\":\"greeting\"}");
        assertTrue(Boolean.FALSE.equals(template.hasKey("guide:demo:greeting")));
    }

    @Test
    void everyKeyTheServerEverSawCarriesTheNamespace() throws Exception {
        op("{\"op\":\"set\",\"key\":\"plain\",\"value\":\"v\"}");
        op("{\"op\":\"hset\",\"key\":\"profile\",\"field\":\"name\",\"value\":\"Ada\"}");
        op("{\"op\":\"lpush\",\"key\":\"feed\",\"value\":\"a\"}");
        op("{\"op\":\"sadd\",\"key\":\"tags\",\"value\":\"spring\"}");
        op("{\"op\":\"zadd\",\"key\":\"league\",\"value\":\"ada\",\"score\":10}");

        Set<String> everything = template.keys("*");
        assertTrue(everything != null && everything.size() >= 5,
                "the ops created keys: " + everything);
        assertTrue(everything.stream().allMatch(key -> key.startsWith("guide:demo:")),
                "no bare key ever reached the server: " + everything);
    }

    @Test
    void ttlIsExactArithmeticAfterSetExAndExpire() throws Exception {
        op("{\"op\":\"set\",\"key\":\"session\",\"value\":\"blob\",\"ttlSeconds\":60}");
        long remaining = resultOf(op("{\"op\":\"ttl\",\"key\":\"session\"}"))
                .path("result").path("secondsRemaining").asLong();
        assertTrue(remaining >= 59 && remaining <= 60,
                "immediately after SET EX 60, TTL is the armed seconds (ceil): " + remaining);

        op("{\"op\":\"set\",\"key\":\"plain\",\"value\":\"v\"}");
        JsonNode insight = resultOf(op("{\"op\":\"ttl\",\"key\":\"plain\"}")).path("result");
        assertTrue(insight.path("exists").asBoolean());
        assertTrue(!insight.path("hasExpiry").asBoolean(),
                "a plain SET carries no expiry — the -1 answer, typed");

        op("{\"op\":\"expire\",\"key\":\"plain\",\"ttlSeconds\":45}");
        long rearmed = resultOf(op("{\"op\":\"ttl\",\"key\":\"plain\"}"))
                .path("result").path("secondsRemaining").asLong();
        assertTrue(rearmed >= 44 && rearmed <= 45,
                "immediately after EXPIRE 45, TTL is the armed seconds: " + rearmed);
    }

    @Test
    void structureSemanticsHoldOnTheRealServer() throws Exception {
        op("{\"op\":\"hset\",\"key\":\"profile\",\"field\":\"name\",\"value\":\"Ada\"}");
        op("{\"op\":\"hset\",\"key\":\"profile\",\"field\":\"lang\",\"value\":\"java\"}");
        JsonNode hash = resultOf(op("{\"op\":\"hgetall\",\"key\":\"profile\"}")).path("result");
        assertTrue(hash.path("name").asText().equals("Ada") && hash.path("lang").asText().equals("java"));

        op("{\"op\":\"lpush\",\"key\":\"feed\",\"value\":\"first\"}");
        op("{\"op\":\"lpush\",\"key\":\"feed\",\"value\":\"second\"}");
        JsonNode feed = resultOf(op("{\"op\":\"lrange\",\"key\":\"feed\",\"start\":0,\"stop\":-1}")).path("result");
        assertTrue(feed.isArray() && feed.size() == 2 && feed.get(0).asText().equals("second"),
                "LPUSH prepends: 0..-1 reads newest first");

        op("{\"op\":\"sadd\",\"key\":\"tags\",\"value\":\"spring\"}");
        op("{\"op\":\"sadd\",\"key\":\"tags\",\"value\":\"spring\"}");
        JsonNode members = resultOf(op("{\"op\":\"smembers\",\"key\":\"tags\"}")).path("result");
        assertTrue(members.isArray() && members.size() == 1, "SADD is idempotent: " + members);

        op("{\"op\":\"zadd\",\"key\":\"league\",\"value\":\"ada\",\"score\":10}");
        op("{\"op\":\"zadd\",\"key\":\"league\",\"value\":\"grace\",\"score\":12}");
        JsonNode board = resultOf(op("{\"op\":\"zrange\",\"key\":\"league\",\"start\":0,\"stop\":-1}")).path("result");
        assertTrue(board.size() == 2 && board.get(0).path("member").asText().equals("ada"),
                "ZRANGE walks score order with scores: " + board);
    }

    @Test
    void publishCrossesTheBrokerIntoTheSubscriptionRing() throws Exception {
        JsonNode published = resultOf(
                op("{\"op\":\"publish\",\"key\":\"chan:playground\",\"value\":\"hello live round trip\"}"));
        assertTrue(published.path("result").path("channel").asText()
                .equals("guide:demo:chan:playground"));

        // delivery is asynchronous: the listener container pushes into the ring.
        // Bounded poll (no awaitility dependency): subscription handshake takes
        // a beat on the first live call, then the assertion is exact.
        AssertionError lastFailure = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                mockMvc.perform(get("/api/demo/redis/recent"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.mode").value("live"))
                        .andExpect(jsonPath("$.messages[0].channel")
                                .value("guide:demo:chan:playground"))
                        .andExpect(jsonPath("$.messages[0].message")
                                .value("hello live round trip"));
                return;
            } catch (AssertionError failure) {
                lastFailure = failure;
                Thread.sleep(50);
            }
        }
        throw lastFailure != null ? lastFailure
                : new AssertionError("pub/sub delivery never reached the ring");
    }

    @Test
    void cacheBackendSeamRoundTripsUnderItsOwnNamespace() {
        cacheBackend.put("posts:listing:1", "{\"page\":1}", Duration.ofSeconds(60));
        assertTrue(cacheBackend.get("posts:listing:1").isPresent());
        assertTrue(cacheBackend.get("posts:listing:1").orElseThrow().contains("page"));

        Set<String> cacheKeys = template.keys("guide:cache:*");
        assertTrue(cacheKeys != null && cacheKeys.contains("guide:cache:posts:listing:1"),
                "the cache seam keeps its own namespace: " + cacheKeys);

        cacheBackend.evict("posts:listing:1");
        assertTrue(cacheBackend.get("posts:listing:1").isEmpty());
        assertTrue(Boolean.FALSE.equals(template.hasKey("guide:cache:posts:listing:1")));
    }

    @Test
    void unknownOpsRemainValidationEnvelopesInLiveModeToo() throws Exception {
        mockMvc.perform(post("/api/demo/redis/op").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"op\":\"keys\",\"key\":\"*\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }
}
