package guide.presentation.demo.security;

import guide.config.SecurityDemoConfig;
import guide.core.errors.ErrorResponse;
import guide.core.logging.RequestIdFilter;
import guide.domain.security.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Demo endpoints for topic 25 (security) — the LAB. Every verdict below is
 * MEASURED, never hardcoded: the headers endpoint fetches its own probe
 * routes through this app's real chains (the main chain and the
 * insecure-comparison chain), the CORS endpoint performs actual OPTIONS
 * preflights the configured policy answers, and the rate limiter spends
 * real Bucket4j tokens.
 *
 * - GET  /api/demo/security/lab/probe                 → target of the main
 *   chain (headers written by SecurityConfig)
 * - GET  /api/demo/security/lab/insecure/probe        → target of the
 *   deliberately relaxed chain (headers disabled)
 * - GET  /api/demo/security/lab/headers               → both chains' REAL
 *   response headers, annotated per header with the attack it blunts
 * - GET  /api/demo/security/lab/cors/preflight        → two live preflights
 *   (the allowlisted origin passes; an unlisted one is refused)
 * - GET/POST /api/demo/security/lab/ratelimited       → the per-key bucket
 *   (POST consumes; refusal = 429 + Retry-After + the error envelope)
 * - POST /api/demo/security/lab/ratelimited/reset     → the reset seam
 */
@RestController
public class SecurityLabDemoController {

    /** One header's teaching: what it blunts, plus demo notes. */
    private record HeaderLesson(String header, String blunts, String note) {
    }

    /** A header as measured on one probe: the REAL value the chain wrote (null = absent). */
    private record MeasuredHeader(String header, String value, String blunts, String note) {
    }

    /** Both probes' verdicts, side by side. */
    private record HeadersLab(String secureProbe, String insecureProbe, String allowlistNote,
            List<MeasuredHeader> secure, List<MeasuredHeader> insecureComparison) {
    }

    /** One live preflight's verdict. */
    private record Preflight(String requestedOrigin, String accessControlRequestMethod,
            int status, Map<String, String> accessControlVerdicts, String verdict) {
    }

    /** The cors lab: allowlisted origin + unlisted origin, both measured. */
    private record CorsLab(String policy, Preflight allowlistedOrigin, Preflight unlistedOrigin) {
    }

    /** A consumption attempt's outcome (the 200 shape; refusals are the 429 envelope). */
    private record ConsumptionReport(String key, long limit, long windowSeconds, boolean allowed,
            long remainingTokens, long retryAfterSeconds) {
    }

    /** The bucket's state without consuming (the GET shape). */
    private record StatusReport(String key, long limit, long windowSeconds, long availableTokens) {
    }

    private static final List<HeaderLesson> CATALOG = List.of(
            new HeaderLesson("X-Content-Type-Options",
                    "MIME-type confusion → script execution",
                    "nosniff forbids the browser from second-guessing Content-Type; a JSON endpoint "
                            + "stays JSON even if an attacker lands HTML-shaped bytes there"),
            new HeaderLesson("X-Frame-Options",
                    "clickjacking (UI redress)",
                    "DENY: no site may frame this origin — invisible-overlay tricks die with it"),
            new HeaderLesson("Content-Security-Policy",
                    "XSS blast radius, injection points",
                    "the allowlist the browser enforces: this app's documents load nothing but "
                            + "same-origin resources; frame-ancestors 'none' doubles the frame deny"),
            new HeaderLesson("Referrer-Policy",
                    "credential leakage in URLs",
                    "strict-origin-when-cross-origin: cross-site referrers carry the origin only — "
                            + "query strings (tokens, emails) never leave the house"),
            new HeaderLesson("Strict-Transport-Security",
                    "protocol downgrade + cookie capture",
                    "one year of HTTPS-only, subdomains included — but ONLY emitted on secure "
                            + "connections: this HTTP probe intentionally carries none"));

    private static final long LIMIT_FLOOR = 1;
    private static final long LIMIT_CEILING = 1000;
    private static final long WINDOW_FLOOR_SECONDS = 1;
    private static final long WINDOW_CEILING_SECONDS = 3600;

    private final RateLimitService rateLimitService;
    private final Environment environment;
    private final Map<Integer, RestClient> selfByPort = new ConcurrentHashMap<>();

    /**
     * The port is read LAZILY per request, not at construction: a
     * RANDOM_PORT test context only publishes local.server.port once the
     * web server is up — a constructor @Value would see the 8080 fallback
     * and self-fetch into a refused socket. Reading the Environment at
     * request time sees the real port in every context.
     */
    public SecurityLabDemoController(RateLimitService rateLimitService, Environment environment) {
        this.rateLimitService = rateLimitService;
        this.environment = environment;
    }

    private RestClient self() {
        int port = environment.getProperty("local.server.port", Integer.class, 8080);
        return selfByPort.computeIfAbsent(port, value ->
                RestClient.builder().baseUrl("http://localhost:" + value).build());
    }

    // ── the probes the lab measures ────────────────────────────────────────────

    @GetMapping(value = "/api/demo/security/lab/probe", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> probe() {
        return Map.of("ok", true, "chain", "main (headers hardened — SecurityConfig)");
    }

    @GetMapping(value = "/api/demo/security/lab/insecure/probe",
            produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> insecureProbe() {
        return Map.of("ok", true, "chain", "insecure-comparison (protections deliberately relaxed)");
    }

    // ── the headers lab ────────────────────────────────────────────────────────

    @GetMapping(value = "/api/demo/security/lab/headers", produces = MediaType.APPLICATION_JSON_VALUE)
    HeadersLab headers() {
        Map<String, String> secure = fetchHeaders("/api/demo/security/lab/probe");
        Map<String, String> insecure = fetchHeaders("/api/demo/security/lab/insecure/probe");
        return new HeadersLab("/api/demo/security/lab/probe", "/api/demo/security/lab/insecure/probe",
                "values below are the MEASURED response headers of this deployment — fetched live "
                        + "by this endpoint through both chains, never hardcoded",
                measure(secure, false), measure(insecure, true));
    }

    private static List<MeasuredHeader> measure(Map<String, String> headers, boolean insecure) {
        List<MeasuredHeader> report = new ArrayList<>();
        for (HeaderLesson lesson : CATALOG) {
            String value = headers.get(lesson.header());
            String note = lesson.note();
            if (insecure && value == null) {
                note = note + " — ABSENT here: the insecure-comparison chain disabled it on purpose";
            }
            report.add(new MeasuredHeader(lesson.header(), value, lesson.blunts(), note));
        }
        return report;
    }

    /**
     * One probe's cataloged headers, measured live: names looked up on the
     * REAL response (Framework 7's HttpHeaders is queried by name, not
     * enumerated — the catalog drives what we report).
     */
    private Map<String, String> fetchHeaders(String path) {
        return self().get().uri(path).exchange((request, response) -> {
            Map<String, String> names = new LinkedHashMap<>();
            for (HeaderLesson lesson : CATALOG) {
                String value = response.getHeaders().getFirst(lesson.header());
                if (value != null) {
                    names.put(lesson.header(), value);
                }
            }
            return names;
        });
    }

    // ── the CORS lab ───────────────────────────────────────────────────────────

    @GetMapping(value = "/api/demo/security/lab/cors/target", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> corsTarget() {
        return Map.of("ok", true, "note", "the preflight target — a plain POST route");
    }

    @PostMapping(value = "/api/demo/security/lab/cors/target",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> corsTargetPost(@RequestBody(required = false) Map<String, Object> body) {
        return Map.of("ok", true, "received", body == null ? Map.of() : body);
    }

    @GetMapping(value = "/api/demo/security/lab/cors/preflight",
            produces = MediaType.APPLICATION_JSON_VALUE)
    CorsLab preflight() {
        return new CorsLab("allowlisted origin " + SecurityDemoConfig.DEMO_PARTNER_ORIGIN
                + " · methods GET/POST · headers Content-Type, X-API-Key · no credentials · maxAge 3600",
                preflight(SecurityDemoConfig.DEMO_PARTNER_ORIGIN),
                preflight("https://evil.example"));
    }

    private Preflight preflight(String origin) {
        return self().method(HttpMethod.OPTIONS).uri("/api/demo/security/lab/cors/target")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type")
                .exchange((request, response) -> {
                    Map<String, String> verdicts = new LinkedHashMap<>();
                    for (String name : List.of("Access-Control-Allow-Origin",
                            "Access-Control-Allow-Methods", "Access-Control-Allow-Headers",
                            "Access-Control-Allow-Credentials", "Access-Control-Max-Age",
                            "Vary")) {
                        List<String> values = response.getHeaders().get(name);
                        if (values != null) {
                            verdicts.put(name, String.join(", ", values));
                        }
                    }
                    boolean allowed = response.getStatusCode().is2xxSuccessful();
                    return new Preflight(origin, "POST", response.getStatusCode().value(), verdicts,
                            allowed ? "allowed — the origin is on the allowlist"
                                    : "rejected — " + response.getStatusCode().value()
                                            + " Invalid CORS request (origin not allowlisted)");
                });
    }

    // ── the rate limiter ───────────────────────────────────────────────────────

    @GetMapping(value = "/api/demo/security/lab/ratelimited",
            produces = MediaType.APPLICATION_JSON_VALUE)
    StatusReport status(@RequestParam(required = false) String key,
            @RequestParam(defaultValue = "5") long limit,
            @RequestParam(defaultValue = "60") long window,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            HttpServletRequest request) {
        String limitKey = rateKey(key, apiKey, request);
        long safeLimit = clamp(limit, LIMIT_FLOOR, LIMIT_CEILING, "limit");
        long safeWindow = clamp(window, WINDOW_FLOOR_SECONDS, WINDOW_CEILING_SECONDS, "window");
        RateLimitService.Status status = rateLimitService.status(limitKey, safeLimit,
                Duration.ofSeconds(safeWindow));
        return new StatusReport(limitKey, status.limit(), status.windowSeconds(),
                status.availableTokens());
    }

    @PostMapping(value = "/api/demo/security/lab/ratelimited",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> consume(@RequestParam(required = false) String key,
            @RequestParam(defaultValue = "5") long limit,
            @RequestParam(defaultValue = "60") long window,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            HttpServletRequest request) {
        String limitKey = rateKey(key, apiKey, request);
        long safeLimit = clamp(limit, LIMIT_FLOOR, LIMIT_CEILING, "limit");
        long safeWindow = clamp(window, WINDOW_FLOOR_SECONDS, WINDOW_CEILING_SECONDS, "window");
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(limitKey, safeLimit,
                Duration.ofSeconds(safeWindow));
        if (consumption.allowed()) {
            return ResponseEntity.ok(new ConsumptionReport(limitKey, safeLimit, safeWindow, true,
                    consumption.remainingTokens(), 0));
        }
        return ResponseEntity.status(429)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(consumption.retryAfterSeconds()))
                .body(ErrorResponse.of("rate_limited",
                        "rate budget exhausted for key \"" + limitKey + "\" — retry in "
                                + consumption.retryAfterSeconds() + "s (the bucket refills at "
                                + safeLimit + " per " + safeWindow + "s)",
                        requestId(request),
                        Map.of("limit", safeLimit, "windowSeconds", safeWindow,
                                "retryAfterSeconds", consumption.retryAfterSeconds())));
    }

    /** The reset seam: forget the bucket so the next call starts full (tests + the demo's reset). */
    @PostMapping(value = "/api/demo/security/lab/ratelimited/reset",
            produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> reset(@RequestParam(required = false) String key,
            @RequestParam(defaultValue = "5") long limit,
            @RequestParam(defaultValue = "60") long window,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            HttpServletRequest request) {
        String limitKey = rateKey(key, apiKey, request);
        long safeLimit = clamp(limit, LIMIT_FLOOR, LIMIT_CEILING, "limit");
        long safeWindow = clamp(window, WINDOW_FLOOR_SECONDS, WINDOW_CEILING_SECONDS, "window");
        rateLimitService.reset(limitKey, safeLimit, Duration.ofSeconds(safeWindow));
        return Map.of("key", limitKey, "reset", true);
    }

    /** The bucket key: explicit param, else the API key, else the caller's IP. */
    private static String rateKey(String key, String apiKey, HttpServletRequest request) {
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return "apikey:" + apiKey.trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static long clamp(long value, long floor, long ceiling, String what) {
        return Math.max(floor, Math.min(ceiling, value));
    }

    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attribute instanceof String value && !value.isEmpty() ? value : "n/a";
    }
}
