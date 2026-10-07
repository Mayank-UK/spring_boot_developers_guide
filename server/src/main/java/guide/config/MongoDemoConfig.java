package guide.config;

import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.data.mongo.InMemoryVisitStore;
import guide.data.mongo.LiveMongoArea;
import guide.data.mongo.MongoVisitStore;
import guide.data.mongo.ScenarioRoutingVisitStore;
import guide.domain.mongo.ReadRecentVisits;
import guide.domain.mongo.ReadVisitStats;
import guide.domain.mongo.RecordVisit;
import guide.domain.mongo.VisitStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Topic 19 (mongodb) wiring — the visits console's composition root: one
 * port bean (the router), three use case beans, every wire explicit. The
 * area registration mirrors the posts pattern rather than the schema one:
 * 'mongo' registers FAKE because the in-memory store is a REAL fake with a
 * deterministic seed — the console works fully with MongoDB absent, and a
 * dedicated /mode endpoint (not the global scenario PATCH) flips this one
 * area live at runtime.
 *
 * The Mongo-side beans are @Lazy and reached through ObjectProviders:
 * contexts without a MongoTemplate (test slices) never construct them,
 * and the full app pays for the live adapter only when a live call
 * actually activates the area — the optional-service discipline this
 * topic teaches, applied to its own infrastructure. Nothing here connects
 * at boot: the autoconfigured MongoClient builds without opening a
 * connection, the template is inert until queried, and the first server
 * contact is the activation ping itself.
 */
@Configuration
public class MongoDemoConfig {

    private final ObjectProvider<LiveMongoArea> liveArea;
    private final ObjectProvider<MongoVisitStore> liveStore;

    public MongoDemoConfig(ObjectProvider<LiveMongoArea> liveArea,
            ObjectProvider<MongoVisitStore> liveStore) {
        this.liveArea = liveArea;
        this.liveStore = liveStore;
    }

    /** The exposed port: the router (fake by default, live on the area's own /mode flip). */
    @Bean
    VisitStore visitStore(ScenarioStore scenarioStore) {
        // Marks this area in the store's per-mode registry: fake (seeded
        // console) by default, live at call time once the area activates.
        scenarioStore.setDataMode(VisitStore.AREA, ScenarioDataMode.FAKE);
        return new ScenarioRoutingVisitStore(
                scenarioStore, inMemoryVisitStore(), liveArea::getObject, liveStore::getObject);
    }

    /** The fake adapter of record — deterministic seed, the handle tests reset through. */
    @Bean
    InMemoryVisitStore inMemoryVisitStore() {
        return new InMemoryVisitStore();
    }

    /** The live adapter, @Lazy: no MongoTemplate touched until a live call asks for it. */
    @Lazy
    @Bean
    MongoVisitStore mongoVisitStore(MongoTemplate mongoTemplate) {
        return new MongoVisitStore(mongoTemplate);
    }

    /** The once-guarded lazy activation, @Lazy for the same reason. */
    @Lazy
    @Bean
    LiveMongoArea liveMongoArea(ScenarioStore scenarioStore, MongoVisitStore mongoVisitStore) {
        return new LiveMongoArea(scenarioStore, mongoVisitStore);
    }

    @Bean
    RecordVisit recordVisit(VisitStore visitStore) {
        return new RecordVisit(visitStore);
    }

    @Bean
    ReadVisitStats readVisitStats(VisitStore visitStore) {
        return new ReadVisitStats(visitStore);
    }

    @Bean
    ReadRecentVisits readRecentVisits(VisitStore visitStore) {
        return new ReadRecentVisits(visitStore);
    }
}
