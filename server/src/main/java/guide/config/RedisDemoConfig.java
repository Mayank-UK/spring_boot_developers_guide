package guide.config;

import guide.core.scenario.ScenarioStore;
import guide.data.redis.InMemoryRedisPlayground;
import guide.data.redis.LiveRedisArea;
import guide.data.redis.RecentMessageListener;
import guide.data.redis.RedisCacheBackend;
import guide.data.redis.RedisNamespace;
import guide.data.redis.ScenarioRoutingRedisPlayground;
import guide.data.redis.StringRedisTemplatePlayground;
import guide.domain.redis.CacheBackend;
import guide.domain.redis.InspectTtl;
import guide.domain.redis.PublishMessage;
import guide.domain.redis.RecentMessages;
import guide.domain.redis.RedisPlayground;
import guide.domain.redis.RunRedisOp;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Topic 20 (redis) wiring — the playground's composition root: one port
 * bean (the router), three use case beans, the recent-messages ring, and a
 * @Lazy live side nothing constructs until a live call activates it (the
 * house rule from PostsDemoConfig/JdbcSqlDemoConfig, restated for Redis).
 *
 * THE AREA CARRIES NO PIN — the deliberate difference from 'api' (pinned
 * fake) and 'db' (pinned live): the 'redis' area follows the GLOBAL
 * scenario toggle, so the public PATCH /api/demo/scenario flips the demo
 * between the in-memory fake and the real server at runtime. For an
 * OPTIONAL service that is the point: the flip, and the typed 503 when the
 * flip outruns the infrastructure, ARE the curriculum.
 *
 * The listener container is BUILT, never REGISTERED: SmartLifecycle beans
 * are resolved and started by the lifecycle processor at context refresh
 * even through @Lazy (a registered container subscribed — and failed the
 * boot — with Redis absent, and controller slices died on the missing
 * connection factory before that), so the activation constructs it after a
 * live PING succeeds and LiveRedisArea stops it on context shutdown.
 */
@Configuration
public class RedisDemoConfig {

    // ---- the port (router) and its two adapters ----

    /** The exposed port: fake by default, live on scenario flip. @Primary wins every by-type injection. */
    @Primary
    @Bean
    RedisPlayground redisPlayground(ScenarioStore scenarioStore, RecentMessages recent,
            ObjectProvider<LiveRedisArea> activation,
            ObjectProvider<StringRedisTemplatePlayground> liveAdapter) {
        return new ScenarioRoutingRedisPlayground(scenarioStore,
                inMemoryRedisPlayground(recent), activation::getObject, liveAdapter::getObject);
    }

    /** The fake adapter of record — the boot-without-Redis answer, and the handle tests reset through. */
    @Bean
    InMemoryRedisPlayground inMemoryRedisPlayground(RecentMessages recent) {
        return new InMemoryRedisPlayground(recent);
    }

    /** The live adapter, @Lazy: no StringRedisTemplate touched until a live call. */
    @Lazy
    @Bean
    StringRedisTemplatePlayground stringRedisTemplatePlayground(StringRedisTemplate template) {
        return new StringRedisTemplatePlayground(template);
    }

    /** The once-guarded activation (PING + subscription start), @Lazy for the same reason. */
    @Lazy
    @Bean
    LiveRedisArea liveRedisArea(ObjectProvider<StringRedisTemplate> template,
            ObjectProvider<RedisConnectionFactory> connectionFactory,
            ObjectProvider<RecentMessageListener> listener) {
        return new LiveRedisArea(template::getObject,
                () -> buildListenerContainer(connectionFactory.getObject(), listener.getObject()));
    }

    // ---- pub/sub ----

    /** The bounded recent-messages window the demo (and the listener) share. */
    @Bean
    RecentMessages recentMessages() {
        return new RecentMessages();
    }

    /** The subscription seam: deliveries become ring entries. */
    @Bean
    RecentMessageListener recentMessageListener(RecentMessages recent) {
        return new RecentMessageListener(recent);
    }

    /**
     * The subscription container, BUILT — never REGISTERED — and only when a
     * live PING has succeeded. This is the hard-won shape: a
     * RedisMessageListenerContainer is a SmartLifecycle bean, and Spring's
     * lifecycle processor RESOLVES AND STARTS SmartLifecycle beans at context
     * refresh even when their definitions are @Lazy — a registered container
     * subscribed (and failed the boot) with Redis absent, and in controller
     * slices it died on the missing RedisConnectionFactory before that.
     * autoStartup=false fixes the first problem but not the second, so the
     * container has no bean definition at all: LiveRedisArea constructs it
     * here, calls the init callback a non-bean never gets, and starts it by
     * hand. The concrete channel (guide:demo:chan:alive) rides beside the
     * pattern because Lettuce multiplexes commands and subscriptions on one
     * connection — it keeps a subscription counted while the wildcard does
     * the matching.
     */
    private static RedisMessageListenerContainer buildListenerContainer(RedisConnectionFactory factory,
            RecentMessageListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(listener, new PatternTopic(RedisNamespace.CHANNEL_PATTERN));
        container.addMessageListener(listener,
                new ChannelTopic(RedisNamespace.PREFIX + "chan:alive"));
        container.afterPropertiesSet();
        return container;
    }

    // ---- use cases (eager; the controller injects these) ----

    @Bean
    RunRedisOp runRedisOp(RedisPlayground playground) {
        return new RunRedisOp(playground);
    }

    @Bean
    InspectTtl inspectTtl(RedisPlayground playground) {
        return new InspectTtl(playground);
    }

    @Bean
    PublishMessage publishMessage(RedisPlayground playground) {
        return new PublishMessage(playground);
    }

    // ---- topic 21's seam, delivered lazy and unused until the caching topic asks ----

    @Lazy
    @Bean
    CacheBackend cacheBackend(StringRedisTemplate template) {
        return new RedisCacheBackend(template);
    }
}
