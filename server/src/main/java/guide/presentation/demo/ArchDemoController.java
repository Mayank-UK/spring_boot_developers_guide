package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.logging.RequestIdFilter;
import guide.core.middleware.PipelineTrace;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.arch.BuildLayerMap;
import guide.domain.arch.TraceRequestFlow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 32 (architecture):
 *
 * - GET /api/demo/arch/trace?target=content|posts — drives one REAL internal
 *   request through every layer and returns the layered story assembled from
 *   the app's own instrumentation: the middleware ring's markers, the aspect
 *   records, and measured timings. This handler also marks its own position
 *   on the CALLING request's trace (topic 08's pattern) so the response
 *   shows both requests: the one you made, nested inside the one it drove.
 * - GET /api/demo/arch/map — the computed layer map: class counts per layer,
 *   the port/adapter inventory with @Bean bindings, and the exemption
 *   ledger the DependencyRuleTest enforces.
 *
 * Thin like every controller here: parse the input, call ONE use case, map
 * the Result — the tracer's own story is the exhibit for why.
 */
@Tag(name = "Architecture", description = "Topic 32's live proof: the dependency rule, the layer map, and one traced request through all of it.")
@RestController
@RequestMapping("/api/demo/arch")
public class ArchDemoController {

    /** Where this handler marks itself on the calling request's pipeline trace. */
    public static final String HANDLER_MARKER = "handler:arch-tracer";

    private final TraceRequestFlow traceRequestFlow;
    private final BuildLayerMap buildLayerMap;

    public ArchDemoController(TraceRequestFlow traceRequestFlow, BuildLayerMap buildLayerMap) {
        this.traceRequestFlow = traceRequestFlow;
        this.buildLayerMap = buildLayerMap;
    }

    /** GET /api/demo/arch/trace?target=content|posts → 200 the layered story, or 400 for an unknown target. */
    @Operation(summary = "Drive one real internal request and return its layered story",
            operationId = "traceRequestFlow",
            description = "Fires a genuine HTTP self-call while the app's own instrumentation "
                    + "collects the evidence: the middleware pipeline's markers, the aspect-recorded "
                    + "use-case invocation, and the measured timings.")
    @GetMapping("/trace")
    TraceRequestFlow.RequestFlowStory trace(@RequestParam(defaultValue = "content") String target,
            HttpServletRequest request) {
        PipelineTrace.mark(request, HANDLER_MARKER);
        return unwrap(traceRequestFlow.execute(target, requestId(request), PipelineTrace.markers(request)));
    }

    /** GET /api/demo/arch/map → 200 layers with counts, the port/adapter inventory, the exemptions. */
    @Operation(summary = "The computed layer map of this running application",
            operationId = "getLayerMap",
            description = "Class counts per layer from the classpath, every domain port with its "
                    + "data-layer adapters and config @Bean bindings, and the documented exemption "
                    + "ledger — computed, never hand-maintained.")
    @GetMapping("/map")
    BuildLayerMap.LayerMap map() {
        return unwrap(buildLayerMap.execute());
    }

    /** The id RequestIdFilter stamped into the request attribute; "n/a" when no filter ran (slices). */
    private static String requestId(HttpServletRequest request) {
        return request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE) instanceof String value
                ? value
                : "n/a";
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
