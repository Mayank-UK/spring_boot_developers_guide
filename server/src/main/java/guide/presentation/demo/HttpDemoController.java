package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.http.HeadersExhibit;
import guide.domain.http.HeadersExhibit.IncomingBody;
import guide.domain.http.HeadersExhibit.IncomingHeader;
import guide.domain.http.InspectHttpRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 03 (http-web) — the live half of the
 * methods-status-headers section:
 * - GET  /api/demo/http/inspect → echoes method, path, query, and every
 *   request header, sorted and classified (caching/auth/content/session/
 *   negotiation/cors), with secret-bearing names flagged sensitive
 * - POST /api/demo/http/inspect → the same echo plus what the server parsed
 *   out of the body: declared Content-Type, effective media type, JSON shape
 *
 * Thin like every controller: it turns the servlet request into plain data
 * (the dependency rule stops Spring here), calls ONE use case, and maps the
 * Result onto HTTP. Note the negotiation declarations on the mappings —
 * produces/consumes are this app's real examples of content negotiation and
 * the content-negotiation section quotes them.
 */
@RestController
public class HttpDemoController {

    private final InspectHttpRequest inspectHttpRequest;

    public HttpDemoController(InspectHttpRequest inspectHttpRequest) {
        this.inspectHttpRequest = inspectHttpRequest;
    }

    /** GET variant: the request annotated — no body, headers sorted and classified. */
    @GetMapping(value = "/api/demo/http/inspect", produces = MediaType.APPLICATION_JSON_VALUE)
    HeadersExhibit.InspectReport get(HttpServletRequest request) {
        return unwrap(inspectHttpRequest.execute(request.getMethod(), request.getRequestURI(),
                request.getQueryString(), headersOf(request), IncomingBody.absent()));
    }

    /** POST variant: same echo plus the parsed Content-Type and JSON shape of the body. */
    @PostMapping(value = "/api/demo/http/inspect",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    HeadersExhibit.InspectReport post(@RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        IncomingBody incoming = new IncomingBody(body != null, request.getContentType(),
                jsonKindOf(body), topLevelEntriesOf(body));
        return unwrap(inspectHttpRequest.execute(request.getMethod(), request.getRequestURI(),
                request.getQueryString(), headersOf(request), incoming));
    }

    /**
     * Flattens the servlet's header enumeration into plain pairs, joining
     * repeated names with ", " the way the wire would. Values are never
     * logged here — they go into the response report only, where the
     * sensitive flag travels with them.
     */
    private static List<IncomingHeader> headersOf(HttpServletRequest request) {
        List<IncomingHeader> headers = new ArrayList<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            List<String> values = new ArrayList<>();
            var valueIterator = request.getHeaders(name);
            while (valueIterator.hasMoreElements()) {
                values.add(valueIterator.nextElement());
            }
            if (!values.isEmpty()) {
                headers.add(new IncomingHeader(name, String.join(", ", values)));
            }
        }
        return headers;
    }

    private static String jsonKindOf(JsonNode body) {
        if (body == null) {
            return "absent";
        }
        if (body.isObject()) {
            return "object";
        }
        if (body.isArray()) {
            return "array";
        }
        return "scalar";
    }

    private static int topLevelEntriesOf(JsonNode body) {
        return body != null && (body.isObject() || body.isArray()) ? body.size() : 0;
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
