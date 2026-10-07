package guide.config;

import guide.data.security.InMemoryErasureLog;
import guide.data.security.InMemoryKeyVault;
import guide.data.security.InMemoryPiiVault;
import guide.domain.security.ErasureLog;
import guide.domain.security.ErasureService;
import guide.domain.security.GrpcPostureInterceptor;
import guide.domain.security.KeyVault;
import guide.domain.security.PiiVault;
import guide.domain.security.RateLimitService;
import guide.domain.security.ReadPii;
import guide.domain.security.StorePii;
import io.grpc.ServerInterceptor;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Demo wiring for topic 25 (security) — the app-security hardening lab,
 * built on topic 23's baseline chain. Everything here is REAL machinery the
 * topic quotes: the CORS policy the chain consults, the deliberately
 * insecure comparison chain the headers lab probes against, the Bucket4j
 * rate limiter behind the 429+Retry-After contract, the PII crypto-shred
 * vault, the SSRF allowlist policy object (its consumer lives in
 * WebhooksDemoConfig, the sanctioned shared edit), and the gRPC posture
 * interceptor that replaced the blanket autoconfiguration exclusion.
 *
 * The insecure-comparison chain is the one deliberate SECOND
 * SecurityFilterChain in the app: same server, /api/demo/security/lab/
 * insecure/** only, every response protection switched OFF — so the headers
 * lab can show both header sets from the SAME deployment instead of asking
 * you to believe a slide. @Order(1) puts it ahead of the main chain
 * (unordered = lowest precedence); its matcher is narrow on purpose.
 */
@Configuration
public class SecurityDemoConfig {

    /** The one origin the CORS policy allowlists — the demo's pretend partner app. */
    public static final String DEMO_PARTNER_ORIGIN = "https://partner.demo.sdg.dev";

    // ── CORS: the policy the main chain's .cors(...) consults ──────────────────

    /**
     * An EXPLICIT origin allowlist, not "*": methods limited to GET/POST (a
     * read-and-write partner needs nothing else), no credentials (see the
     * topic for why "*" + credentials is forbidden by the spec itself),
     * headers limited to what the partner actually sends, and a preflight
     * cache that keeps the browser from re-asking for an hour.
     *
     * The list carries the app's OWN client surfaces beside the demo partner
     * — load-bearing: browsers attach Origin to non-GET requests even
     * same-origin, the dev/proxied client calls cross-PORT (5176 → 8080, and
     * the prod proxy's 8081), and a CorsFilter policy that omits them would
     * 403 the app's own UI on its first POST. An allowlist must include
     * every origin you legitimately serve, not just the interesting ones.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration policy = new CorsConfiguration();
        policy.setAllowedOrigins(List.of(DEMO_PARTNER_ORIGIN,
                "http://localhost:5176", "http://localhost:8081"));
        policy.setAllowedMethods(List.of(HttpMethod.GET.name(), HttpMethod.POST.name()));
        policy.setAllowedHeaders(List.of("Content-Type", "X-API-Key"));
        policy.setAllowCredentials(false);
        policy.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", policy);
        return source;
    }

    // ── The insecure comparison: protections deliberately relaxed ──────────────

    /**
     * The anti-demo. Headers DISABLED (no nosniff, no frame deny, no CSP, no
     * referrer policy, no HSTS), CSRF off, everything permitted — the exact
     * posture an unconfigured stack serves. The headers lab fetches its
     * probe endpoint and shows the absence beside the main chain's presence.
     */
    @Bean
    @Order(1)
    SecurityFilterChain insecureComparisonChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/demo/security/lab/insecure/**")
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    // ── Rate limiting: Bucket4j, per-key, in memory ────────────────────────────

    @Bean
    RateLimitService rateLimitService() {
        return new RateLimitService();
    }

    // ── The PII crypto-shred vault ─────────────────────────────────────────────

    @Bean
    KeyVault keyVault() {
        return new InMemoryKeyVault();
    }

    @Bean
    PiiVault piiVault() {
        return new InMemoryPiiVault();
    }

    @Bean
    ErasureLog erasureLog() {
        return new InMemoryErasureLog(Clock.systemUTC());
    }

    @Bean
    StorePii storePii(PiiVault piiVault, KeyVault keyVault) {
        return new StorePii(piiVault, keyVault, Clock.systemUTC());
    }

    @Bean
    ReadPii readPii(PiiVault piiVault, KeyVault keyVault) {
        return new ReadPii(piiVault, keyVault);
    }

    @Bean
    ErasureService erasureService(PiiVault piiVault, KeyVault keyVault, ErasureLog erasureLog) {
        return new ErasureService(piiVault, keyVault, erasureLog, Clock.systemUTC());
    }

    // ── The deliberate gRPC posture, globally registered ───────────────────────

    /**
     * spring-grpc's @GlobalServerInterceptor applies this to EVERY @GrpcService
     * — the permit-all verdict the yaml comment documents, now a stated rule
     * instead of an exclusion side effect (presented credentials are REFUSED,
     * never silently ignored).
     */
    @Bean
    @GlobalServerInterceptor
    ServerInterceptor grpcPostureInterceptor() {
        return new GrpcPostureInterceptor();
    }
}
