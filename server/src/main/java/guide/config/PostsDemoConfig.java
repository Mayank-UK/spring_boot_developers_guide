package guide.config;

import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.data.posts.InMemoryPostRepository;
import guide.data.posts.PgPostRepository;
import guide.data.posts.ScenarioRoutingPostRepository;
import guide.data.sql.SchemaMigrator;
import guide.domain.posts.CreatePost;
import guide.domain.posts.DeletePost;
import guide.domain.posts.GetPost;
import guide.domain.posts.ListPosts;
import guide.domain.posts.PostRepository;
import guide.domain.posts.UpdatePost;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Topic 11 (api-design) wiring — the posts substrate's composition root:
 * ONE port bean, five use case beans, every wire explicit. Phase 6 made the
 * port bean a ROUTER: the exposed PostRepository answers from the in-memory
 * fake or the PostgreSQL adapter based on the ScenarioStore's 'api' area AT
 * CALL TIME (PATCH /api/demo/scenario flips it live at runtime; no restart).
 *
 * The port-adapter payoff, paid in full: both adapters implement the same
 * five methods, the use cases above them were written against the fake and
 * now serve PostgreSQL without one changed line, and nothing above
 * guide.domain.posts (controllers, GraphQL, OpenAPI) noticed the storage
 * swap. THAT is what "the port is the contract" buys.
 *
 * The SQL-side beans are @Lazy and reached through ObjectProviders: contexts
 * without a DataSource (the @WebMvcTest slice) never construct them, and
 * the full app pays for them only when a live call actually activates the
 * adapter — migrations, seeding, and all.
 */
@Configuration
public class PostsDemoConfig {

    private final ObjectProvider<SchemaMigrator> schemaMigrator;
    private final ObjectProvider<PgPostRepository> liveAdapter;

    public PostsDemoConfig(ObjectProvider<SchemaMigrator> schemaMigrator,
            ObjectProvider<PgPostRepository> liveAdapter) {
        this.schemaMigrator = schemaMigrator;
        this.liveAdapter = liveAdapter;
    }

    /** The exposed port: the router (fake by default, live on scenario flip). @Primary wins every by-type injection. */
    @Primary
    @Bean
    PostRepository postRepository(ScenarioStore scenarioStore) {
        // Marks this area in the store's per-mode registry: fake data by
        // default, live at call time once ScenarioStore flips it (dataModeFor("api")).
        scenarioStore.setDataMode(PostRepository.AREA, ScenarioDataMode.FAKE);
        return new ScenarioRoutingPostRepository(scenarioStore, inMemoryPostRepository(),
                schemaMigrator::getObject, liveAdapter::getObject);
    }

    /** The fake adapter of record — deterministic seed, and the handle tests reset through. */
    @Bean
    InMemoryPostRepository inMemoryPostRepository() {
        return new InMemoryPostRepository();
    }

    /** The migrations seam, @Lazy: built on first live activation, never at boot. */
    @Lazy
    @Bean
    SchemaMigrator schemaMigrator(DataSource dataSource) {
        return new SchemaMigrator(dataSource);
    }

    /** The live adapter, @Lazy for the same reason: no DataSource touched until a live call. */
    @Lazy
    @Bean
    PgPostRepository pgPostRepository(JdbcTemplate jdbcTemplate) {
        return new PgPostRepository(jdbcTemplate);
    }

    @Bean
    ListPosts listPosts(PostRepository postRepository) {
        return new ListPosts(postRepository);
    }

    @Bean
    GetPost getPost(PostRepository postRepository) {
        return new GetPost(postRepository);
    }

    @Bean
    CreatePost createPost(PostRepository postRepository) {
        return new CreatePost(postRepository);
    }

    @Bean
    UpdatePost updatePost(PostRepository postRepository) {
        return new UpdatePost(postRepository);
    }

    @Bean
    DeletePost deletePost(PostRepository postRepository) {
        return new DeletePost(postRepository);
    }
}
