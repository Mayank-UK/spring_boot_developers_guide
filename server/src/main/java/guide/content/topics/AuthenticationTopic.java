package guide.content.topics;

import static guide.content.topics.TopicBlocks.code;
import static guide.content.topics.TopicBlocks.demo;
import static guide.content.topics.TopicBlocks.section;
import static guide.content.topics.TopicBlocks.text;

import guide.content.ContentSection;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;

/**
 * Topic 23 — Authentication. Group auth-security, order 1. The guide's
 * biggest topic: seven demos over one security baseline, everything real
 * Spring Security (no external IdP). The code quoted throughout is the
 * app's own security configuration — guide/config/SecurityConfig.java is
 * the baseline topics 24 (authorization) and 25 (security) extend.
 */
public final class AuthenticationTopic {

    private AuthenticationTopic() {
    }

    public static Topic topic() {
        return new Topic("authentication", "Authentication",
                "Who are you? Prove it. The Spring Security filter chain, "
                        + "session logins, API keys, JWTs and the resource server, the OAuth "
                        + "authorization-code dance with PKCE, bcrypt password hashing, and "
                        + "refresh-token rotation with family reuse detection — every flow "
                        + "runnable against this app's own /api/demo/auth island.",
                TopicGroupId.AUTH_SECURITY, 1,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("what-authentication-is", "What authentication is",
                        "Authorization asks may you; authentication asks are you — and every security decision you will ever make downstream hangs on getting the second question right first.",
                        text("authn:definition", """
                                AUTHENTICATION is establishing WHO is making a request, from evidence the requester controls: a password, a key, a token, a session cookie. It is the FIRST half of the security sentence — AUTHORIZATION (topic 24: what may this identity do) is meaningless without it, and an API with only role checks trusts whatever identity a request CLAIMS.

                                Frame it as a PROOF problem. The server never "finds out" who is calling — it VERIFIES evidence against something it trusts: a stored bcrypt hash, a session entry, an HMAC key. Every mechanism here answers three questions: WHAT evidence, WHAT trust anchors its verification, WHAT lifetime. Pick wrong and you inherit that choice's failure mode: cookies bring CSRF duty, forever-keys bring revocation duty, tiny-TTL tokens bring refresh complexity — the deciding section is this trade table. The 401-vs-403 split is the wire-level echo: 401 means the proof failed or is missing; 403 means the proof was fine and the answer is no."""),
                        text("authn:evidence", """
                                Spring Boot's default posture before this topic: EVERYTHING OPEN — this guide ran 22 topics with no security on the classpath. That changes with one dependency: spring-boot-starter-security, whose autoconfigured extreme (every request authenticated, form login, a generated password) would fail every existing test. The honest move is what this app did: replace the autoconfigured chain with an explicit SecurityFilterChain stating the real posture — the guide stays open, the /api/demo/auth island is protected — quoted next section, extended by topics 24 and 25.

                                Vocabulary: a PRINCIPAL is the authenticated party (this app's UserPrincipal record), an AUTHORITY a granted right (ROLE_ADMIN), an AUTHENTICATION the verified binding between them — principal, authorities, details in a SecurityContext readable anywhere in the request. Authentication is producing that binding from evidence; everything else here is plumbing around that one sentence.""")),
                section("the-spring-security-filter-chain", "The Spring Security filter chain",
                        "Fifteen-ish servlet filters between the socket and your controller — that is the entire architecture, and every mechanism you will meet is just one more filter with a job.",
                        text("chain:architecture", """
                                Spring Security is NOT an interceptor — it is SITUATION FILTERS. Long before DispatcherServlet routes anything (topic 08's pipeline), the container hands each request to Spring Security's ONE delegate filter, fanning out to an ordered list of filter beans: CsrfFilter checks tokens, SecurityContextHolderFilter loads this request's authenticated context, a MECHANISM filter reads credentials from wherever yours live (form body, basic header, X-API-Key, Bearer token), AuthorizationFilter renders the URL-rule verdict, ExceptionTranslationFilter turns failures into 401/403. Your controller only runs if the whole chain lets the request through.

                                The HEART is not a filter: the SecurityContextHolder — a ThreadLocal of the current Authentication, readable anywhere in the request (this app's /session/me literally does). Security code you write is mostly one of three shapes: a filter that POPULATES the context, a UserDetailsService that ANSWERS a lookup, rules that READ the context. AUTHENTICATING happens in an AuthenticationManager looping over AuthenticationProviders; the workhorse DaoAuthenticationProvider asks a UserDetailsService for the user and delegates to a PasswordEncoder — the seam where YOUR code plugs in."""),
                        code("chain:security-config", "java",
                                "guide/config/SecurityConfig.java — the app's whole security posture, one readable method (topics 24/25 extend this chain, they never replace it)",
                                "server/src/main/java/guide/config/SecurityConfig.java", """
                                @Bean
                                SecurityFilterChain securityFilterChain(HttpSecurity http, IdentityStore identityStore,
                                        JwtDecoder jwtDecoder, CorsConfigurationSource corsConfigurationSource) throws Exception {
                                    http
                                        // API routes are token/header-authenticated, not cookie+form: no CSRF
                                        // tokens to hand out. (The session demo's own endpoints accept the
                                        // deliberate per-demo decision — topic 25 teaches the full matrix.)
                                        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/graphql/**"))
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
                                            // everything else in the guide stays open — the deliberate
                                            // teaching inversion (real apps: deny by default)
                                            .requestMatchers("/api/**").permitAll()
                                            .anyRequest().permitAll());
                                    return http.build();
                                }"""),
                        text("chain:reading-it", """
                                Read that method top to bottom — it IS this section in code. CSRF ignored for API routes (token authentication carries no CSRF surface; topic 25 has the matrix). Sessions created only when something asks. Two mechanisms added explicitly: the X-API-Key filter, the resource server for Bearer tokens. Then the authorization rules — first matcher wins, which is why POST /api/demo/auth/apikeys (issuement, proving itself with a password) is permitted ABOVE the authenticated rule guarding the rest of the island. And note the INVERSION: a real service ends with anyRequest().authenticated() — deny by default. This guide ends permitAll because its product is readability; the island is the one protected slice. Copy this file into a real service and flip that last rule FIRST.

                                One cross-framework surprise this app hit and pinned: setting the resource-server key activated spring-grpc's OWN security autoconfigurations (they read the same properties), and topic 15's gRPC demos began answering UNAUTHENTICATED — fixed by excluding the two grpc security autoconfigs in application.yml. When you add security to a modular stack, run the WHOLE suite: other starters listen for the keys you just set."""),
                        code("chain:pom", "xml",
                                "server/pom.xml — the security baseline's three coordinates, versionless through the Boot BOM",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-security</artifactId>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.security</groupId>
                                    <artifactId>spring-security-test</artifactId>
                                    <scope>test</scope>
                                </dependency>"""),
                        text("chain:test-churn", """
                                The churn every Boot 3 developer braced for did not happen here — an empirical Boot 4 fact: the modularized @WebMvcTest slice no longer imports the security autoconfigurations, so the pre-existing controller slices kept passing untouched (no @WithMockUser sprinkle-fest, no addFilters=false flags). Security is tested where the chain is REAL — full-context @SpringBootTest + MockMvc, the pattern every auth-island test follows. Make the same split deliberately: slices test handler contracts, full contexts test the filter chain — and when you DO want security in a slice, @Import the SecurityConfig or opt in with @WithMockUser.""")),
                section("userdetailsservice-and-the-flow", "UserDetailsService and the flow",
                        "One interface, one question — 'who is this username?' — and the whole DaoAuthenticationProvider dance around it.",
                        text("uds:port", """
                                Spring Security never dictates WHERE users live; it defines ONE method: UserDetailsService.loadUserByUsername(String) → UserDetails. LDAP, JDBC, an in-memory map — identical from the framework's side. That is a PORT in this app's vocabulary (topic 32), and ports get adapters: the substrate is domain/auth's IdentityStore (UserPrincipal + findByUsername + API-key lookup), implemented by an in-memory adapter seeded with three users — alice (admin+staff), bob (staff), carol (reader) — whose bcrypt hashes are MINTED AT STARTUP from dev constants, never pasted as hash literals. A pasted literal cannot be read or rotated; a constant + encoder.encode() at construction keeps the seed honest and the verify path real:"""),
                        code("uds:seed", "java",
                                "guide/data/auth/InMemoryIdentityStore.java — the seeded substrate: constants in, real bcrypt out",
                                "server/src/main/java/guide/data/auth/InMemoryIdentityStore.java", """
                                public InMemoryIdentityStore(PasswordEncoder passwordEncoder) {
                                    seed("alice", List.of("admin", "staff"), ALICE_PASSWORD, passwordEncoder);
                                    seed("bob", List.of("staff"), BOB_PASSWORD, passwordEncoder);
                                    seed("carol", List.of("reader"), CAROL_PASSWORD, passwordEncoder);
                                }

                                private void seed(String username, List<String> roles, String password,
                                        PasswordEncoder passwordEncoder) {
                                    usersByUsername.put(username, new UserPrincipal(
                                            "usr_" + username, username, roles, passwordEncoder.encode(password)));
                                }"""),
                        text("uds:adapter", """
                                The bridge from that port to Spring's question is a two-line bean — map the domain record onto Spring's UserDetails (username, password HASH, roles — the .roles() helper adds the ROLE_ prefix the framework's matchers expect), and throw UsernameNotFoundException when the store has no answer. With a UserDetailsService bean and a PasswordEncoder bean on the context, Boot wires the DaoAuthenticationProvider for you; http.userDetailsService(...) is the explicit form if you prefer the chain method to state it. This app's bridge:"""),
                        code("uds:bean", "java",
                                "guide/config/SecurityConfig.java — the port becomes Spring's answer to 'who is this username'",
                                "server/src/main/java/guide/config/SecurityConfig.java", """
                                @Bean
                                UserDetailsService userDetailsService(IdentityStore identityStore) {
                                    return username -> identityStore.findByUsername(username)
                                            .<UserDetails>map(user -> User.withUsername(user.username())
                                                    .password(user.passwordHash())
                                                    .roles(user.roles().toArray(String[]::new))
                                                    .build())
                                            .orElseThrow(() -> new UsernameNotFoundException(username));
                                }"""),
                        text("uds:anatomy", """
                                The FULL anatomy of one authentication, the five moves behind every login this app performs. (1) A mechanism filter builds an UNAUTHENTICATED token — UsernamePasswordAuthenticationToken(username, password). (2) It hands it to the AuthenticationManager → ProviderManager → DaoAuthenticationProvider. (3) loadUserByUsername; UsernameNotFoundException becomes BadCredentialsException IMMEDIATELY. (4) passwordEncoder.matches(raw, storedHash) — the only place passwords are ever compared. (5) On success a NEW token — authenticated=true, principal + authorities — enters the SecurityContext. The five moves never vary; only the mechanism collecting the evidence does. For CUSTOM login shapes — this app's JSON POST — you run the same moves yourself, funneled through one use case so the check is identical everywhere:"""),
                        code("uds:usecase", "java",
                                "guide/domain/auth/AuthenticateCredentials.java — the five moves' domain half: one lookup, one hash check, one failure shape",
                                "server/src/main/java/guide/domain/auth/AuthenticateCredentials.java", """
                                public Result<UserPrincipal> execute(String username, String password) {
                                    if (username == null || username.isBlank() || password == null || password.isBlank()) {
                                        return new Failed<>(Failure.of(FailureKind.VALIDATION,
                                "username and password are both required"));
                                    }
                                    return identityStore.findByUsername(username)
                                            .filter(user -> passwordEncoder.matches(password, user.passwordHash()))
                                            .<Result<UserPrincipal>>map(user -> Result.success(user))
                                            .orElseGet(() -> new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                                "invalid credentials")));
                                }"""),
                        text("uds:oracle", """
                                Look hard at that failure: "invalid credentials" — ONE shape for unknown user and wrong password. An endpoint that distinguishes them is a USERNAME ORACLE: anyone can enumerate your accounts by message or timing. The demo's login test pins both failure modes to the same answer. The remaining trap is subtler: the filter(user -> matches(...)) short-circuits so bcrypt runs only for users who EXIST — a timing oracle (unknown users answer fast). High-security systems hash against a dummy password for unknown users to equalize; this guide accepts the simpler form. Know which trade you made.""")),
                section("the-session-model", "The session model",
                        "The server remembers you between requests — a server-side object plus a cookie that names it. Oldest trick on the web, still the right one for browsers.",
                        text("session:model", """
                                HTTP is stateless (topic 03); a login would be worthless if the NEXT request forgot it. The classic fix: the server stores a SESSION (an HttpSession — a map with an id and an idle timeout), and the client carries the session's ID in a cookie. Each request trades the cookie for the session; identity becomes a fact the SERVER holds. Spring Security stores exactly one thing there — the SecurityContext — via a SecurityContextRepository, and since Spring Security 6 that save is EXPLICIT after programmatic authentication. One config line declares which repository; this app uses the session-backed one:"""),
                        code("session:repo", "java",
                                "guide/config/SecurityConfig.java — where the authenticated context persists (and the session policy that governs creation)",
                                "server/src/main/java/guide/config/SecurityConfig.java", """
                                .sessionManagement(session -> session
                                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                                .securityContext(context -> context
                                        .securityContextRepository(new HttpSessionSecurityContextRepository()))"""),
                        text("session:creation", """
                                The creation POLICY: IF_REQUIRED (the default) creates a session only when something asks — the session demo asks, everything else here never does, which is why the JWT and key demos leave no server-side state. STATELESS is the strict alternative for pure-token APIs — a statement, not a protection. SECURITY on the model: the id must be unguessable and the cookie defended (next section) — and the id must ROTATE at login.

                                SESSION FIXATION is the attack that rotation kills: an attacker plants a KNOWN session id in the victim's browser (a link, a subdomain cookie) and waits — the victim logs in, the server binds the authenticated context to the ATTACKER'S known session, and the attacker presents the same id as a fully authenticated user. The defense: change the id at the moment privilege changes — attributes survive, the id does not. request.changeSessionId() exists for this; Spring Security does it automatically for filter-driven logins, and this app's programmatic login does it by hand, deliberately visible:"""),
                        code("session:fixation", "java",
                                "guide/presentation/security/SecuritySessionService.java — login's three servlet facts: rotate the id, build the context, save it explicitly",
                                "server/src/main/java/guide/presentation/security/SecuritySessionService.java", """
                                // fixation protection: a session that existed pre-login gets a NEW id
                                // (attributes survive; an attacker's copy of the old id dies here)
                                HttpSession existing = request.getSession(false);
                                if (existing != null) {
                                    request.changeSessionId();
                                }
                                SecurityContext context = SecurityContextHolder.createEmptyContext();
                                UsernamePasswordAuthenticationToken authentication =
                                        new UsernamePasswordAuthenticationToken(user, null, authorities(user));
                                authentication.setDetails("session");
                                context.setAuthentication(authentication);
                                securityContextRepository.saveContext(context, request, response);"""),
                        text("session:drift", """
                                Why do APIs drift away from sessions? SCALE and CLIENTS. A session is server-side state: every node behind a load balancer must see it (sticky sessions, replication, or a shared store like Redis — topic 20), and a stateless node is easier to scale and restart (topic 40). And a session cookie is a BROWSER concept — a script, a phone, another server has no cookie jar you can rely on. The drift is not "sessions are wrong"; it is "everything that is not a sticky-served browser wants evidence that travels in the request itself" — the next two demos' two most common shapes.""")),
                section("cookies-done-right", "Cookies done right",
                        "The session cookie is an ambient credential every request carries automatically — so its attributes are security configuration, not boilerplate.",
                        text("cookies:attributes", """
                                A cookie carrying a session id IS a bearer credential: whatever request it rides on is authenticated as that user — attached AUTOMATICALLY by the browser, its power and its attack surface, governed by ATTRIBUTES on Set-Cookie. Three matter. HTTPONLY: JavaScript cannot read it — XSS cannot exfiltrate what it cannot read; still a bug to fix (topic 25), but the session is not stolen. SAMESITE: which cross-site requests may carry the cookie — Lax (navigations yes, cross-site POSTs no) is most of the CSRF defense before a token exists; Strict breaks deep-links; None is the third-party-cookie business this app wants nothing of. SECURE: HTTPS only — without it, any plain-HTTP hop leaks the id. This app's three lines — CONFIGURATION, not code; the container writes the attributes:"""),
                        code("cookies:yaml", "yaml",
                                "server/src/main/resources/application.yml — the session cookie's whole security posture, three attributes",
                                "server/src/main/resources/application.yml", """
                                server:
                                  port: 8080
                                  # Topic 23 (authentication) — FLAGGED APPEND: the session cookie's
                                  # attributes, the whole cookies-done-right section in three lines.
                                  # http-only: JavaScript never reads the session id (XSS loses its
                                  # easiest escalation). same-site: lax — the cookie rides same-site
                                  # navigations but not cross-site POSTs (the CSRF dampener; the topic's
                                  # CSRF notes explain why lax + stateless APIs is the deliberate mix).
                                  # secure: env-toggled — true the moment TLS terminates the connection
                                  # (prod behind the reverse proxy), false only for plain-HTTP dev.
                                  servlet:
                                    session:
                                      cookie:
                                        http-only: true
                                        same-site: lax
                                        secure: ${SESSION_COOKIE_SECURE:false}"""),
                        text("cookies:proof", """
                                The login demo surfaces these live: its response reports the configured attributes, so posture is comparable to effect without a packet capture. Two production notes the dev defaults hide: SECURE=false exists only because local dev is plain HTTP — production sets it true (a cookie set without Secure over an http hop can be hijacked by anything on the wire); and the cookie's NAME and MAX-AGE are also configurable — a renamed session cookie buys a little obscurity, and a server-set MaxAge is how 'remember me' is built without a second token. Attributes first, obscurity never.

                                One connection to reopen in topic 25: SameSite=Lax does not fully close CSRF — so the moment your cookie-session surface grows beyond a demo, the CSRF machinery comes back on. That is why the baseline disables CSRF only for the token-authenticated /api routes.""")),
                section("the-login-flow", "The login flow",
                        "Credentials in, session out — the whole classic web login, one endpoint at a time, watchable in the demo.",
                        text("login:flow", """
                                The session island is three endpoints: POST /api/demo/auth/session/login (credentials JSON in; principal, roles, cookie posture out), GET /api/demo/auth/session/me (what the request's SecurityContext says — authenticated: principal + via; anonymous: the chain's 401, never the controller), POST /api/demo/auth/session/logout (session invalidated). Login runs the five-moves anatomy through the shared credential use case; the servlet-side service does the three facts the domain must not know. The me endpoint is deliberately a PROBE: anonymous (401), with the cookie (200), after logout (401 again) — the session's lifecycle in three calls, driven by the demo below."""),
                        demo("login:sessions-demo", "auth:sessions",
                                "The session login demo",
                                "Log alice, bob, or carol in (wrong password = the one 401 shape); read the authenticated principal back with the session cookie attached; watch the cookie's attributes; then log out and see /me refuse the dead session. The demo holds the session cookie the way a browser does."),
                        text("login:wire", """
                                The client half is deliberately boring — the guide's client never teaches, it windows. The login POST and the me GET share one guarded fetch helper (the same apiJson shape every demo in this guide uses); the only session-specific fact is that the BROWSER cookie jar does the carrying, so the component sends credentials along and never sees the cookie itself:"""),
                        code("login:client", "ts",
                                "client/src/ui/demos/auth/sessions_demo.tsx — the login call rides the shared helper; the cookie jar does the rest",
                                "client/src/ui/demos/auth/sessions_demo.tsx", """
                                const callLogin = async (username: string, password: string): Promise<void> => {
                                  const result = await authApiJson<LoginResponse>(
                                    SESSION_URL + '/login',
                                    {
                                      method: 'POST',
                                      headers: { 'Content-Type': 'application/json' },
                                      body: JSON.stringify({ username, password }),
                                      credentials: 'include',
                                    },
                                    parseLoginResponse,
                                  );
                                  if (!result.ok) {
                                    setPanel({ kind: 'error', failure: result.failure });
                                    return;
                                  }
                                  setPanel({ kind: 'idle' });
                                  setLoginResponse(result.value);
                                  void callMe();
                                };""")),
                section("api-keys", "API keys",
                        "The machine-to-machine credential: an opaque string, shown once, stored hashed, revoked like a door key.",
                        text("keys:model", """
                                An API key is a PASSWORD FOR PROGRAMS: a long random opaque string in a header (X-API-Key here) on every request — issued BY the server, often scoped, revocable independently of the account; unlike a session it carries no login state, the key IS the durable identity. The rules that separate a key system from a string in a database: RANDOM (32+ bytes from SecureRandom — never a UUID), PREFIXED (sdg_key_ here, ghp_ on GitHub — leaked keys are identifiable), SHOWN ONCE, HASHED AT REST (SHA-256 — passwords need bcrypt because they are low-entropy; a 256-bit random key does not), and BOUNDED (expiry, scopes, a revoke button that works). This app's material rules live in one utility — every opaque secret here (keys, refresh tokens, authorization codes) goes through the same two functions:"""),
                        code("keys:material", "java",
                                "guide/domain/auth/OpaqueTokens.java — bearer material born random, kept hashed",
                                "server/src/main/java/guide/domain/auth/OpaqueTokens.java", """
                                /** A fresh opaque bearer secret: prefix + 43 base64url chars (32 bytes). */
                                public static String generate(String prefix) {
                                    byte[] bytes = new byte[32];
                                    RANDOM.nextBytes(bytes);
                                    return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                                }

                                /** The at-rest form: hex SHA-256 of the token. */
                                public static String sha256Hex(String token) {
                                    try {
                                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                                        return java.util.HexFormat.of().formatHex(
                                digest.digest(token.getBytes(StandardCharsets.UTF_8)));
                                    } catch (NoSuchAlgorithmException unavailable) {
                                        throw new IllegalStateException("SHA-256 is unavailable in this JVM", unavailable);
                                    }
                                }"""),
                        text("keys:filter", """
                                The MECHANISM is one filter in the chain, slotted before the username/password machinery. Its contract is deliberately narrow: act only when an X-API-Key header is present AND nothing has authenticated yet (it adds a mechanism, never overrides another), hash the presented key, look it up, and populate the SecurityContext with the matching principal — or do nothing at all (a wrong key is indistinguishable from none; the endpoint's authorization rule answers the same 401 either way, no oracle):"""),
                        code("keys:filter-code", "java",
                                "guide/presentation/security/ApiKeyAuthenticationFilter.java — the whole mechanism: header → hash → lookup → SecurityContext",
                                "server/src/main/java/guide/presentation/security/ApiKeyAuthenticationFilter.java", """
                                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
                                    String presentedKey = request.getHeader(HEADER);
                                    Authentication existing = SecurityContextHolder.getContext().getAuthentication();
                                    if (presentedKey != null && !presentedKey.isBlank()
                                            && (existing == null || !existing.isAuthenticated())) {
                                        identityStore.findApiKeyByHash(OpaqueTokens.sha256Hex(presentedKey))
                                                .ifPresent(record -> authenticate(request, record, presentedKey));
                                    }
                                    chain.doFilter(request, response);
                                }"""),
                        text("keys:island", """
                                The island's four endpoints turn those rules into a runnable workflow: POST /api/demo/auth/apikeys (issue — password proof, raw key shown ONCE), GET /api/demo/auth/apikeys (the redacted list), DELETE /api/demo/auth/apikeys/{id} (the next request with the dead key 401s), and GET /api/demo/auth/apikeys/whoami — the proof the key IS a principal: called with X-API-Key it answers user, roles, via=api-key, because the filter above populated the same SecurityContext the session login writes and the resource server validates. Three mechanisms, one context — the chain's whole promise, visible in one endpoint."""),
                        demo("keys:api-keys-demo", "auth:api-keys",
                                "The API key workbench",
                                "Issue a key for alice, bob, or carol (shown once, prefixed sdg_key_); browse the redacted list; call whoami with the key attached and watch it identify the principal; then revoke it and watch whoami refuse the same key. Wrong-key and no-key look identical — no oracle.")),
                section("sessions-vs-keys-vs-tokens-preview", "Sessions vs keys vs tokens — a preview",
                        "Three credential shapes, three philosophies of where proof lives — before the two heavy mechanisms, the map.",
                        text("preview:three", """
                                Stand back before the JWT and OAuth sections double the vocabulary. A SESSION keeps the proof SERVER-SIDE: the client holds only an unguessable name for state you own — cheapest to verify, trivially revocable, but stateful and browser-shaped. An API KEY keeps the proof CLIENT-SIDE but OPAQUE: the caller holds a random secret, you hold its hash — no per-request state, valid forever until revoked; right for machines, wrong for browsers. A SIGNED TOKEN keeps the proof in a SELF-DESCRIBING artifact: claims you minted and signed, verifiable from the signature ALONE — no session, no lookup — at the cost of exactly that: you cannot un-issue a verified token, only wait out its expiry or change the key.

                                Statelessness is WHY tokens won modern APIs: a resource server validating a Bearer JWT needs no database, no session store — the signing key and nothing else (topic 40's scale story). The PRICE is revocation: a valid-signature token is valid until exp. Short expiry bounds the window; refresh tokens (long-lived, opaque, server-resolvable) buy renewal without re-typed passwords. And OAuth is not a fourth credential — a PROTOCOL for issuing the second and third when the user is a human on a device you do not control.""")),
                section("jwts-and-the-resource-server", "JWTs and the resource server",
                        "header.payload.signature — claims, a signature over them, and a resource server that trusts only the math.",
                        text("jwt:anatomy", """
                                A JSON Web Token is three base64url segments joined by dots. HEADER ({"alg":"HS256","typ":"JWT"}). PAYLOAD (the CLAIMS: iss, sub, aud, exp, nbf, iat, jti, plus yours — this app adds roles). SIGNATURE (HS256(secret, header + "." + payload), or the asymmetric equivalents). The first two segments are BASE64, NOT ENCRYPTION — anyone can decode them; a JWT is SIGNED, not secret, so never put anything in claims you would not log. The signature's one job: bytes cannot change without the change being detectable — the demo's tampered-token test.

                                This app MINTS its own (a real deployment usually receives them from an issuer — the OAuth section is that story). The claims are deliberately minimal — sub, roles, jti, iat, exp, 15 minutes by default, the TTL that makes the refresh machinery necessary:"""),
                        code("jwt:minter", "java",
                                "guide/domain/auth/JwtMinter.java — minting: standard claims, short life, HS256 with the config secret",
                                "server/src/main/java/guide/domain/auth/JwtMinter.java", """
                                JWTClaimsSet claims = new JWTClaimsSet.Builder()
                                        .subject(user.username())
                                        .claim("roles", user.roles())
                                        .jwtID(UUID.randomUUID().toString())
                                        .issueTime(Date.from(now))
                                        .expirationTime(Date.from(expiresAt))
                                        .build();
                                SignedJWT jwt = new SignedJWT(
                                        new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
                                jwt.sign(new MACSigner(secretHmacKey));"""),
                        text("jwt:algorithms", """
                                HS256 vs RS256/ES256 is the FIRST real architectural fork in JWT land. HS256 (HMAC-SHA256) is SYMMETRIC: one secret signs and verifies — simple, fast, and whoever can verify can also FORGE. Fine when issuer and verifier are the same app (this demo). RS256/ES256 are ASYMMETRIC: a private key signs, a PUBLIC key verifies — verifiers get zero minting power, the issuer publishes its public key (JWKS), any number of resource servers verify without being able to forge. The moment a SECOND service must trust your tokens, HS256 becomes a liability.

                                The VALIDATION CHAIN is the half everyone gets wrong. The resource server does NOT 'decode' the token — it VERIFIES it: signature against the configured key (the alg must be the one YOU configured, never the one the TOKEN suggests — 'alg: none' dies here), then the claims validators (exp, nbf, iss, aud), and only then an Authentication whose principal IS the verified Jwt. The demo's protected endpoint shows the difference: its claims are TRUSTED because the chain checked them; the mint response's decoded panel is a PREVIEW anyone could produce:"""),
                        code("jwt:protected", "java",
                                "guide/presentation/demo/auth/JwtDemoController.java — the resource-server half: the principal IS the verified token",
                                "server/src/main/java/guide/presentation/demo/auth/JwtDemoController.java", """
                                @GetMapping(value = "/api/demo/auth/jwt/protected", produces = MediaType.APPLICATION_JSON_VALUE)
                                public Map<String, Object> protectedResource() {
                                    Object principal = org.springframework.security.core.context.SecurityContextHolder
                                            .getContext().getAuthentication().getPrincipal();
                                    if (principal instanceof Jwt jwt) {
                                        return Map.of(
                                                "message", "the resource server verified this Bearer token — the claims below are TRUSTED",
                                                "sub", jwt.getSubject(),
                                                "roles", jwt.getClaimAsStringList("roles"),
                                                "jti", jwt.getId(),
                                                "issuedAt", jwt.getIssuedAt().toString(),
                                                "expiresAt", jwt.getExpiresAt().toString(),
                                                "via", "jwt");
                                    }
                                    // …
                                }"""),
                        text("jwt:config", """
                                Wiring the verifying side is one yaml key plus one chain line. The key feeds a JwtDecoder — Boot can build it from the property alone; this app builds it as an explicit bean for ONE teaching reason: the stock timestamp validator tolerates 60 seconds of clock skew, and this demo mints tokens that die in seconds, so the decoder narrows the skew to 1s — the tour watches a token actually expire. The secret is the app's single HS256 source of truth (the minter reads the same property): env-overridable, clearly-marked DEV default — the secrets discipline topic 25 formalizes:"""),
                        code("jwt:yaml", "yaml",
                                "server/src/main/resources/application.yml — the resource server's symmetric key (env-first, marked dev default)",
                                "server/src/main/resources/application.yml", """
                                security:
                                  oauth2:
                                    resourceserver:
                                      jwt:
                                        secret-key: ${SDG_JWT_SECRET:dev-only-sdg-jwt-hs256-secret-3b9d6f01c2a8e45d7f0b}"""),
                        code("jwt:decoder", "java",
                                "guide/config/AuthDemoConfig.java — the decoder bean: HS256 from the same secret, skew narrowed to 1s",
                                "server/src/main/java/guide/config/AuthDemoConfig.java", """
                                @Bean
                                org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder(
                                        @Value("${spring.security.oauth2.resourceserver.jwt.secret-key}") String hs256Secret) {
                                    org.springframework.security.oauth2.jwt.NimbusJwtDecoder decoder =
                                            org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withSecretKey(
                                                    new javax.crypto.spec.SecretKeySpec(
                                                            hs256Secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                                            "HmacSHA256"))
                                                    .build();
                                    decoder.setJwtValidator(
                                            new org.springframework.security.oauth2.jwt.JwtTimestampValidator(
                                                    java.time.Duration.ofSeconds(1)));
                                    return decoder;
                                }"""),
                        text("jwt:refusals", """
                                What refusal looks like on the wire — the resource server talking, not your error taxonomy: an expired or tampered Bearer gets 401 with WWW-Authenticate: Bearer error="invalid_token" (RFC 6750's challenge vocabulary); a MISSING bearer gets the plain challenge. The demo's expiry hook (ttlSeconds=1) makes the first reproducible in two seconds, and the tamper test forges it by flipping the signature's last character. NEVER trust client-side decoding as validation — the mint response's decoded panel and the protected endpoint's claims answer DIFFERENT questions (what does this token SAY vs IS this token true), and only the second is security."""),
                        demo("jwt:demo", "auth:jwt",
                                "The JWT workbench",
                                "Mint a 15-minute (or 1-second) token for alice, bob, or carol; read the decoded-claims preview; then call the protected resource with Authorization: Bearer — or with a tampered copy, or after the expiry — and watch the resource server's three answers: verified claims, invalid_token, challenge.")),
                section("the-oauth-authorization-code-flow", "The OAuth authorization-code flow",
                        "Four roles, one dance, a code instead of a token, and PKCE closing the last hole — taught here by an authorization server living inside the demo.",
                        text("oauth:roles", """
                                OAuth 2.0 solves a NARROW problem with heavy machinery: letting a CLIENT APP act for a USER at a RESOURCE SERVER, without the client holding the user's password. The roles: RESOURCE OWNER (alice), CLIENT (the app wanting access), AUTHORIZATION SERVER (the identity provider — Keycloak, Auth0, or this demo's in-app teaching server), RESOURCE SERVER (the API). Passwords stay between owner and authorization server; everyone else gets time-limited, scoped tokens.

                                The AUTHORIZATION CODE flow: (1) the client sends the owner to the authorization server — GET /authorize?response_type=code&client_id&redirect_uri&state&code_challenge; (2) the server authenticates the owner and asks CONSENT; (3) the owner allows, and the server redirects to the client's redirect_uri with a single-use, ~60-second CODE and the state echoed; (4) the CLIENT — server-to-server now — POSTs the code to /token and receives access (+ refresh) tokens. Why two steps? The REDIRECT crosses the browser, the most hostile channel there is — so what crosses it is a useless-by-itself, single-use, seconds-long code; tokens travel only on the server-to-server leg. The demo's authorize endpoint IS steps (2)+(3) as one JSON POST (real deployments show real pages; the specimen keeps everything curl-able):"""),
                        code("oauth:authorize", "java",
                                "guide/domain/auth/AuthorizeClient.java — the decision endpoint: known client, allowlisted redirect, PKCE required, consent asked, code minted",
                                "server/src/main/java/guide/domain/auth/AuthorizeClient.java", """
                                if (!clients.redirectAllowed(request.clientId(), request.redirectUri())) {
                                    return new Failed<>(Failure.of(FailureKind.VALIDATION,
                                "redirect_uri is not registered for this client (open-redirect guard)"));
                                }
                                if (request.codeChallenge() == null || request.codeChallenge().isBlank()
                                        || !"S256".equals(request.codeChallengeMethod())) {
                                    return new Failed<>(Failure.of(FailureKind.VALIDATION,
                                "PKCE required: code_challenge + method S256 (public clients must prove key possession)"));
                                }
                                if (!request.consent()) {
                                    return new Failed<>(Failure.of(FailureKind.FORBIDDEN,
                                "access_denied — the resource owner declined consent"));
                                }"""),
                        text("oauth:state", """
                                STATE is the client's CSRF token for the dance: a random string put in at step 1 and DEMANDED back in step 3's redirect. Without it, an attacker who tricks the client into consuming an attacker-initiated redirect can log the victim into the attacker's account; with it, a redirect the client did not initiate fails the echo check and dies.

                                PKCE (proof of key for code exchange, 'pixy') closes the hole state cannot: on phones, desktops, and any SPA, step 4's client secret cannot be kept secret. So the client PROVES possession: it invents a random code_verifier, sends its S256 hash as the code_challenge, presents the VERIFIER at /token. The server recomputes S256(verifier) and compares (constant-time — the webhooks topic's lesson). An attacker who intercepts the code holds a code whose challenge matches a verifier they do not have; the exchange fails:"""),
                        code("oauth:pkce", "java",
                                "guide/domain/auth/ExchangeAuthorizationCode.java — the exchange: single-use first, everything re-verified, PKCE last",
                                "server/src/main/java/guide/domain/auth/ExchangeAuthorizationCode.java", """
                                // consume FIRST: single-use means the code is spent even when everything after fails
                                AuthorizationCodeStore.StoredAuthorization stored =
                                        codeStore.consume(OpaqueTokens.sha256Hex(code));
                                if (stored == null) {
                                    return new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                                "unknown or already-used code — codes are single-use"));
                                }
                                // …
                                String expected = OpaqueTokens.s256Challenge(codeVerifier == null ? "" : codeVerifier);
                                if (!OpaqueTokens.challengeMatches("S256:" + expected, "S256:" + stored.codeChallenge())) {
                                    return new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                                "PKCE verification failed — verifier does not match the challenge"));
                                }
                                // the pair's access side is minted passwordless: the CODE was the proof
                                return issueTokenPair.forAuthorizedUser(stored.username());"""),
                        text("oauth:real-deployments", """
                                Read the demo's /token response and you have the OAuth wire contract: access_token, token_type Bearer, expires_in, refresh_token — the same fields every IdP returns. REAL deployments never build this: you run Keycloak, Auth0, or the corporate OIDC server, and your app becomes a CLIENT (spring-boot-starter-oauth2-client — the code flow WITH PKCE, autoconfigured from issuer-uri + client-id/secret) and/or a RESOURCE SERVER (this app's starter, validating the IdP's tokens). The value of having built the teaching version once: when the flow misbehaves in production — state mismatch, PKCE failure, code already used — you know WHICH role failed to keep WHICH promise. The demo's refresh_token grant delegates to the next section's rotation machinery — the same machine, the same idea."""),
                        demo("oauth:demo", "auth:oauth",
                                "The authorization-code dance",
                                "Play all four roles from one console: the demo generates the PKCE verifier/challenge pair and a state, 'sends the owner' to the authorize endpoint (login simulation + consent), shows the redirect arriving with code and state, verifies the echo, and exchanges at /token — with the option to attack it: wrong verifier, replayed code, declined consent, unregistered redirect.")),
                section("sessions-vs-jwt-vs-api-keys-deciding", "Sessions vs JWT vs API keys — deciding",
                        "The honest decision table: no best credential, only fitted ones — and the four columns that fit them.",
                        text("deciding:table", """
                                The decision is four questions, in order. WHO IS THE CLIENT? Browser → sessions; machine you credentialed → API keys; third-party app or any human you do not own → OAuth-issued JWTs. HOW MUST REVOCATION WORK? Instant, per-user → sessions or keys; minutes of drift tolerable → short-expiry JWTs + refresh revocation; nothing short of key rotation revokes a long-lived JWT — do not issue those. WHAT DOES SCALE DEMAND? Sticky/shared-session infrastructure is real cost (topic 40); stateless-every-node wants tokens. WHAT IS THE CSRF SURFACE? Cookie-carried proof inherits CSRF duty; header-carried proof is CSRF-immune — a cross-site page cannot set your headers. THAT is why APIs converged on Bearer: not safer, but stateless and header-carried.

                                Real systems COMBINE, this app included: sessions, keys, and JWTs behind ONE chain — one SecurityContext; ONE mechanism per request, decided by what the caller can carry. The anti-patterns the table kills: JWT-in-localStorage for a browser app (XSS reads it); 10-year keys with no revoke path; sessions for a phone-facing API; and "we do not need revocation" — always said before the first leaked key, never after.""")),
                section("password-hashing", "Password hashing",
                        "The last line of defense for every credential you will ever store — and the reason MD5 and SHA are crimes.",
                        text("hashing:why", """
                                Passwords must be stored UNRECOVERABLY. Not encrypted (a key that decrypts them leaks them), HASHED. And not with a fast hash: SHA-256(password) is BROKEN for passwords — not because SHA-256 is weak but because it is FAST (billions of GPU attempts against a few billion human-memorable strings). PASSWORD hashing needs DELIBERATELY SLOW and SALTED: slow so bulk brute force costs years; salted so identical passwords hash differently — no rainbow tables. The demo proves the salt: same password twice, two different 60-char strings.

                                BCRYPT (1999) remains the industry default and is what this app uses: cost-parameterized (2^cost rounds; each +1 DOUBLES the work), salt built in. ARGON2ID is the modern answer (prose here, no extra dependency): it also demands MEMORY per attempt, killing the GPU/ASIC parallelism bcrypt tolerates. New system today: Argon2id is the defensible choice; bcrypt at cost 10-12 remains acceptable — the crime is MD5/SHA/plaintext, not bcrypt. The anatomy, dissected live by the demo's compare endpoint:"""),
                        code("hashing:dissect", "java",
                                "guide/domain/auth/PasswordHashLab.java — the format, parsed: $2a$10$ + 22-char salt + 31-char hash body",
                                "server/src/main/java/guide/domain/auth/PasswordHashLab.java", """
                                String version = hash.substring(1, 3);
                                int cost = Integer.parseInt(hash.substring(4, 6));
                                return new HashAnatomy("bcrypt", version, cost,
                                        hash.substring(7, 29),
                                        hash.substring(29),
                                        List.of(
                                "version " + version + " — the algorithm revision (2a/2b/2y differ on edge cases)",
                                "cost " + cost + " — 2^cost bcrypt rounds; +1 doubles the work",
                                "salt — 22 base64 chars, generated per hash: same password, different hash every time",
                                "hash body — 31 chars, derived from password+salt; only this half is the secret"));"""),
                        text("hashing:timing", """
                                The comparison is where implementations quietly become vulnerable — the SAME timing lesson the webhooks topic taught for HMAC, one layer down. A naive early-exit compare leaks HOW MANY leading characters matched; PasswordEncoder.matches never does that. The deeper property: bcrypt's runtime does not depend on the ANSWER — CORRECT and WRONG passwords execute the same full schedule, so the clock cannot tell you how CLOSE you were. The demo makes it measurable: verify reports elapsedMs for both verdicts, same order of magnitude — that equality IS the security property. (Where hashes ARE compared directly, it stays constant-time — MessageDigest.isEqual in OpaqueTokens.challengeMatches.)

                                COST TUNING is living, not set-and-forget: the right cost is 'as slow as your login tolerates' — roughly 100ms at p99. Migration is built into the format: bcrypt embeds the cost IN the hash, so raising the default verifies old hashes at their old cost and re-hashes at the next successful login."""),
                        demo("hashing:demo", "auth:hashing",
                                "The PasswordEncoder lab",
                                "Hash a password at any bcrypt cost and watch the milliseconds; hash the same password twice and compare the salts; then VERIFY a correct and a wrong password against the same hash and put their elapsed times side by side — roughly equal, on purpose. The compare view dissects any hash into version, cost, salt, and body.")),
                section("refresh-tokens-and-rotation", "Refresh tokens and rotation",
                        "Short-lived access, long-lived renewal — and the theft detector hiding in the rotation.",
                        text("refresh:split", """
                                The ACCESS/REFRESH split resolves a tension the JWT section left open: users want weeks; a stolen credential should die in minutes. The ACCESS token is short (15 minutes — the statelessly-validated JWT, impossible to revoke); the REFRESH token is long (days) but OPAQUE — meaningful only to the issuer, validated by a server-side lookup like a session. Every API call presents only the access token; expiry triggers one refresh call for a fresh pair. Blast radius: a stolen ACCESS token dies in ≤15 minutes by arithmetic; a stolen REFRESH token is a full session credential appearing on exactly ONE endpoint. At rest it follows the API-key rule: SHA-256 hashed.

                                ROTATION-ON-USE makes the refresh token single-use like the OAuth code: every exchange spends the PRESENTED token and mints a new one in the same FAMILY. Benefit: the theft detector. A token ALREADY SPENT being replayed means two parties hold it — one is a thief — so the only safe move is revoking the ENTIRE FAMILY: the honest user re-authenticates, the thief loses everything simultaneously. The policy, as the app runs it:"""),
                        code("refresh:reuse", "java",
                                "guide/domain/auth/ExchangeRefreshToken.java — the theft detector: a spent token re-presented kills its whole lineage",
                                "server/src/main/java/guide/domain/auth/ExchangeRefreshToken.java", """
                                if (stored.familyRevoked()) {
                                    return new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                                "refresh family is revoked — log in again"));
                                }
                                if (stored.state() != RefreshFamilyStore.StoredRefreshToken.TokenState.ACTIVE) {
                                    // reuse: this token was already exchanged once. Revoke the lineage.
                                    refreshFamilies.revokeFamily(stored.familyId());
                                    return new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                                "refresh token reuse detected — family revoked (possible theft)"));
                                }"""),
                        text("refresh:store", """
                                The STORE's shape is the quiet design decision: spent tokens are NOT deleted. A rotated-away token must stay resolvable — deleting it would turn the replay into a mere 'unknown token', losing the theft signal and the family handle. So the in-memory family store keeps every generation with its state (ACTIVE/ROTATED), revocation flags the family, and eviction is oldest-family-first at a ceiling — a bounded teaching store whose observability endpoint (/api/demo/auth/refresh/families) shows generations and revoked flags while hashes never leave. The rotation write itself:"""),
                        code("refresh:rotate", "java",
                                "guide/data/auth/InMemoryRefreshFamilyStore.java — rotate: mark the spent generation, append the next",
                                "server/src/main/java/guide/data/auth/InMemoryRefreshFamilyStore.java", """
                                List<Family.Generation> generations = new ArrayList<>();
                                for (Family.Generation generation : family.generations()) {
                                    generations.add(generation.tokenHash().equals(spentTokenHash)
                                            ? new Family.Generation(generation.tokenHash(),
                                StoredRefreshToken.TokenState.ROTATED, generation.issuedAt())
                                            : generation);
                                }
                                generations.add(new Family.Generation(nextTokenHash,
                                StoredRefreshToken.TokenState.ACTIVE, at));"""),
                        demo("refresh:demo", "auth:refresh",
                                "The refresh rotation console",
                                "Issue a pair (a family is born); exchange the refresh token and watch the generation count tick while the presented token becomes spent; then run the theft: present the SPENT token again and watch the whole family die — revoked flag, and even the current token answers 401. The families view shows every lineage's timeline.")),
                section("terminal-companion", "Terminal companion",
                        "All six flows by curl: a login session, an API key's life, a JWT's death by expiry, the OAuth dance with PKCE, the hashing lab, and the reuse-revocation — plain curl only: every response is small enough to read as printed.",
                        text("terminal:intro", """
                                With the stack running (server on :8080), the tour walks every mechanism in this topic end to end. Three seeded users: alice/alice-demo-pass-01 (admin+staff), bob/bob-demo-pass-01 (staff), carol/carol-demo-pass-01 (reader) — dev fixtures, openly documented on purpose (the hashing section explains why that openness is itself a decision you make consciously in dev and never in prod)."""),
                        code("terminal:sessions", "bash",
                                "the session: login (cookie jar), me, logout, me again",
                                null, """
                                # login — the cookie jar plays the browser
                                curl -s -c /tmp/sdg.jar -X POST localhost:8080/api/demo/auth/session/login \\
                                  -H 'content-type: application/json' \\
                                  -d '{"username":"alice","password":"alice-demo-pass-01"}'
                                # → {"user":{"username":"alice","roles":["admin","staff"]},
                                #     "session":{"cookie":{"httpOnly":true,"sameSite":"lax", …}},
                                #     "via":"session"}
                                curl -s -b /tmp/sdg.jar localhost:8080/api/demo/auth/session/me
                                # → the "user" object: {"username":"alice", …}
                                curl -s -b /tmp/sdg.jar -X POST localhost:8080/api/demo/auth/session/logout
                                curl -s -o /dev/null -w '%{http_code}\\n' -b /tmp/sdg.jar \\
                                  localhost:8080/api/demo/auth/session/me
                                # → 401 — the session is gone; wrong password? same 401 shape, no oracle"""),
                        code("terminal:apikeys", "bash",
                                "the API key: issue (once), use, list, revoke, refuse",
                                null, """
                                KEY=$(curl -s -X POST localhost:8080/api/demo/auth/apikeys \\
                                  -H 'content-type: application/json' \\
                                  -d '{"username":"bob","password":"bob-demo-pass-01"}' \\
                                  | python3 -c 'import json,sys; print(json.load(sys.stdin)["apiKey"])')
                                # → sdg_key_… (43 chars — the ONE response that ever shows it)
                                curl -s -H "X-API-Key: $KEY" localhost:8080/api/demo/auth/apikeys/whoami
                                # → {"user":{"username":"bob","roles":["staff"]},"via":"api-key"}
                                curl -s -H "X-API-Key: $KEY" localhost:8080/api/demo/auth/apikeys
                                # → {"keys":[{"redacted":"sdg_key_9f3…", …}]} — prefixes only, never key material
                                KEY_ID=$(curl -s -H "X-API-Key: $KEY" localhost:8080/api/demo/auth/apikeys \\
                                  | python3 -c 'import json,sys; print(json.load(sys.stdin)["keys"][0]["id"])')
                                curl -s -X DELETE -H "X-API-Key: $KEY" localhost:8080/api/demo/auth/apikeys/$KEY_ID
                                curl -s -o /dev/null -w '%{http_code}\\n' -H "X-API-Key: $KEY" \\
                                  localhost:8080/api/demo/auth/apikeys/whoami
                                # → 401 — revoked keys are dead keys"""),
                        code("terminal:jwt", "bash",
                                "the JWT: mint, call, tamper, expire",
                                null, """
                                TOKEN=$(curl -s -X POST localhost:8080/api/demo/auth/jwt/token \\
                                  -H 'content-type: application/json' \\
                                  -d '{"username":"carol","password":"carol-demo-pass-01"}' \\
                                  | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
                                curl -s -H "Authorization: Bearer $TOKEN" \\
                                  localhost:8080/api/demo/auth/jwt/protected
                                # → {"sub":"carol","roles":["reader"],"via":"jwt", …} — claims are TRUSTED here
                                TAMPERED="${TOKEN%?}A"   # flip the signature's last character
                                curl -s -i -H "Authorization: Bearer $TAMPERED" \\
                                  localhost:8080/api/demo/auth/jwt/protected | grep -i www-authenticate
                                # → WWW-Authenticate: Bearer error="invalid_token" — the signature caught it
                                SHORT=$(curl -s -X POST localhost:8080/api/demo/auth/jwt/token \\
                                  -H 'content-type: application/json' \\
                                  -d '{"username":"carol","password":"carol-demo-pass-01","ttlSeconds":1}' \\
                                  | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
                                sleep 2 && curl -s -o /dev/null -w '%{http_code}\\n' \\
                                  -H "Authorization: Bearer $SHORT" localhost:8080/api/demo/auth/jwt/protected
                                # → 401 — expiry, on schedule"""),
                        code("terminal:oauth", "bash",
                                "the dance: PKCE pair, authorize, exchange — then the attack on the verifier",
                                null, """
                                VERIFIER="my-random-verifier-string-01"
                                CHALLENGE=$(printf %s "$VERIFIER" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=')
                                RESP=$(curl -s -X POST localhost:8080/api/demo/auth/oauth/authorize \\
                                  -H 'content-type: application/json' \\
                                  -d "{\"responseType\":\"code\",\"clientId\":\"sdg-console\",
                                       \\"redirectUri\\":\\"http://localhost:5176/oauth/callback\\",
                                       \\"state\\":\\"s3ntinel\\",\\"codeChallenge\\":\\"$CHALLENGE\\",
                                       \\"codeChallengeMethod\\":\\"S256\\",
                                       \\"username\\":\\"alice\\",\\"password\\":\\"alice-demo-pass-01\\",
                                       \\"consent\\":true}")
                                echo "$RESP"   # the client checks the state echo in the raw body
                                CODE=$(echo "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["code"])')
                                curl -s -X POST localhost:8080/api/demo/auth/oauth/token \\
                                  -H 'content-type: application/json' \\
                                  -d "{\"grantType\":\"authorization_code\",\"code\":\"$CODE\",
                                       \\"redirectUri\\":\\"http://localhost:5176/oauth/callback\\",
                                       \\"clientId\\":\\"sdg-console\\",\\"codeVerifier\\":\\"$VERIFIER\\"}"
                                # → {"grant":"authorization_code", …} — and re-running it: 401, codes are single-use"""),
                        code("terminal:hashing", "bash",
                                "the lab: cost, salt, and the timing evidence",
                                null, """
                                HASH=$(curl -s -X POST localhost:8080/api/demo/auth/hashing/hash \\
                                  -H 'content-type: application/json' \\
                                  -d '{"password":"correct horse","cost":10}' \\
                                  | python3 -c 'import json,sys; print(json.load(sys.stdin)["hash"])')
                                curl -s "localhost:8080/api/demo/auth/hashing/compare?hash=$HASH"
                                # → {"version":"2a","cost":10,"salt":"22 chars, unique every time", …}
                                curl -s -X POST localhost:8080/api/demo/auth/hashing/verify \\
                                  -H 'content-type: application/json' \\
                                  -d "{\"password\":\"correct horse\",\"hash\":\"$HASH\"}"
                                # → {"matches":true, "elapsedMs":62.1}
                                curl -s -X POST localhost:8080/api/demo/auth/hashing/verify \\
                                  -H 'content-type: application/json' \\
                                  -d "{\"password\":\"correct horses\",\"hash\":\"$HASH\"}"
                                # → {"matches":false,"elapsedMs":61.8} — wrong costs the SAME: no timing oracle"""),
                        code("terminal:refresh", "bash",
                                "the theft story: issue, rotate, replay the spent token, watch the family die",
                                null, """
                                RT=$(curl -s -X POST localhost:8080/api/demo/auth/refresh/issue \\
                                  -H 'content-type: application/json' \\
                                  -d '{"username":"alice","password":"alice-demo-pass-01"}' \\
                                  | python3 -c 'import json,sys; print(json.load(sys.stdin)["refreshToken"])')
                                NEW=$(curl -s -X POST localhost:8080/api/demo/auth/refresh/exchange \\
                                  -H 'content-type: application/json' \\
                                  -d "{\"refreshToken\":\"$RT\"}" \\
                                  | python3 -c 'import json,sys; print(json.load(sys.stdin)["refreshToken"])')
                                # the replay — the SPENT token again:
                                curl -s -X POST localhost:8080/api/demo/auth/refresh/exchange \\
                                  -H 'content-type: application/json' \\
                                  -d "{\"refreshToken\":\"$RT\"}"
                                # → {"error":{"code":"…","message":"refresh token reuse detected — family revoked (possible theft)", …}}
                                curl -s -X POST localhost:8080/api/demo/auth/refresh/exchange \\
                                  -H 'content-type: application/json' \\
                                  -d "{\"refreshToken\":\"$NEW\"}"
                                # → …"message":"refresh family is revoked" — even the CURRENT token dies with the lineage
                                curl -s localhost:8080/api/demo/auth/refresh/families
                                # → the LAST family in the list: "revoked":true — the observability view"""),
                        text("terminal:close", """
                                Six curls later you have exercised every mechanism the topic built: a session born and killed by its cookie, a key issued once and revoked, a token verified-tampered-expired, a dance completed with state and PKCE intact, a hash whose wrong answers cost the same as right ones, and a family revoked by its own replay. The machinery lives in domain/auth (use cases, tokens, minter), data/auth (three in-memory stores), presentation/security (the key filter, the session glue), and config/ (the baseline chain). Topic 24 asks the next question on the same substrate — authenticated as alice, WHAT MAY SHE DO — and answers it with roles, permissions, and method security.""")));
    }
}
