package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.reqres.ChunkedLinesExhibit;
import guide.domain.reqres.DecideMethodResponse;
import guide.domain.reqres.InspectRequestSurface;
import guide.domain.reqres.MethodsExhibit;
import guide.domain.reqres.RequestSurfaceExhibit;
import guide.domain.reqres.RequestSurfaceExhibit.BoundBody;
import guide.domain.reqres.RequestSurfaceExhibit.IncomingAttribute;
import guide.domain.reqres.RequestSurfaceExhibit.IncomingCookie;
import guide.domain.reqres.RequestSurfaceExhibit.IncomingHeader;
import guide.domain.reqres.RequestSurfaceExhibit.IncomingParam;
import guide.domain.reqres.RequestSurfaceExhibit.RawSurface;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;

/**
 * Demo endpoints for topic 09 (request-response):
 * - GET/POST /api/demo/reqres/inspect → the two surfaces of ONE request:
 *   what MVC BOUND (via @RequestParam/@RequestHeader/@CookieValue/@RequestBody
 *   — visible in this file's own signatures) beside the RAW servlet surface
 *   (URI, query, auth type, content type, locales, attributes), summarized
 *   by the domain exhibit
 * - GET/POST/PUT/PATCH/DELETE /api/demo/reqres/methods → ONE path, five
 *   answers; every handler is one line because the status/headers/body
 *   decision is a DOMAIN table (MethodsExhibit), not controller branching
 * - GET /api/demo/reqres/stream → a real StreamingResponseBody: chunks
 *   flushed as computed (curl it in the terminal companion)
 * - GET /api/demo/reqres/cookie → ResponseCookie with explicit attributes +
 *   a 302 redirect — the cookie the inspector binds back on the next request
 *
 * Thin like every controller: turn the request into plain data, call ONE use
 * case, map the Result onto HTTP. The sugars this topic teaches are exactly
 * the annotations in the inspect signatures.
 */
@RestController
public class ReqResDemoController {

    private final InspectRequestSurface inspectRequestSurface;
    private final DecideMethodResponse decideMethodResponse;

    public ReqResDemoController(InspectRequestSurface inspectRequestSurface,
            DecideMethodResponse decideMethodResponse) {
        this.inspectRequestSurface = inspectRequestSurface;
        this.decideMethodResponse = decideMethodResponse;
    }

    // ---- the inspector: binding sugars beside the raw surface ----

    /** GET variant: every query param bound by map, three headers by name, one cookie — plus the raw object. */
    @GetMapping(value = "/api/demo/reqres/inspect", produces = MediaType.APPLICATION_JSON_VALUE)
    RequestSurfaceExhibit.Report get(@RequestParam MultiValueMap<String, String> params,
            @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String accept,
            @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent,
            @RequestHeader(name = "x-teaching-flag", required = false) String teachingFlag,
            @CookieValue(name = "reqres-visit", required = false) String visit,
            HttpServletRequest request) {
        return unwrap(inspectRequestSurface.execute(incoming(request.getMethod(), params,
                accept, userAgent, teachingFlag, visit, BoundBody.absent(), request)));
    }

    /** POST variant: the same sugars plus @RequestBody — the converter bound the whole body. */
    @PostMapping(value = "/api/demo/reqres/inspect",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    RequestSurfaceExhibit.Report post(@RequestBody(required = false) JsonNode body,
            @RequestParam MultiValueMap<String, String> params,
            @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String accept,
            @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent,
            @RequestHeader(name = "x-teaching-flag", required = false) String teachingFlag,
            @CookieValue(name = "reqres-visit", required = false) String visit,
            HttpServletRequest request) {
        return unwrap(inspectRequestSurface.execute(incoming(request.getMethod(), params,
                accept, userAgent, teachingFlag, visit, boundBodyOf(body), request)));
    }

    // ---- the method matrix: ONE path, five verbs, zero switches ----

    /** GET → 200 + representation. The decision table lives in the domain. */
    @GetMapping(value = MethodsExhibit.RESOURCE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MethodsExhibit.OutcomeBody> get() {
        return respond("GET");
    }

    /** POST → 201 + Location naming the resource it created. */
    @PostMapping(value = MethodsExhibit.RESOURCE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MethodsExhibit.OutcomeBody> post() {
        return respond("POST");
    }

    /** PUT → 200 + ETag versioning the replaced representation. */
    @PutMapping(value = MethodsExhibit.RESOURCE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MethodsExhibit.OutcomeBody> put() {
        return respond("PUT");
    }

    /** PATCH → 204, deliberately no body. */
    @PatchMapping(value = MethodsExhibit.RESOURCE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MethodsExhibit.OutcomeBody> patch() {
        return respond("PATCH");
    }

    /** DELETE → 200 + a confirmation body. */
    @DeleteMapping(value = MethodsExhibit.RESOURCE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MethodsExhibit.OutcomeBody> delete() {
        return respond("DELETE");
    }

    // ---- streaming: computed output, flushed per chunk ----

    /** GET /api/demo/reqres/stream?lines=N → text/plain streamed as it is computed. */
    @GetMapping(value = "/api/demo/reqres/stream", produces = "text/plain;charset=UTF-8")
    StreamingResponseBody stream(@RequestParam(defaultValue = "5") int lines) {
        if (lines < 1 || lines > ChunkedLinesExhibit.MAX_LINES) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "lines must be between 1 and " + ChunkedLinesExhibit.MAX_LINES,
                    Map.of("lines", lines)));
        }
        return output -> ChunkedLinesExhibit.writeLines(output, lines);
    }

    // ---- cookies + redirects: set with attributes, then 302 to the inspector ----

    /** Sets reqres-visit with explicit attributes, then 302 Found to the inspector that binds it back. */
    @GetMapping("/api/demo/reqres/cookie")
    ResponseEntity<Void> cookieTour() {
        ResponseCookie cookie = ResponseCookie.from("reqres-visit", "set-by-tour")
                .httpOnly(true)
                .sameSite("Lax")
                .path("/api/demo/reqres")
                .maxAge(Duration.ofHours(1))
                .build();
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .location(URI.create("/api/demo/reqres/inspect?channel=cookie-tour"))
                .build();
    }

    // ---- assembly helpers: the request → plain-data edge (Spring stops here) ----

    private static RequestSurfaceExhibit.Incoming incoming(String method, MultiValueMap<String, String> params,
            String accept, String userAgent, String teachingFlag, String visit, BoundBody body,
            HttpServletRequest request) {
        List<IncomingParam> boundParams = params == null ? List.of() : params.entrySet().stream()
                .map(entry -> new IncomingParam(entry.getKey(), String.join(", ", entry.getValue())))
                .toList();
        List<IncomingHeader> headers = new ArrayList<>();
        if (accept != null) {
            headers.add(new IncomingHeader(HttpHeaders.ACCEPT, accept));
        }
        if (userAgent != null) {
            headers.add(new IncomingHeader(HttpHeaders.USER_AGENT, userAgent));
        }
        if (teachingFlag != null) {
            headers.add(new IncomingHeader("x-teaching-flag", teachingFlag));
        }
        List<IncomingCookie> cookies = visit != null
                ? List.of(new IncomingCookie("reqres-visit", visit))
                : List.of();
        RawSurface raw = new RawSurface(request.getRequestURI(), request.getQueryString(),
                request.getAuthType(), request.getContentType(),
                localesOf(request));
        return new RequestSurfaceExhibit.Incoming(method, boundParams, headers, cookies, body,
                raw, attributesOf(request));
    }

    /** Locale list as BCP-47 language tags — the modern spelling of a locale. */
    private static List<String> localesOf(HttpServletRequest request) {
        List<String> locales = new ArrayList<>();
        var enumeration = request.getLocales();
        while (enumeration != null && enumeration.hasMoreElements()) {
            locales.add(enumeration.nextElement().toLanguageTag());
        }
        return locales;
    }

    /**
     * The raw surface's exclusive cargo, filtered to the app's own: the
     * servlet request carries dozens of framework attributes
     * (org.springframework.*, jakarta.*) — noise for teaching — while the
     * request-id filter's "requestId" (topic 08) is exactly the middleware
     * trace worth surfacing. Null-tolerant: a slice with no attributes
     * yields an empty list.
     */
    private static List<IncomingAttribute> attributesOf(HttpServletRequest request) {
        List<IncomingAttribute> attributes = new ArrayList<>();
        var names = request.getAttributeNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (isFrameworkAttribute(name)) {
                continue;
            }
            Object value = request.getAttribute(name);
            attributes.add(new IncomingAttribute(name, value != null ? String.valueOf(value) : "null"));
        }
        attributes.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return attributes;
    }

    private static boolean isFrameworkAttribute(String name) {
        String lowered = name.toLowerCase(Locale.ROOT);
        return lowered.startsWith("org.springframework") || lowered.startsWith("jakarta.")
                || lowered.startsWith("javax.") || lowered.startsWith("spring.")
                || lowered.startsWith("org.apache.") || lowered.endsWith(".FILTERED");
    }

    private static BoundBody boundBodyOf(JsonNode body) {
        if (body == null) {
            return BoundBody.absent();
        }
        String parsedAs = body.isObject() ? "object" : body.isArray() ? "array" : "scalar";
        int entries = body.isObject() || body.isArray() ? body.size() : 0;
        return new BoundBody(true, parsedAs, entries);
    }

    /** The whole methods demo in one line per verb: ask the domain, map the decision. */
    private ResponseEntity<MethodsExhibit.OutcomeBody> respond(String method) {
        return switch (decideMethodResponse.execute(method)) {
            case Success<MethodsExhibit.Decision> success -> toResponse(success.value());
            case Failed<MethodsExhibit.Decision> failed -> throw AppException.of(failed.failure());
        };
    }

    /** Generic decision → ResponseEntity mapping: no per-verb branching lives at the edge. */
    private static ResponseEntity<MethodsExhibit.OutcomeBody> toResponse(MethodsExhibit.Decision decision) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(decision.status());
        decision.headers().forEach(builder::header);
        return decision.body() != null ? builder.body(decision.body()) : builder.build();
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
