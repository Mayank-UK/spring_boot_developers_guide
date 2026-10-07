package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.DebugDemoConfig;
import guide.domain.debug.StallTheater;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the debugging stall demo with the topic's own wiring. The
 * engine's start() blocks until the parked states are on stage, so every
 * assertion here is deterministic — no sleeps. Pinned: the lock stall
 * answers the dump-shaped facts (victim BLOCKED naming the owner, owner
 * TIMED_WAITING holding the monitor), the spin stall answers RUNNABLE,
 * start-while-running is a typed 409, unknown kinds a typed 400, stop is
 * idempotent and leaves nothing parked, and the plain GET serves the same
 * report shape for the idle page. @AfterEach stops the theater so the
 * stateful singleton never bleeds between tests.
 */
@WebMvcTest(DebugDemoController.class)
@Import(DebugDemoConfig.class)
class DebugDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StallTheater theater;

    @AfterEach
    void stopTheTheater() {
        theater.stop();
    }

    @Test
    void lockStallAnswersTheDumpShapedFactsDeterministically() throws Exception {
        mockMvc.perform(post("/api/demo/debug/stall/start").param("kind", "lock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.kind").value("lock"))
                .andExpect(jsonPath("$.startedAtMs").isNumber())
                .andExpect(jsonPath("$.jvmThreadCount").isNumber())
                // inspect() lists owner before victim — deterministic order
                .andExpect(jsonPath("$.threads.length()").value(2))
                .andExpect(jsonPath("$.threads[0].name").value("debug-stall-owner"))
                .andExpect(jsonPath("$.threads[0].state").value("TIMED_WAITING"))
                .andExpect(jsonPath("$.threads[1].name").value("debug-stall-victim"))
                .andExpect(jsonPath("$.threads[1].state").value("BLOCKED"))
                .andExpect(jsonPath("$.threads[1].lock").isNotEmpty())
                .andExpect(jsonPath("$.threads[1].lockOwner").value("debug-stall-owner"))
                .andExpect(jsonPath("$.threads[1].stack[0]")
                        .value(org.hamcrest.Matchers.startsWith("at ")))
                .andExpect(jsonPath("$.reading[0]")
                        .value(org.hamcrest.Matchers.containsString("TIMED_WAITING")))
                .andExpect(jsonPath("$.reading[1]")
                        .value(org.hamcrest.Matchers.containsString("BLOCKED")))
                .andExpect(jsonPath("$.reading[1]")
                        .value(org.hamcrest.Matchers.containsString("debug-stall-owner")));
    }

    @Test
    void spinStallAnswersRunnableAndTheReadingSaysSo() throws Exception {
        mockMvc.perform(post("/api/demo/debug/stall/start").param("kind", "spin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("spin"))
                .andExpect(jsonPath("$.threads.length()").value(1))
                .andExpect(jsonPath("$.threads[0].name").value("debug-stall-spinner"))
                .andExpect(jsonPath("$.threads[0].state").value("RUNNABLE"))
                .andExpect(jsonPath("$.reading[0]")
                        .value(org.hamcrest.Matchers.containsString("RUNNABLE")));
    }

    @Test
    void startingWhileParkedIsATypedConflict() throws Exception {
        mockMvc.perform(post("/api/demo/debug/stall/start").param("kind", "lock"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/demo/debug/stall/start").param("kind", "spin"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
        // the running stall is untouched by the rejected start
        mockMvc.perform(get("/api/demo/debug/stall/threads"))
                .andExpect(jsonPath("$.kind").value("lock"));
    }

    @Test
    void unknownKindIsATypedValidationFailure() throws Exception {
        mockMvc.perform(post("/api/demo/debug/stall/start").param("kind", "deadlock"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.field").value("kind"));
    }

    @Test
    void stopClearsTheStageAndIsIdempotent() throws Exception {
        mockMvc.perform(post("/api/demo/debug/stall/start").param("kind", "lock"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/demo/debug/stall/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.lastParkedMs").isNumber())
                .andExpect(jsonPath("$.threads").isEmpty())
                .andExpect(jsonPath("$.reading[0]").value(
                        org.hamcrest.Matchers.containsString("no stall is parked")));
        // stopping again is a no-op, and a fresh start works right after
        mockMvc.perform(post("/api/demo/debug/stall/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false));
        mockMvc.perform(post("/api/demo/debug/stall/start").param("kind", "spin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true));
    }

    @Test
    void theThreadsGetServesTheIdleReportForTheFirstPaint() throws Exception {
        mockMvc.perform(get("/api/demo/debug/stall/threads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.kind").value(""))
                .andExpect(jsonPath("$.threads").isEmpty())
                .andExpect(jsonPath("$.jvmThreadCount").isNumber())
                .andExpect(jsonPath("$.reading").isArray());
    }
}
