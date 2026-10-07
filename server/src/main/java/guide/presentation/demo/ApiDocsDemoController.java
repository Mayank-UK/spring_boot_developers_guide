package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Demo endpoints for topic 12 (api-documentation) — the docs, documented.
 *
 * - GET /api/demo/api-docs/coverage → a report computed from the LIVE
 *   /v3/api-docs document: path/operation/schema counts, sample paths grouped
 *   by surface, the spec version, and where Swagger UI mounts.
 *
 * The fetching detail is adapter-isolated on purpose: the handler asks THIS
 * server for /v3/api-docs over HTTP (exactly what the terminal companion does
 * — the report can never be a cached snapshot), through the RestClient bean
 * wired in ApiDocsDemoConfig; the base URI is derived from the incoming
 * request, so the demo works on any port. Swapping the fetch for a springdoc
 * bean later would change ONE method here.
 *
 * The counting half (CoverageReport.of) is a pure function of the parsed
 * document — counts and capped samples, never exact lists, so the demo stays
 * honest while sibling topics keep adding endpoints to the same spec.
 */
@Tag(name = "API docs", description = "The api-documentation topic's report on the generated spec itself.")
@RestController
@RequestMapping("/api/demo/api-docs")
public class ApiDocsDemoController {

    /** Where springdoc mounts Swagger UI in this app (application.yml owns the truth). */
    static final String SWAGGER_UI_PATH = "/docs";

    /** The OpenAPI path-item keys that count as operations (lowercase, per spec). */
    private static final Set<String> OPERATION_KEYS =
            Set.of("get", "post", "put", "delete", "patch", "head", "options", "trace");

    /** How many example paths each group shows — samples, never exhaustive lists. */
    private static final int SAMPLES_PER_GROUP = 4;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ApiDocsDemoController(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /** GET /api/demo/api-docs/coverage → 200 { paths, operations, schemas, samplePaths, specVersion, swaggerUiUrl } */
    @Operation(summary = "Coverage report over the live OpenAPI document",
            operationId = "getApiDocsCoverage",
            description = "Fetches /v3/api-docs from this very server and counts what it finds — "
                    + "the report is derived, never hand-maintained.")
    @GetMapping(value = "/coverage", produces = MediaType.APPLICATION_JSON_VALUE)
    CoverageReport coverage() {
        return CoverageReport.of(fetchLiveSpec());
    }

    // ---- the fetch adapter: the only line that knows WHERE the spec lives ----

    /** Self-fetch the live document over HTTP — same consumer the terminal uses. */
    private JsonNode fetchLiveSpec() {
        URI specUri = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/v3/api-docs")
                .build()
                .toUri();
        String body = restClient.get().uri(specUri).retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            throw AppException.of(Failure.of(FailureKind.INTERNAL,
                    "the OpenAPI document at /v3/api-docs came back empty"));
        }
        return objectMapper.readTree(body);
    }

    // ---- the report: a pure function of the parsed document ----

    /**
     * Counts and samples derived from one OpenAPI document. Deliberately
     * resilient: exact numbers move as the app grows, so consumers assert
     * ranges and memberships, never totals — only the drift test pins exact
     * paths, because THAT is its job.
     */
    record CoverageReport(int paths, int operations, int schemas,
            Map<String, List<String>> samplePaths, String specVersion, String swaggerUiUrl) {

        static CoverageReport of(JsonNode spec) {
            JsonNode paths = spec.path("paths");
            int pathCount = 0;
            int operationCount = 0;
            Map<String, List<String>> grouped = new TreeMap<>();
            for (Map.Entry<String, JsonNode> pathItem : paths.properties()) {
                pathCount += 1;
                operationCount += countOperations(pathItem.getValue());
                List<String> group = grouped.computeIfAbsent(groupOf(pathItem.getKey()),
                        key -> new ArrayList<>());
                if (group.size() < SAMPLES_PER_GROUP) {
                    group.add(pathItem.getKey());
                }
            }
            String version = spec.path("openapi").asString();
            return new CoverageReport(pathCount, operationCount,
                    spec.path("components").path("schemas").size(), grouped,
                    version.isBlank() ? "unknown" : version, SWAGGER_UI_PATH);
        }

        private static int countOperations(JsonNode pathItem) {
            int count = 0;
            for (Map.Entry<String, JsonNode> entry : pathItem.properties()) {
                if (OPERATION_KEYS.contains(entry.getKey())) {
                    count += 1;
                }
            }
            return count;
        }

        /**
         * The surface a path belongs to: the posts substrate keeps its own
         * group, other demo areas group by their topic, and the core API
         * (content, health) groups by resource.
         */
        private static String groupOf(String path) {
            if (path.startsWith("/api/demo/api/")) {
                return "posts";
            }
            if (path.startsWith("/api/demo/")) {
                return segment(path, "/api/demo/");
            }
            if (path.startsWith("/api/")) {
                return segment(path, "/api/");
            }
            return "other";
        }

        private static String segment(String path, String after) {
            String rest = path.substring(after.length());
            int slash = rest.indexOf('/');
            return slash < 0 ? rest : rest.substring(0, slash);
        }
    }
}
