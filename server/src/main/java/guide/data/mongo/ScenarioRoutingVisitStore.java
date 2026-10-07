package guide.data.mongo;

import guide.core.result.Result;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioStore;
import guide.domain.mongo.LocaleTally;
import guide.domain.mongo.TopicTally;
import guide.domain.mongo.VisitDocument;
import guide.domain.mongo.VisitDraft;
import guide.domain.mongo.VisitStore;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.bson.Document;

/**
 * The LIVE SWAP for the mongo area — ScenarioRoutingPostRepository's exact
 * shape: one VisitStore bean that answers every call from whichever
 * adapter the ScenarioStore's 'mongo' area points at AT CALL TIME. Unlike
 * the posts area, THIS area's flip is a dedicated endpoint
 * (POST /api/demo/mongo/mode) because the demo console needs to show and
 * change its own store without touching the global scenario; the global
 * PATCH keeps working too, through the same dataModeFor("mongo") read.
 *
 * The live side arrives through suppliers (ObjectProviders in the
 * composition root): @WebMvcTest slices and any context without a
 * MongoClient never construct the live beans at all, and the full app
 * pays for them only when a live call actually activates the area.
 * Activation is ONCE-GUARDED inside LiveMongoArea; a failed activation
 * throws the typed unavailable exception through the normal endpoints
 * (503 envelope) while the /mode endpoint asks for the Result form and
 * renders a hint instead.
 */
public final class ScenarioRoutingVisitStore implements VisitStore {

    private final ScenarioStore scenarioStore;
    private final InMemoryVisitStore fake;
    private final Supplier<LiveMongoArea> liveArea;
    private final Supplier<MongoVisitStore> liveStore;

    public ScenarioRoutingVisitStore(ScenarioStore scenarioStore,
            InMemoryVisitStore fake,
            Supplier<LiveMongoArea> liveArea,
            Supplier<MongoVisitStore> liveStore) {
        this.scenarioStore = Objects.requireNonNull(scenarioStore, "scenarioStore");
        this.fake = Objects.requireNonNull(fake, "fake");
        this.liveArea = Objects.requireNonNull(liveArea, "liveArea");
        this.liveStore = Objects.requireNonNull(liveStore, "liveStore");
    }

    /** The adapter this call goes to — read at CALL time, which is the whole point. */
    private VisitStore delegate() {
        return scenarioStore.dataModeFor(AREA) == ScenarioDataMode.LIVE
                ? liveActivated()
                : fake;
    }

    /** The once-guarded lazy activation: ping + indexes on first live use. */
    private VisitStore liveActivated() {
        liveArea.get().ensureActivated();
        return liveStore.get();
    }

    /** The /mode endpoint's form: attempt the live flip as a Result, never throwing. */
    public Result<String> attemptLive() {
        return liveArea.get().activate();
    }

    /** The console's transparency artifact works in BOTH modes — it never touches the server. */
    public List<Document> explainPipelines() {
        return liveStore.get().explainPipelines();
    }

    /** Whether the area currently runs live — the /mode endpoint's "current" answer. */
    public boolean isLive() {
        return scenarioStore.dataModeFor(AREA) == ScenarioDataMode.LIVE;
    }

    /** Point the area back at the fake — the /mode endpoint's other direction. */
    public void useFake() {
        scenarioStore.setDataMode(AREA, ScenarioDataMode.FAKE);
    }

    @Override
    public VisitDocument record(VisitDraft draft) {
        return delegate().record(draft);
    }

    @Override
    public List<VisitDocument> recent(int limit) {
        return delegate().recent(limit);
    }

    @Override
    public long countAll() {
        return delegate().countAll();
    }

    @Override
    public long countByTopic(String topicId) {
        return delegate().countByTopic(topicId);
    }

    @Override
    public List<TopicTally> topTopics(int limit) {
        return delegate().topTopics(limit);
    }

    @Override
    public long avgDurationMs(String topicId) {
        return delegate().avgDurationMs(topicId);
    }

    @Override
    public List<LocaleTally> byLocaleHistogram() {
        return delegate().byLocaleHistogram();
    }

    /** The fake adapter this router serves in fake mode (tests reset through it). */
    public InMemoryVisitStore fakeAdapter() {
        return fake;
    }
}
