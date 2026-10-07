package guide.presentation;

import guide.core.errors.ValidationException;
import guide.core.scenario.ScenarioDataMode;
import guide.core.scenario.ScenarioPatch;
import guide.core.scenario.ScenarioState;
import guide.core.scenario.ScenarioStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Demo scenario API (express-parity shape):
 * - GET   /api/demo/scenario → current runtime state { dataMode, injectFailure }
 * - PATCH /api/demo/scenario → partial update { dataMode?, injectFailure? }
 *
 * The PATCH body arrives as untyped JSON, so the controller validates it
 * exactly like the express guide did: object-only, known fields only, typed
 * values only — every violation becomes one validation envelope carrying the
 * per-field reasons under details.fields.
 */
@RestController
public class ScenarioController {

    private static final Set<String> KNOWN_FIELDS = Set.of("dataMode", "injectFailure");

    private final ScenarioStore scenarioStore;

    public ScenarioController(ScenarioStore scenarioStore) {
        this.scenarioStore = scenarioStore;
    }

    @GetMapping("/api/demo/scenario")
    ScenarioState get() {
        return scenarioStore.get();
    }

    @PatchMapping("/api/demo/scenario")
    ScenarioState patch(@RequestBody JsonNode body) {
        ScenarioPatch patch = parsePatch(body);
        try {
            return scenarioStore.apply(patch);
        } catch (IllegalArgumentException e) {
            // The store defends its own invariants too; if its rules ever
            // diverge from parsePatch, that must still surface as a 400.
            throw new ValidationException("scenario patch body is invalid",
                    Map.of("parameter", "body"), e);
        }
    }

    /** Ported field-for-field from the express parseScenarioPatch (demo_routes.ts). */
    static ScenarioPatch parsePatch(JsonNode body) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (body == null || !body.isObject()) {
            throw new ValidationException("scenario patch body must be a JSON object",
                    Map.of("parameter", "body", "fields", Map.of("body", "must be a JSON object")));
        }

        for (Map.Entry<String, JsonNode> entry : body.properties()) {
            if (!KNOWN_FIELDS.contains(entry.getKey())) {
                fields.put(entry.getKey(), "unknown field — only dataMode and injectFailure are accepted");
            }
        }

        JsonNode dataMode = body.get("dataMode");
        if (dataMode != null && !isDataMode(dataMode)) {
            fields.put("dataMode", "must be \"fake\" or \"live\"");
        }
        JsonNode injectFailure = body.get("injectFailure");
        if (injectFailure != null && !injectFailure.isBoolean()) {
            fields.put("injectFailure", "must be a boolean");
        }
        if (!fields.isEmpty()) {
            throw new ValidationException("scenario patch body is invalid",
                    Map.of("parameter", "body", "fields", fields));
        }

        // Null-checks double as narrowing: anything else already failed validation
        // (including JSON null, which survives a body.get(...) != null check as NullNode).
        ScenarioDataMode mode = dataMode != null ? ScenarioDataMode.fromWire(dataMode.asText()) : null;
        Boolean failure = injectFailure != null ? injectFailure.asBoolean() : null;
        return new ScenarioPatch(mode, failure);
    }

    private static boolean isDataMode(JsonNode node) {
        if (!node.isTextual()) {
            return false;
        }
        String value = node.asText();
        return value.equals(ScenarioDataMode.FAKE.wireName()) || value.equals(ScenarioDataMode.LIVE.wireName());
    }
}
