package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.graphql.ExecuteGraphqlQuery;
import guide.domain.graphql.GraphqlQueryCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 13 (graphql) — the playground over the REAL
 * GraphQL engine:
 * - GET  /api/demo/graphql/queries → the preset query catalog (each with its
 *   teaching commentary), the picker's data
 * - POST /api/demo/graphql/run     → execute {query, variables?} against the
 *   same ExecutionGraphQlService the /graphql endpoint serves. The answer is
 *   GraphQL's own envelope — {data, errors[]} with every error's extensions
 *   (code + requestId from the failure resolver) — so the browser can show
 *   what the terminal tour curls, without a client-side GraphQL stack.
 */
@RestController
public class GraphqlDemoController {

    /** The wire request: a document plus its variables (omitted = none). */
    private record GraphqlRunRequest(String query, Map<String, Object> variables) {
    }

    /** One catalog entry as the wire serves it (the variables stay JSON text). */
    public record PresetView(String id, String title, String description, String query,
            String defaultVariables) {

        static PresetView from(GraphqlQueryCatalog.Preset preset) {
            return new PresetView(preset.id(), preset.title(), preset.description(),
                    preset.query(), preset.defaultVariables());
        }
    }

    private final ExecuteGraphqlQuery executeGraphqlQuery;

    public GraphqlDemoController(ExecuteGraphqlQuery executeGraphqlQuery) {
        this.executeGraphqlQuery = executeGraphqlQuery;
    }

    @GetMapping(value = "/api/demo/graphql/queries", produces = MediaType.APPLICATION_JSON_VALUE)
    List<PresetView> queries() {
        return GraphqlQueryCatalog.presets().stream().map(PresetView::from).toList();
    }

    @PostMapping(value = "/api/demo/graphql/run", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ExecuteGraphqlQuery.GraphqlOutcome run(@RequestBody GraphqlRunRequest request) {
        return unwrap(executeGraphqlQuery.execute(request.query(), request.variables()));
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
