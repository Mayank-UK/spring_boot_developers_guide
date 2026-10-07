package guide.config;

import guide.domain.auth.IdentityStore;
import guide.presentation.security.ApiKeyAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * THE APP'S SECURITY BASELINE (topic 23, authentication) — the one chain
 * every request crosses. Two inversions of normal practice, both
 * deliberate and both teaching decisions:
 *
 * 1. This is a TEACHING GUIDE: the content API and every demo surface
 *    stay open (permitAll) so the guide reads without a login. A real
 *    service inverts this — deny by default, enumerate what is public.
 *    The /api/demo/auth/** island is the ONE protected surface, each
 *    demo endpoint enforcing exactly what its lesson teaches.
 * 2. CSRF is disabled for API routes (and the GraphQL route): the API
 *    surface authenticates with stateless bearer tokens per-demo, and
 *    this app serves no browser forms. The full CSRF decision matrix —
 *    when cookie-session auth DOES bring CSRF duty back — is topic 25's
 *    csrf section; this baseline just leaves the note.
 *
 * ── EXTENSION POINTS (topics 24 authorization, 25 security) ──────────────────
 * - addAuthorizationRules: DONE by topic 24 (the authz island above the
 *   fallthrough) — further topics keep APPENDING above it, never rewriting.
 * - response headers + CORS: DONE by topic 25 (the .headers/.cors blocks
 *   below — the helmet equivalent; the insecure-comparison chain the
 *   headers lab probes against lives in SecurityDemoConfig).
 * - the session island: cookies are configured in application.yml
 *   (server.servlet.session.cookie.*) — topic 25 hardens them further
 *   (Secure in prod) and revisits CSRF for cookie-authenticated routes.
 * - JWT authorities: the resource server currently authenticates only;
 *   a JwtAuthenticationConverter mapping the "roles" claim to granted
 *   authorities belongs to topic 24's permissions section.
 * - the ApiKeyAuthenticationFilter is constructed HERE (not a bean) so
 *   Boot cannot double-register it as a plain servlet filter; extend it
 *   where it is added, keep that property.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The chain. Read it top to bottom — it IS the architecture section:
     * CSRF posture, response headers (topic 25's helmet), CORS (topic 25),
     * session posture, the API-key mechanism, the resource server (Bearer
     * JWT), then the authorization rules with the auth island as the only
     * protected surface.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, IdentityStore identityStore,
            JwtDecoder jwtDecoder, CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                // API routes are token/header-authenticated, not cookie+form: no CSRF
                // tokens to hand out. (The session demo's own endpoints accept the
                // deliberate per-demo decision — topic 25 teaches the full matrix.)
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/graphql/**"))
                // ── Topic 25 (security) — FLAGGED EXTENSION: response headers, the
                // helmet equivalent. Each line blunts one attack on the BROWSER
                // side of the wire (the headers lab demo reads them live):
                // nosniff kills MIME-type confusion, DENY kills clickjacking,
                // the CSP locks every document this app serves to its own
                // origin ('self' rather than 'none' because /docs Swagger UI
                // and dev GraphiQL load same-origin assets — an API with no
                // bundled UI would go all the way to default-src 'none'),
                // strict-origin-when-cross-origin trims the Referer to its
                // origin+path, and HSTS pins HTTPS for a year. HSTS is honest
                // here: the writer only emits it over a SECURE connection —
                // plain-HTTP dev responses carry every header but that one.
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .contentTypeOptions(nosniff -> {
                        })
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; frame-ancestors 'none'; "
                                        + "base-uri 'self'; form-action 'self'"))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                // ── Topic 25 (security) — FLAGGED EXTENSION: CORS. The chain now
                // consults an explicit CorsConfigurationSource (bean in
                // SecurityDemoConfig: one allowlisted demo origin, GET/POST,
                // no credentials). Preflight OPTIONS requests are answered by
                // the chain's CorsFilter BEFORE any controller; same-origin
                // and credentialed-cross-origin requests pass through untouched.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // sessions exist for exactly one demo (the session island); every
                // other surface is stateless-in-practice and never touches one
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context
                        .securityContextRepository(new HttpSessionSecurityContextRepository()))
                // mechanism 1: opaque API keys (X-API-Key → hashed lookup → principal)
                .addFilterBefore(new ApiKeyAuthenticationFilter(identityStore),
                        UsernamePasswordAuthenticationFilter.class)
                // mechanism 2: Bearer JWTs (validated by the resource server against
                // the yaml-configured HS256 secret — see application.yml)
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.decoder(jwtDecoder)))
                .authorizeHttpRequests(auth -> auth
                        // the auth island — the guide's ONLY protected surface
                        .requestMatchers(HttpMethod.POST, "/api/demo/auth/apikeys").permitAll()
                        .requestMatchers("/api/demo/auth/apikeys/**").authenticated()
                        .requestMatchers("/api/demo/auth/session/me",
                                "/api/demo/auth/session/logout").authenticated()
                        .requestMatchers("/api/demo/auth/jwt/protected").authenticated()
                        // the authz island (topic 24, FLAGGED APPEND per the extension-point
                        // javadoc — rules above the open fallthrough, never a rewrite): the
                        // matrix and the act-as switch are the island's open doors (the tour
                        // starts anonymous — reference table and identity switch carry no user
                        // data); every guarded route below demands an authenticated caller.
                        // This is the URL-rule layer: coarse, perimeter, first-match-wins.
                        .requestMatchers(HttpMethod.POST, "/api/demo/authz/act-as").permitAll()
                        .requestMatchers("/api/demo/authz/matrix").permitAll()
                        .requestMatchers("/api/demo/authz/**").authenticated()
                        // everything else in the guide stays open — the deliberate
                        // teaching inversion (real apps: deny by default)
                        .requestMatchers("/api/**").permitAll()
                        .anyRequest().permitAll());
        return http.build();
    }

    /**
     * The password hasher: bcrypt, cost 10 (~2^10 rounds, tens of
     * milliseconds on current hardware — the hashing demo tunes this).
     * One bean everywhere: the DaoAuthenticationProvider, the login use
     * cases and the hashing lab must never disagree about format.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The bridge from the identity substrate to Spring Security: one
     * method, the username question, answered against the port. Spring's
     * DaoAuthenticationProvider calls this (via the http.userDetailsService
     * shortcut this config could also use) and compares hashes itself.
     */
    @Bean
    UserDetailsService userDetailsService(IdentityStore identityStore) {
        return username -> identityStore.findByUsername(username)
                .<UserDetails>map(user -> User.withUsername(user.username())
                        .password(user.passwordHash())
                        .roles(user.roles().toArray(String[]::new))
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }
}
