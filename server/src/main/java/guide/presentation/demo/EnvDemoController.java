package guide.presentation.demo;

import guide.domain.environments.EnvInspection;
import guide.domain.environments.InspectEnvironment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoint for topic 36 (environments) — the env inspector, live:
 *
 * - GET /api/demo/env/inspector → the environment story for a CONTROLLED,
 *   teachable set of facts: the active profiles, the curated guide.env.*
 *   values (each classified safe vs secret — the secret's value has no path
 *   into the answer, only the dev-default verdict travels), and the
 *   runtime-switchable flags this app really has (the ScenarioStore).
 *
 * Thin as always: one endpoint, one use case call, no mapping logic — the
 * inspection is already the wire shape. Deterministic: every input is
 * process state that exists before the first request.
 */
@RestController
public class EnvDemoController {

    private final InspectEnvironment inspectEnvironment;

    public EnvDemoController(InspectEnvironment inspectEnvironment) {
        this.inspectEnvironment = inspectEnvironment;
    }

    @GetMapping(value = "/api/demo/env/inspector", produces = MediaType.APPLICATION_JSON_VALUE)
    EnvInspection inspect() {
        return inspectEnvironment.execute();
    }
}
