package guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import guide.data.posts.InMemoryPostRepository;
import guide.domain.posts.PostRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * THE DEGRADE-GRACEFULLY PROOF: the whole application context boots, and the
 * content + posts surfaces WORK, with PostgreSQL unreachable — the datasource
 * URL below points at a port nothing will ever answer, and boot never
 * notices, because every data-side choice defers the connection:
 *
 * - Flyway autoconfiguration is OFF (spring.flyway.enabled=false): migrations
 *   run programmatically at live activation, so a schema check cannot gate
 *   startup;
 * - Hikari starts its pool lazily, on the first getConnection() — bean
 *   creation connects nothing (asserted below, directly on the pool);
 * - the posts port's exposed bean is the router, whose default mode is the
 *   in-memory fake — PostgreSQL is first needed the moment a live flip asks
 *   for it, never one instant earlier.
 *
 * (grpc moves to an ephemeral port: this context intentionally differs from
 * the suite's cached one, whose Netty server already holds 9090.)
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/guide",
        "spring.grpc.server.port=0"})
@AutoConfigureMockMvc
class AppBootsWithoutPostgresTest {

    private static final String NEWEST_SEED = "9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private InMemoryPostRepository inMemoryPostRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void bootingWithPostgresDownConnectsNothing() {
        assertTrue(dataSource instanceof HikariDataSource,
                "the autoconfigured DataSource is the Hikari pool from application.yml");
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertNull(hikari.getHikariPoolMXBean(),
                "the pool starts on first getConnection — boot connected nothing");
        assertEquals("jdbc:postgresql://127.0.0.1:1/guide", hikari.getJdbcUrl(),
                "the unreachable URL is really the one configured");
    }

    @Test
    void contentApiServesTopicsWithPostgresUnreachable() throws Exception {
        // The topic count is SHARED state: every new topic file + ContentTopics
        // line moves it, so the expectation tracks the registry itself rather
        // than pinning a number that goes stale each phase.
        int expectedTopics = guide.content.ContentTopics.all().size();
        mockMvc.perform(get("/api/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.groups").isArray())
                .andExpect(jsonPath("$.topics").isArray())
                .andExpect(jsonPath("$.topics.length()").value(expectedTopics))
                .andExpect(jsonPath("$.groups.length()").value(10));
    }

    @Test
    void postsSurfaceServesTheFakeSeedWithPostgresUnreachable() throws Exception {
        mockMvc.perform(get("/api/demo/api/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].id").value(NEWEST_SEED));

        // The router's default route is THE fake bean — the exact instance the
        // seed reset uses: no PostgreSQL path was involved in the answer.
        assertTrue(postRepository instanceof guide.data.posts.ScenarioRoutingPostRepository,
                "the exposed port bean is the router");
        guide.data.posts.ScenarioRoutingPostRepository router =
                (guide.data.posts.ScenarioRoutingPostRepository) postRepository;
        assertEquals(inMemoryPostRepository, router.fakeAdapter(),
                "fake mode is served by the in-memory adapter bean itself");
    }
}
