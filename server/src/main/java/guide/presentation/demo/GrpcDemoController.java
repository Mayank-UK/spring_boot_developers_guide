package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.grpc.DescribeGuideDemoService;
import guide.domain.grpc.InvokeGuideDemo;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 15 (grpc) — the client console over the REAL
 * in-app gRPC server:
 * - POST /api/demo/grpc/invoke → run one rpc of the chosen interaction kind
 *   against guide.demo.GuideDemo (unary answers and streams come from the
 *   real content registry) with an optional per-call deadline; the answer is
 *   the verdict {methodKind, roundTripMs, results, status} where status is
 *   the gRPC code — OK, or e.g. DEADLINE_EXCEEDED when the deadline beat the
 *   server's artificial delay, NOT_FOUND for an unknown topic
 * - GET /api/demo/grpc/service → the contract as the generated descriptor
 *   sees it: which rpcs exist and which interaction kind each is
 */
@RestController
public class GrpcDemoController {

    /** The wire request: kebab-case method names, optional timing knobs. */
    private record GrpcInvokeRequest(String method, String topicId, Integer delayMs, Integer deadlineMs) {
    }

    private final InvokeGuideDemo invokeGuideDemo;
    private final DescribeGuideDemoService describeGuideDemoService;

    public GrpcDemoController(InvokeGuideDemo invokeGuideDemo,
            DescribeGuideDemoService describeGuideDemoService) {
        this.invokeGuideDemo = invokeGuideDemo;
        this.describeGuideDemoService = describeGuideDemoService;
    }

    @PostMapping(value = "/api/demo/grpc/invoke",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    InvokeGuideDemo.GrpcCallReport invoke(@RequestBody GrpcInvokeRequest request) {
        return unwrap(invokeGuideDemo.execute(new InvokeGuideDemo.GrpcInvocation(
                parseKind(request.method()), request.topicId(),
                request.delayMs() == null ? 0 : request.delayMs(),
                request.deadlineMs())));
    }

    @GetMapping(value = "/api/demo/grpc/service", produces = MediaType.APPLICATION_JSON_VALUE)
    DescribeGuideDemoService.ServiceView service() {
        return describeGuideDemoService.execute();
    }

    /** "server-stream" and friends → the enum; unknown kinds are a 400. */
    private static InvokeGuideDemo.MethodKind parseKind(String method) {
        if (method == null) {
            return null;
        }
        return switch (method.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "unary" -> InvokeGuideDemo.MethodKind.UNARY;
            case "server-stream" -> InvokeGuideDemo.MethodKind.SERVER_STREAM;
            case "client-stream" -> InvokeGuideDemo.MethodKind.CLIENT_STREAM;
            case "bidi" -> InvokeGuideDemo.MethodKind.BIDI;
            default -> null;
        };
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
