package guide.core.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScenarioStoreTest {

    @Test
    void defaultsToFakeModeWithoutFailureInjection() {
        assertEquals(new ScenarioState(ScenarioDataMode.FAKE, false), new ScenarioStore().get());
    }

    @Test
    void readsScenarioEnvironmentValues() {
        assertEquals(new ScenarioState(ScenarioDataMode.LIVE, true),
                ScenarioState.fromEnvironment("live", "true"));
        // Empty strings are treated as unset, like the app config treats env.
        assertEquals(new ScenarioState(ScenarioDataMode.FAKE, false),
                ScenarioState.fromEnvironment("", ""));
        assertEquals(new ScenarioState(ScenarioDataMode.FAKE, false),
                ScenarioState.fromEnvironment(null, null));
    }

    @Test
    void throwsOnMalformedScenarioEnvironment() {
        IllegalStateException dataMode = assertThrows(IllegalStateException.class,
                () -> ScenarioState.fromEnvironment("prod-ish", null));
        assertTrue(dataMode.getMessage().contains("Invalid SCENARIO_DATA_MODE \"prod-ish\""));

        IllegalStateException injectFailure = assertThrows(IllegalStateException.class,
                () -> ScenarioState.fromEnvironment(null, "yes"));
        assertTrue(injectFailure.getMessage().contains("Invalid SCENARIO_INJECT_FAILURE \"yes\""));
    }

    @Test
    void applyPatchesOnlyTheProvidedFields() {
        ScenarioStore store = new ScenarioStore();
        store.apply(new ScenarioPatch(null, true));
        assertEquals(new ScenarioState(ScenarioDataMode.FAKE, true), store.get());

        store.apply(new ScenarioPatch(ScenarioDataMode.LIVE, null));
        assertEquals(new ScenarioState(ScenarioDataMode.LIVE, true), store.get());

        store.apply(ScenarioPatch.empty());
        assertEquals(new ScenarioState(ScenarioDataMode.LIVE, true), store.get());
    }

    @Test
    void resetReturnsToTheInitialStateAndClearsAreaOverrides() {
        ScenarioStore store = new ScenarioStore(new ScenarioState(ScenarioDataMode.LIVE, false));
        store.apply(new ScenarioPatch(null, true));
        store.setDataMode("posts", ScenarioDataMode.FAKE);

        store.reset();
        assertEquals(new ScenarioState(ScenarioDataMode.LIVE, false), store.get());
        assertEquals(ScenarioDataMode.LIVE, store.dataModeFor("posts"), "overrides clear on reset");
    }

    @Test
    void areaOverridesFallBackToTheGlobalMode() {
        ScenarioStore store = new ScenarioStore();
        store.setDataMode("posts", ScenarioDataMode.LIVE);

        assertEquals(ScenarioDataMode.LIVE, store.dataModeFor("posts"));
        assertEquals(ScenarioDataMode.FAKE, store.dataModeFor("caching"), "unoverridden areas follow global");
        assertEquals(ScenarioDataMode.FAKE, store.get().dataMode(), "global state untouched by override");
    }

    @Test
    void concurrentApplyNeverLosesAFinalFieldValue() throws Exception {
        ScenarioStore store = new ScenarioStore();
        int rounds = 200;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> dataModes = pool.submit(() -> {
                start.await();
                for (int i = 0; i < rounds; i++) {
                    store.apply(new ScenarioPatch(ScenarioDataMode.LIVE, null));
                }
                return null;
            });
            Future<?> failures = pool.submit(() -> {
                start.await();
                for (int i = 0; i < rounds; i++) {
                    store.apply(new ScenarioPatch(null, true));
                }
                return null;
            });
            start.countDown();
            dataModes.get(10, TimeUnit.SECONDS);
            failures.get(10, TimeUnit.SECONDS);
        }
        // Each patch read the latest state under the lock, so the LAST write of
        // each field must survive regardless of interleaving.
        assertEquals(ScenarioDataMode.LIVE, store.get().dataMode());
        assertTrue(store.get().injectFailure());
    }

    @Test
    void applyDefendsAgainstNullPatch() {
        assertThrows(NullPointerException.class, () -> new ScenarioStore().apply(null));
    }
}
