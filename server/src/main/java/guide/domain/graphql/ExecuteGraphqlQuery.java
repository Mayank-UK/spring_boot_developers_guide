package guide.domain.graphql;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.graphql.ExecutionGraphQlResponse;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest;

/**
 * Use case: execute one GraphQL document against the app's REAL schema and
 * execution service — the engine behind the playground demo's run endpoint.
 * It is a thin, honest wrapper: the {@link ExecutionGraphQlService} Boot
 * auto-configures (schema + controllers + batch loaders, the same machinery
 * the /graphql HTTP endpoint serves) executes the document, and this use
 * case's added value is the app's usual one — judge the input, keep the
 * Result discipline, and reshape the answer into the {data, errors} view
 * the demo renders (each error carrying message, path, and extensions, so
 * the taxonomy's code + requestId are visible as data, not as an HTTP
 * status).
 *
 * Note what does NOT happen here: no HTTP. Executing a GraphQL document is
 * transport-independent — the same service an HTTP handler drives is
 * drivable from a controller, a scheduler, or a test, which is exactly why
 * the playground can run real queries without a client-side GraphQL stack.
 */
public class ExecuteGraphqlQuery {

    private static final String REQUEST_ID = "graphql-playground";

    /** The answer shape the demo renders: GraphQL's own envelope, error maps included. */
    public record GraphqlOutcome(Map<String, Object> data, List<Map<String, Object>> errors) {

        public GraphqlOutcome {
            errors = List.copyOf(errors);
        }

        /** True when the document executed with zero GraphQL errors. */
        public boolean isClean() {
            return errors.isEmpty();
        }
    }

    private final ExecutionGraphQlService graphQlService;

    public ExecuteGraphqlQuery(ExecutionGraphQlService graphQlService) {
        this.graphQlService = graphQlService;
    }

    /**
     * Executes one document. A blank query is a validation failure (Failed
     * Result, the caller maps it to the standard envelope); a document that
     * EXECUTES but fails (unknown field, not_found from a resolver) is a
     * SUCCESS here carrying errors — GraphQL always answers 200 with
     * {data, errors}, and this use case preserves that truth instead of
     * laundering it into an HTTP status.
     */
    public Result<GraphqlOutcome> execute(String query, Map<String, Object> variables) {
        if (query == null || query.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "a GraphQL run needs a query document",
                    Map.of("fields", Map.of("query", "is required"))));
        }
        Map<String, Object> safeVariables = variables == null ? Map.of() : variables;
        DefaultExecutionGraphQlRequest request = new DefaultExecutionGraphQlRequest(
                query, null, safeVariables, Map.of(), REQUEST_ID, Locale.ROOT);
        ExecutionGraphQlResponse response = Objects.requireNonNull(
                graphQlService.execute(request).block(java.time.Duration.ofSeconds(10)),
                "the GraphQL execution service must answer");
        return Result.success(new GraphqlOutcome(response.getData(), errorViews(response)));
    }

    private static List<Map<String, Object>> errorViews(ExecutionGraphQlResponse response) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (org.springframework.graphql.ResponseError error : response.getErrors()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("message", error.getMessage());
            view.put("path", error.getParsedPath());
            view.put("extensions", error.getExtensions());
            views.add(view);
        }
        return views;
    }
}
