package guide.config;

import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.data.messaging.MsgDemoActivation;
import guide.data.messaging.OutboxEventPublisher;
import guide.data.messaging.OutboxRelayImpl;
import guide.data.messaging.OutboxRelayTicker;
import guide.data.messaging.PgOutboxStore;
import guide.data.messaging.PgPostEventTransaction;
import guide.data.messaging.RedisDirectEventPublisher;
import guide.data.messaging.RedisStreamMessaging;
import guide.data.sqldemo.DbDemoActivation;
import guide.domain.messaging.ClaimIdleEntries;
import guide.domain.messaging.ConsumeFromStream;
import guide.domain.messaging.EventCounters;
import guide.domain.messaging.EventStream;
import guide.domain.messaging.IdempotentConsumer;
import guide.domain.messaging.InspectPending;
import guide.domain.messaging.ListDeadLetters;
import guide.domain.messaging.OutboxRelay;
import guide.domain.messaging.OutboxStore;
import guide.domain.messaging.PostEventTransaction;
import guide.domain.messaging.PublishPostEvent;
import guide.domain.messaging.PublishToStream;
import guide.domain.messaging.ReadOutboxState;
import guide.domain.messaging.TeachingEventPublisher;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Topic 22 (messaging) wiring — the area's composition root: two publishers
 * on ONE port, the stream console's use cases, the outbox relay and its
 * gated ticker, every port→adapter wire explicit (the house rule from
 * PostsDemoConfig, restated).
 *
 * The ScenarioStore's 'msg' area is pinned LIVE at startup — the db area's
 * call, for the db area's reason: these demos are about the REAL Redis
 * Streams and the REAL transactional outbox; an in-memory fake of "what the
 * consumer group did" would be theater. Degradation stays honest per call:
 * the first endpoint hit pays MsgDemoActivation (typed 503 while PG or
 * Redis is gone, retried on the next call once they return).
 *
 * The adapter beans are @Lazy and injected through @Lazy parameters — the
 * parameter annotation hands dependents a resolution PROXY, so no DataSource
 * or RedisConnectionFactory is touched at boot, and contexts without them
 * (controller slices) still come up.
 *
 * The TICKER is eager but inert by design: its tick reads one boolean (the
 * relay's canRun, fed by the activation flag + the area mode) and does
 * nothing until a live activation has succeeded — the no-eager-consumers
 * rule from topic 20, restated for polling. @EnableScheduling lives on
 * JobsDemoConfig (topic 29 relocated it from the webhooks config); adding
 * it again here would be noise.
 */
@Configuration
public class MessagingDemoConfig {

    public MessagingDemoConfig(ScenarioStore scenarioStore) {
        // The area pin: msg demos answer from real PG + real Redis Streams.
        scenarioStore.setDataMode(TeachingEventPublisher.AREA, ScenarioDataMode.LIVE);
    }

    // ---- the stream console's live adapter (lazy; owns keys + group) ----

    @Lazy
    @Bean
    RedisStreamMessaging redisStreamMessaging(StringRedisTemplate template, ObjectMapper mapper) {
        return new RedisStreamMessaging(template, mapper);
    }

    @Lazy
    @Bean
    MsgDemoActivation msgDemoActivation(ObjectProvider<DbDemoActivation> dbActivation,
            ObjectProvider<StringRedisTemplate> template,
            ObjectProvider<RedisStreamMessaging> stream) {
        return new MsgDemoActivation(dbActivation::getObject, template::getObject,
                () -> stream.getObject().ensureGroup());
    }

    // ---- the two publishers, one port, taught side by side ----

    @Lazy
    @Bean
    RedisDirectEventPublisher directEventPublisher(RedisStreamMessaging stream) {
        return new RedisDirectEventPublisher(stream);
    }

    @Lazy
    @Bean
    OutboxEventPublisher outboxEventPublisher(PgOutboxStore store) {
        return new OutboxEventPublisher(store);
    }

    // ---- the outbox: store, transactional adapter, relay, ticker ----

    @Lazy
    @Bean
    PgOutboxStore pgOutboxStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new PgOutboxStore(jdbc, mapper);
    }

    @Lazy
    @Bean
    PgPostEventTransaction pgPostEventTransaction(DbDemoActivation activation, JdbcTemplate jdbc) {
        return new PgPostEventTransaction(activation, jdbc);
    }

    @Lazy
    @Bean
    OutboxRelay outboxRelay(PgOutboxStore store, RedisStreamMessaging stream,
            MsgDemoActivation activation, ScenarioStore scenarioStore) {
        // The ticker's gate: live activation AND the msg area live — the
        // scenario flip pauses/resumes the drain, observably, no restart.
        return new OutboxRelayImpl(store, stream, () -> activation.isActivated()
                && scenarioStore.dataModeFor(TeachingEventPublisher.AREA) == ScenarioDataMode.LIVE);
    }

    @Bean
    OutboxRelayTicker outboxRelayTicker(@Lazy OutboxRelay relay) {
        return new OutboxRelayTicker(relay, Clock.systemUTC());
    }

    // ---- the projection + the idempotent consumer ----

    @Bean
    EventCounters eventCounters() {
        return new EventCounters();
    }

    @Bean
    IdempotentConsumer idempotentConsumer(EventCounters counters) {
        return new IdempotentConsumer(counters);
    }

    // ---- use cases (eager; controllers inject these) ----

    @Bean
    PublishToStream publishToStream(
            @Lazy @Qualifier("directEventPublisher") TeachingEventPublisher directPublisher) {
        // The console publishes DIRECT, on purpose: no mutation to pair
        // with, the naive publisher is the honest tool for the job. The
        // @Lazy param is typed as the PORT (JDK proxy): the two publishers
        // are final classes, and a concrete-typed lazy param would demand a
        // CGLIB subclass of a final — the proxy lesson, paid quietly.
        return new PublishToStream(directPublisher, Clock.systemUTC());
    }

    @Bean
    ConsumeFromStream consumeFromStream(@Lazy EventStream stream, IdempotentConsumer consumer) {
        return new ConsumeFromStream(stream, consumer);
    }

    @Bean
    InspectPending inspectPending(@Lazy EventStream stream) {
        return new InspectPending(stream);
    }

    @Bean
    ClaimIdleEntries claimIdleEntries(@Lazy EventStream stream, IdempotentConsumer consumer) {
        return new ClaimIdleEntries(stream, consumer);
    }

    @Bean
    ListDeadLetters listDeadLetters(@Lazy EventStream stream) {
        return new ListDeadLetters(stream);
    }

    @Bean
    PublishPostEvent publishPostEvent(@Lazy PostEventTransaction transaction,
            @Lazy @Qualifier("directEventPublisher") TeachingEventPublisher directPublisher,
            @Lazy @Qualifier("outboxEventPublisher") TeachingEventPublisher outboxPublisher) {
        return new PublishPostEvent(transaction, directPublisher, outboxPublisher,
                Clock.systemUTC());
    }

    @Bean
    ReadOutboxState readOutboxState(@Lazy OutboxStore store) {
        return new ReadOutboxState(store);
    }
}
