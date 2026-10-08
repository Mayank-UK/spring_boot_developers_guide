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
 * Topic 25 — Security. Group auth-security, order 3. The app-hardening
 * topic, built on topic 23's baseline chain and topic 24's authorization
 * island: response headers, CORS as practiced, Bucket4j rate limiting with
 * a 429+Retry-After contract, the whole injection family, CSRF/SSRF
 * decisions, secrets discipline, the OWASP API Top 10 mapped to THIS app,
 * and a crypto-shred PII vault with an append-only erasure log. Two demos
 * run live against this app's own chains and endpoints.
 */
public final class SecurityTopic {

    private SecurityTopic() {
    }

    public static Topic topic() {
        return new Topic("security", "Security",
                "Hardening the whole app: security headers, CORS in practice, "
                        + "rate limiting with Bucket4j, the injection family, CSRF and SSRF decisions, "
                        + "secrets and config discipline, the OWASP API Top 10 mapped to this very "
                        + "app, and a crypto-shred PII vault — every defense runnable, every gap "
                        + "admitted.",
                TopicGroupId.AUTH_SECURITY, 3,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("the-threat-model-habit", "The threat-model habit",
                        "Before the first header and the first rate limit: five minutes of deciding what you are protecting, from whom, and what you will deliberately not defend.",
                        text("sec:threat:triage", """
                                SECURITY IS RISK MANAGEMENT, not a feature list. Every practice in this topic — headers, CORS, rate limits, allowlists, crypto-shred — is a MITIGATION for a named threat, and a mitigation without its threat is cargo cult. Start every system the same way, five minutes, three lists:

                                ASSETS — what loss actually hurts? For this guide: teaching content (low value), demo substrates (resettable), the JWT signing secret and API keys (REAL credentials — forgeable identity), any PII a demo stores. VULNERABILITIES — where could assets be reached without authorization? Every input boundary qualifies: request bodies, subscription URLs the server will later FETCH, parameters that reach SQL. THREATS — WHO exploits which vulnerability against which asset? A bored user pasting a UNION into the sql-safety demo; a hostile webhook subscriber aiming our HTTP client at the cloud metadata service; a stolen browser tab replaying cookie-authenticated state changes.

                                The triage KILLS work as much as it creates it. This app serves teaching JSON on plain HTTP in dev: TLS termination belongs to the reverse proxy (topic 37), so HSTS is configured but honestly absent on HTTP responses — measured, not claimed. And a teaching API's threat model is narrow: availability matters, confidentiality barely does — EXCEPT where topics deliberately create secrets. Hence the protected surfaces here are ISLANDS: precise, named, enforced by the chain, while the guide stays open."""),
                        text("sec:threat:habit", """
                                Make the triage a HABIT, not a document: before every bean, what does this component TRUST, what does it EXPOSE, and what is the BLAST RADIUS when it fails. You watched the habit work in topic 23's discovery — adding a resource-server key activated spring-grpc's security autoconfigs, and a whole-suite run caught a POSTURE CHANGE nobody decided on.

                                One habit-rule before the toolbox: DEFENSE IN DEPTH ASSUMES THE LAYER ABOVE ALREADY FAILED. Parameterized queries because input validation has bugs (topic 17 proved the sandbox's UNION escape live); least-privilege database roles because queries have bugs; per-subject encryption keys because deletion has bugs (backups keep rows). Not paranoia — arithmetic over how often software ships with bugs.""")),
                section("secure-headers-via-spring-security", "Secure headers via Spring Security",
                        "The helmet equivalent: five response headers that blunt browser-side attacks, written by the filter chain you already have — and a second, deliberately relaxed chain to prove each one's absence.",
                        text("sec:headers:why", """
                                Response headers are instructions to the BROWSER about how to treat a response — and because the browser enforces them, they defend against attacks your server cannot see. Spring Security's HeadersWriterFilter writes a default set into EVERY response crossing a chain, and the DSL makes the policy explicit. This app's block, added to topic 23's baseline:

                                Read the five directives as five attacks: nosniff stops MIME confusion (a JSON endpoint tricked into executing as script); frameOptions DENY stops clickjacking (your page invisible-inside-an-attacker-page); the Content-Security-Policy is the browser-enforced allowlist — this app serves Swagger UI and a dev GraphiQL from its own origin, so 'self' is the honest lock (a pure JSON API could drop to default-src 'none'); Referrer-Policy trims the Referer to origin-plus-path, keeping tokens-in-URLs at home; HSTS tells the browser this origin is HTTPS-only for a year — and carries this topic's honesty badge: the writer emits it ONLY over secure connections, so the HTTP dev responses (including the live demo below) show it configured-but-absent, exactly as a browser on HTTP would experience."""),
                        code("sec:headers:block", "java",
                                "guide/config/SecurityConfig.java — the headers block on the main chain (topic 25's flagged extension of the baseline)",
                                "server/src/main/java/guide/config/SecurityConfig.java", """
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
                                                .policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))"""),
                        text("sec:headers:lab", """
                                The demo below is TWO chains, not a slide: the main chain answers /api/demo/security/lab/probe; a second, deliberately insecure chain (matched only to /api/demo/security/lab/insecure/**) disables every header writer and serves the comparison probe. The lab endpoint SELF-FETCHES both probes over the loopback socket and reports the MEASURED headers, each annotated with the attack it blunts. Measuring matters: header claims are the easiest thing in security to fake on a slide and the easiest to verify on the wire — curl -I does it in the terminal companion, the integration test does it in CI."""),
                        code("sec:headers:insecure", "java",
                                "guide/config/SecurityDemoConfig.java — the deliberately relaxed comparison chain: @Order(1) puts it ahead of the main chain for its narrow matcher only",
                                "server/src/main/java/guide/config/SecurityDemoConfig.java", """
                                @Bean
                                @Order(1)
                                SecurityFilterChain insecureComparisonChain(HttpSecurity http) throws Exception {
                                    http
                                            .securityMatcher("/api/demo/security/lab/insecure/**")
                                            .csrf(csrf -> csrf.disable())
                                            .headers(headers -> headers.disable())
                                            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
                                    return http.build();
                                }"""),
                        demo("sec:headers:demo", "security:lab", "The security lab",
                                "Live response headers from both chains (the helmet beside the deliberately-relaxed comparison), two real CORS preflights answered by the configured policy, and a per-key token bucket you can exhaust to a 429 + Retry-After and reset through the test seam."),
                        text("sec:headers:placement", """
                                Placement notes worth stealing. Multiple SecurityFilterChain beans are legal and ordered — first MATCH wins, so the comparison chain's @Order(1) plus a narrow securityMatcher scopes it while the unordered main chain stays the catch-all. headers.disable() turns off ALL writers including defaults you forgot you had — the exact posture of an app with no security dependency. And when a real frontend pressures your CSP toward unsafe-inline: hash or nonce the inline scripts instead; unsafe-inline reopens the hole CSP exists to close.""")),
                section("cors-in-practice", "CORS in practice",
                        "Cross-origin sharing is a BROWSER policy the SERVER configures — one explicit allowlist, answered by the chain before any controller, and the reasons '*' with credentials is forbidden by the spec itself.",
                        text("sec:cors:model", """
                                The model first, because every misconfigured CORS story starts with a wrong one: the SERVER does not enforce CORS — the BROWSER does, and the server merely STATES its permission via response headers. A page at https://evil.example can SEND a cross-origin fetch to your API all day; CORS decides whether the page gets to READ the answer. For "simple" requests the browser adds Origin and enforces the response's Access-Control-Allow-Origin; for anything with custom headers or non-simple methods it sends a PREFLIGHT — an OPTIONS request carrying Access-Control-Request-Method — and refuses the real request unless the preflight's answer allows it. Preflights are answered by the chain's CorsFilter BEFORE any controller: your handler never sees them.

                                Spring Security consults a CorsConfigurationSource when you wire .cors(...). This app's policy is an explicit allowlist — the demo partner origin, this app's own client surfaces (dev server and the prod proxy origin — browsers attach Origin to POSTs even through a same-host proxy, and a policy that omits your own UI 403s it), two methods, no credentials:"""),
                        code("sec:cors:source", "java",
                                "guide/config/SecurityDemoConfig.java — the explicit policy: named origins (the demo partner PLUS the app's own client surfaces), GET/POST, no credentials",
                                "server/src/main/java/guide/config/SecurityDemoConfig.java", """
                                CorsConfiguration policy = new CorsConfiguration();
                                policy.setAllowedOrigins(List.of(DEMO_PARTNER_ORIGIN,
                                        "http://localhost:5176", "http://localhost:8081"));
                                policy.setAllowedMethods(List.of(HttpMethod.GET.name(), HttpMethod.POST.name()));
                                policy.setAllowedHeaders(List.of("Content-Type", "X-API-Key"));
                                policy.setAllowCredentials(false);
                                policy.setMaxAge(3600L);
                                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                                source.registerCorsConfiguration("/api/**", policy);
                                return source;"""),
                        text("sec:cors:star", """
                                WHEN IS '*' ACCEPTABLE? For a PUBLIC, CREDENTIALS-LESS API — anonymous read-only data — a wildcard origin is defensible: no cookies ride the request, so '*' only says "anyone may read what is already public." The moment credentials enter, '*' is not risky but ILLEGAL BY SPEC: the CORS spec forbids Access-Control-Allow-Origin: * together with Access-Control-Allow-Credentials: true, and browsers enforce the ban — echoing "any origin may send my cookies" would resurrect CSRF with extra steps. setAllowedOriginPatterns("*") + allowCredentials(true) is the misconfiguration that occasionally ships. The honest table: public anonymous API → '*' fine; anything authenticated → explicit allowlist (this policy); cookie-carrying SPA → EXACT origin, never a pattern.

                                The preflight demo asserts the verdicts the CONFIG actually answers — the integration test the chain runs in CI, not a mock:"""),
                        code("sec:cors:test", "java",
                                "server/src/test/java/guide/presentation/demo/security/SecurityLabDemoTest.java — the preflight verdicts, measured through the real filter chain",
                                "server/src/test/java/guide/presentation/demo/security/SecurityLabDemoTest.java", """
                                mockMvc.perform(options("/api/demo/security/lab/cors/target")
                                                .header("Origin", "https://partner.demo.sdg.dev")
                                                .header("Access-Control-Request-Method", "POST")
                                                .header("Access-Control-Request-Headers", "Content-Type"))
                                        .andExpect(status().isOk())
                                        .andExpect(header().string("Access-Control-Allow-Origin",
                                                "https://partner.demo.sdg.dev"))
                                        .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST"))
                                        .andExpect(header().string("Access-Control-Allow-Headers", "Content-Type"));"""),
                        text("sec:cors:notauthz", """
                                Last word, the one that prevents the classic confusion: CORS IS NOT AUTHORIZATION. A rejected origin stops a BROWSER PAGE from reading your API; curl and server-side scripts never sent a preflight in the first place. If a caller must not read a resource, that verdict belongs in the AUTHORIZATION layer (topic 24), enforced server-side. CORS manages pages; your chain manages callers.""")),
                section("rate-limiting-bucket4j", "Rate limiting with Bucket4j",
                        "The algorithm honesty — fixed window, sliding window, token bucket — and one in-memory per-key limiter behind a 429 + Retry-After contract the client can respect.",
                        text("sec:rate:why", """
                                Rate limiting is availability defense — one caller's volume (or flood, or buggy retry loop) must not consume everyone's capacity — and abuse defense: the express guide's login-rate-limit (30 attempts per 60 seconds per identity) is the canonical case, where brute-forcing at scale dies when each identity gets a small budget. Spring Security ships no limiter, so this topic adds the one the Java ecosystem converged on: Bucket4j. The dependency CHOICE, verified on Central: there is NO first-party Boot starter (spring-boot-starter-bucket4j does not exist; the ecosystem's only starter, com.giffing.bucket4j's, is third-party and Boot-3-shaped) — so the framework-agnostic core jar it is, wired explicitly as a bean like every other adapter here:"""),
                        code("sec:rate:pom", "xml",
                                "server/pom.xml — Bucket4j's JDK-17 core, the framework-agnostic token-bucket engine (no Spring coupling, Boot-4 compatible by construction)",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>com.bucket4j</groupId>
                                    <artifactId>bucket4j_jdk17-core</artifactId>
                                    <version>8.21.0</version>
                                </dependency>"""),
                        text("sec:rate:algorithms", """
                                ALGORITHM HONESTY before the code. FIXED WINDOW: "5 per minute" — count requests, reset at :00. Flaw: a burst of 5 at :59 plus 5 at :01 = 10 in two seconds; the budget doubles at every boundary. SLIDING WINDOW: the window TRAVELS with the request (last 60 seconds), fixing the boundary flaw at the cost of remembering timestamps. TOKEN BUCKET: the bucket holds up to C tokens, refills continuously at C per window; each request spends one — a legitimate burst passes instantly (the accumulated tokens ARE the burst budget) while the sustained rate caps at C/window, which is why it is the default for "attempts per minute per identity". RateLimitService chooses it; the demo lets you provision any (key, limit, window) family and watch it deplete:"""),
                        code("sec:rate:service", "java",
                                "guide/domain/security/RateLimitService.java — one token bucket per (key, limit, window), the refusal carrying Retry-After",
                                "server/src/main/java/guide/domain/security/RateLimitService.java", """
                                public Consumption tryConsume(String key, long limit, Duration window) {
                                    Bucket bucket = bucket(key, limit, window);
                                    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
                                    if (probe.isConsumed()) {
                                        return new Consumption(true, probe.getRemainingTokens(), 0);
                                    }
                                    long retryAfterSeconds = Math.max(1,
                                            Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
                                    return Consumption.refused(retryAfterSeconds);
                                }
                                // …
                                private Bucket bucket(String key, long limit, Duration window) {
                                    return buckets.computeIfAbsent(signature(key, limit, window), ignored -> Bucket.builder()
                                            .addLimit(Bandwidth.classic(limit, Refill.greedy(limit, window)))
                                            .build());
                                }"""),
                        text("sec:rate:contract", """
                                The CONTRACT is the part callers feel: on refusal, HTTP 429 with the STANDARD Retry-After header (seconds, machine-parseable) and the error envelope carrying the budget details. A polite client backs off for Retry-After seconds; a polite client library does it for you. The key selection is itself a lesson: the bucket key is the explicit param, else the X-API-Key header, else the caller's IP — per-identity budgets survive shared-NAT fairness better than per-IP ones (every corporate office shares one IP):"""),
                        code("sec:rate:controller", "java",
                                "guide/presentation/demo/security/SecurityLabDemoController.java — the 429 + Retry-After + envelope contract, assembled at the edge where HTTP lives",
                                "server/src/main/java/guide/presentation/demo/security/SecurityLabDemoController.java", """
                                if (consumption.allowed()) {
                                    return ResponseEntity.ok(new ConsumptionReport(limitKey, safeLimit, safeWindow, true,
                                            consumption.remainingTokens(), 0));
                                }
                                return ResponseEntity.status(429)
                                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(consumption.retryAfterSeconds()))
                                        .body(ErrorResponse.of("rate_limited",
                                                "rate budget exhausted for key \\"" + limitKey + "\\" — retry in "
                                                        + consumption.retryAfterSeconds() + "s (the bucket refills at "
                                                        + safeLimit + " per " + safeWindow + "s)",
                                                requestId(request),
                                                Map.of("limit", safeLimit, "windowSeconds", safeWindow,
                                                        "retryAfterSeconds", consumption.retryAfterSeconds())));"""),
                        text("sec:rate:limits", """
                                Honest limits of THIS implementation: the buckets are IN-MEMORY (per-instance) — a fleet needs the same bucket family everywhere, which is what Bucket4j's distributed backends (Redis, Hazelcast, a JDBC table) provide behind the same API. The reset seam exists for determinism: tests and the demo's reset button drop the bucket rather than sleeping out a window. And note what the limiter is NOT: not authentication (a fresh key gets a fresh budget — per-identity fairness, by design), and not a DDoS answer (the edge/CDN's job, topic 40) — it is the layer that keeps YOUR service polite under abuse it can still see.""")),
                section("injection-the-whole-family", "Injection: the whole family",
                        "SQL injection is the one you test for; XSS, command injection, deserialization and ReDoS are the siblings — one rule covers them all: data is never code, and every escaping boundary is a trust boundary.",
                        text("sec:inj:sql", """
                                The family patriarch, already proven live in topic 17's sql-safety demo, so this is the recap with the cross-reference: the demo's sandbox writes the SAME lookup twice — parameterized (the input travels as DATA to the planner, never as SQL text) and vulnerable (string concatenation, the input BECOMES the query). The UNION attack that reads another user's row works on one and dies on the other, and the sandbox runs the vulnerable path under a least-privilege role so even the working attack hits "permission denied" — bind parameters are the FIX, least privilege is the BACKSTOP, because validation has bugs. The two constants, side by side in the real adapter:"""),
                        code("sec:inj:sql-snippet", "java",
                                "server/src/main/java/guide/data/sqldemo/PgSqlSafetySandbox.java — topic 17's living proof, cross-quoted: the same lookup written safely and unsafely",
                                "server/src/main/java/guide/data/sqldemo/PgSqlSafetySandbox.java", """
                                private static final String PARAMETERIZED_SQL =
                                        "SELECT username, role, secret FROM demo_injection WHERE username = ?";

                                private static final String VULNERABLE_PREFIX =
                                        "SELECT username, role, secret FROM demo_injection WHERE username = '";"""),
                        text("sec:inj:xss", """
                                XSS is injection into the RENDERING layer: attacker-supplied data reaching a browser as MARKUP or SCRIPT. For a pure JSON API like this server, XSS is MUTED AT THE SOURCE — the API never emits HTML, and application/json plus the nosniff header keeps the browser from improvising any. The risk RELOCATES to the client that renders the data. This guide's React client is the specimen: React escapes ALL interpolated children by design — a topic title containing <script> renders as inert TEXT — and the component displaying the most attacker-shaped content of all (the code viewer, rendering snippet strings from the content API) leans on exactly that property:"""),
                        code("sec:inj:xss-client", "ts",
                                "client/src/ui/components/code-block.tsx — the escape happens by NOT opting out: children render as text, dangerouslySetInnerHTML appears nowhere in this client",
                                "client/src/ui/components/code-block.tsx", """
                                * The code string is rendered as text content (never dangerouslySetInnerHTML)
                                * so it appears verbatim: whitespace-pre + horizontal scroll, no reformatting.
                                // …
                                <pre className="overflow-x-auto whitespace-pre px-md py-md font-mono text-body-sm leading-relaxed text-surface dark:text-on-surface">
                                  <code>{code}</code>
                                </pre>"""),
                        text("sec:inj:xss-html", """
                                XSS returns the moment an SPA reaches for raw HTML: dangerouslySetInnerHTML (React) or innerHTML (vanilla) renders markup AS markup — the escape that protected you is what you disabled. Render TEXT by default; for the rare genuine-HTML case, sanitize with a parsing allowlist (DOMPurify is the standard) BEFORE the raw assignment — never regex, never "we trust this field" (a field's trustworthiness is one upstream bug from revoked). Template engines have the same seam: they auto-escape interpolation and offer raw opt-outs; count yours like your @SuppressWarnings.

                                COMMAND INJECTION is the sibling with root consequences: never Runtime.exec / ProcessBuilder on input-shaped strings. Arguments travel as separate argv elements (never a shell string), the command itself is a constant you own. This codebase has zero Runtime.exec — the honest number for a web app; when you truly need a process, the discipline is the webhooks guard's: fixed program, enumerated arguments, nothing user-shaped in the command position."""),
                        text("sec:inj:deser", """
                                DESERIALIZATION is injection into object construction: polymorphic Jackson (enableDefaultTyping / an open @JsonTypeInfo id) or raw ObjectInputStream lets the PAYLOAD name the class to instantiate — gadget chains become remote code execution. The rules, all followed here: bind request bodies to records with CLOSED typing; @JsonTypeInfo only with explicitly enumerated subtypes (a sealed hierarchy, like this app's content blocks, is the natural fit); never Java serialization on the wire. Jackson 3 (Boot 4's tools.jackson.*) changed none of this — the dangerous API is still the type id you did not enumerate.

                                ReDoS in one paragraph: nested quantifiers like (a+)+ take exponential time on adversarial input, wedging threads — fix with a safer pattern or a length cap BEFORE matching (this app's validation layer shapes every field first). The family pattern, one sentence: every member is DATA crossing into a context that will INTERPRET it — SQL, HTML, shell, the type system, the regex engine — and the fix is always the same boundary: parameterize, escape, or enumerate.""")),
                section("csrf-ssrf-and-friends", "CSRF, SSRF and friends",
                        "Why our API routes disable CSRF (and when you must not), and the webhooks simulator's outbound fetch turned into the SSRF specimen — an allowlist guard, implemented and tested.",
                        text("sec:csrf:ours", """
                                THE DECISION, IN THE BASELINE'S OWN WORDS: "API routes are token/header-authenticated, not cookie+form: no CSRF tokens to hand out." CSRF (Cross-Site Request Forgery) exploits the BROWSER's automatic credential attachment: a cookie authenticates a request the USER never intended to make, because the browser attaches session cookies to any cross-site request a page can trigger. If your authentication evidence travels in a header the hostile page CANNOT make the browser attach (Authorization: Bearer…, X-API-Key…), there is nothing to forge — the page's fetch carries no credentials. That is this app's API posture, in the chain:"""),
                        code("sec:csrf:config", "java",
                                "guide/config/SecurityConfig.java — the CSRF decision, quoted from the baseline: token/header authentication carries no CSRF surface",
                                "server/src/main/java/guide/config/SecurityConfig.java", """
                                // API routes are token/header-authenticated, not cookie+form: no CSRF
                                // tokens to hand out. (The session demo's own endpoints accept the
                                // deliberate per-demo decision — topic 25 teaches the full matrix.)
                                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/graphql/**"))"""),
                        text("sec:csrf:matrix", """
                                WHEN YOU MUST ENABLE IT: the moment authentication rides a COOKIE the browser attaches automatically — session logins, "remember me", SSO domains. Then every state-changing route needs a CSRF token (Spring Security's synchronizer-token pattern: server issues, form/body echoes, CsrfFilter compares), with SameSite cookies as the modern dampener — this app's session cookie ships http-only + SameSite=Lax (topic 23's yaml), blocking cross-site POST attachment while keeping normal navigation. The matrix: token-auth API → CSRF off is CORRECT (this app); cookie-session app → CSRF on + SameSite + Secure in prod; mixed app (this one) → per-island decisions stated in the chain. The session demo's POST /login stays exempt BY DESIGN — it accepts credentials, it does not consume them; the duty begins at the routes the session then authenticates.

                                SSRF is the family's server-side sibling: the SERVER tricked into making a request the ATTACKER chose. The specimen lives in this app's own webhooks simulator — a subscriber registers a URL the delivery engine will later POST to. Without a guard, "http://169.254.169.254/latest/meta-data/iam/…" is not a webhook destination; it is a request for the CLOUD'S OWN CREDENTIALS, sent from this machine's network position. The fix: judge every outbound destination at REGISTRATION time, default-deny:"""),
                        code("sec:ssrf:guard", "java",
                                "guide/domain/webhooks/SubscribeWebhook.java — the sanctioned guard, minimal: the verdict call sits after the scheme check, before the store",
                                "server/src/main/java/guide/domain/webhooks/SubscribeWebhook.java", """
                                // Topic 25: the SSRF guard — the server will FETCH this url later, so
                                // private ranges and metadata endpoints are refused at SUBSCRIPTION
                                // time, not discovered at delivery time.
                                UrlAllowlist.Verdict outbound = urlAllowlist.check(url);
                                if (!outbound.allowed()) {
                                    return Result.failed(Failure.of(FailureKind.VALIDATION,
                                            "url refused by the outbound allowlist (SSRF guard): " + outbound.reason()));
                                }"""),
                        code("sec:ssrf:allowlist", "java",
                                "guide/domain/security/UrlAllowlist.java — the judgment: named exceptions pass, metadata names and address classes refuse, hostnames are never resolved (no TOCTOU window)",
                                "server/src/main/java/guide/domain/security/UrlAllowlist.java", """
                                if (permittedHosts.contains(normalized)) {
                                    return Verdict.ALLOWED;
                                }
                                if (isMetadataName(normalized)) {
                                    return Verdict.refused("'" + normalized + "' is a cloud metadata service alias — "
                                            + "the credential endpoint SSRF exists to reach");
                                }
                                // …
                                    if (address.isLinkLocalAddress()) {
                                        return Verdict.refused(host + " is link-local — this range hides the cloud metadata service "
                                                + "(169.254.169.254 and friends)");
                                    }"""),
                        text("sec:ssrf:honesty", """
                                The guard's HONEST LIMITS, stated in its own javadoc: hostnames are checked by NAME and IP-literal only, never resolved — resolving at validation opens a DNS-rebinding TOCTOU (first answer public, delivery-time answer link-local) and makes validation network-dependent; production closes that with an egress proxy or pinning resolver. The demo's loopback exception (the simulator's own receiver at localhost) is a NAMED allowlist entry, not a range. Open redirects are the adjacent friend: a redirect-following client turns your validated URL into a hop to anywhere — pin redirect handling off or re-validate each hop. Clickjacking got its header two sections ago (frameOptions DENY).""")),
                section("secrets-and-config", "Secrets and config",
                        "The SDG_JWT_SECRET pattern — env over file over clearly-marked dev default, never a production literal — plus .env discipline, rotation prose, and how compose actually moves secrets into containers.",
                        text("sec:secrets:pattern", """
                                THE PATTERN, IN ONE LINE OF YAML: the secret's value is ${ENV_VAR:fallback}, the fallback is a clearly-marked DEV value, and production sets the env var from a secret store. This app's one real secret, quoted from its config:"""),
                        code("sec:secrets:yaml", "yaml",
                                "server/src/main/resources/application.yml — the JWT signing secret: env first, dev default explicitly marked as demo material",
                                "server/src/main/resources/application.yml", """
                                security:
                                  oauth2:
                                    resourceserver:
                                      jwt:
                                        secret-key: ${SDG_JWT_SECRET:dev-only-sdg-jwt-hs256-secret-3b9d6f01c2a8e45d7f0b}"""),
                        text("sec:secrets:ladder", """
                                The LADDER, most to least preferred: (1) SECRET STORE — Vault, AWS/GCP secret managers, k8s Secrets; access auditable, rotation an operation not a release. (2) ENVIRONMENT at deploy time — what this app's compose files do (below); the secret lives outside image and repo. (3) UNCOMMITTED FILE — .env for local dev, git-ignored by a rule that exists IN the repo. (4) MARKED DEV DEFAULTS — acceptable only because a dev default grants nothing an attacker lacks. What NEVER appears: production literals in yaml, secrets in the repo, secrets baked into IMAGE LAYERS (they leak forever), secrets in LOGS (this app logs field NAMES, never values) or stack traces. The test question: "if this repo leaked tomorrow, what would need rotating?" — here, nothing.

                                ROTATION is the promise the pattern implies: the OLD value stops working while the NEW one starts, without downtime. For HS256 that means dual-key overlap — accept either signature during the window, issue only the new (nimbus via a JWK Set with kid rotation); for API keys (topic 23) the store keeps old-hash validity until expiry; for the PII vault below, rotation and ERASURE are the same machinery. A secret you cannot rotate is a liability, not a control."""),
                        code("sec:secrets:compose", "yaml",
                                ".docker/docker-compose.prod.yml — the deploy-time move: the image stays clean, the environment hands the process its values",
                                ".docker/docker-compose.prod.yml", """
                                environment:
                                  # Phase 6 (data foundation): the datasource, by service name inside the
                                  # compose network (mirrors the dev stack's server env exactly).
                                  - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/guide
                                  - SPRING_DATASOURCE_USERNAME=guide
                                  - SPRING_DATASOURCE_PASSWORD=guide"""),
                        text("sec:secrets:compose-note", """
                                Read that block as the PATTERN, not the values: the image is identical in every environment (twelve-factor, topic 36), and compose is the deploy-time handoff — values move from a store into the container's environment; the app knows only property names. These values are dev-grade on purpose; a real deployment interpolates from the host's secret store, and ${VAR}?error=missing makes an absent secret a loud boot failure instead of a silent fallback. The .env discipline ties the ladder's bottom rung: a committed .env.example documents the SHAPE (names, dummy values) while the real .env stays ignored.""")),
                section("owasp-api-top-10-mapped-to-this-app", "OWASP API Top 10, mapped to this app",
                        "The ten API risks, each answered with the real code that defends it — and the honest gaps flagged, because a security map that admits nothing is marketing.",
                        text("sec:owasp:map", """
                                The OWASP API Security Top 10 is the checklist shape this topic's lessons snap into — mapped to THIS app, risk by risk:

                                API1 BROKEN OBJECT LEVEL AUTHORIZATION — every object access must check the CALLER's right to THAT object: topic 24's authorization island. GAP, honestly: most demo routes here are deliberately permissive (the teaching inversion) — the ISLAND is the example, not the whole app.

                                API2 BROKEN AUTHENTICATION — topic 23's six flows, all real Spring Security. The deliberate gRPC posture belongs here: topic 15's teaching surface authenticates NOTHING, and after topic 23's blanket exclusion was replaced by THIS topic's decision, the verdict is stated, stamped on the wire, and ENFORCED — presented credentials are refused, never silently ignored:"""),
                        code("sec:owasp:grpc", "java",
                                "guide/domain/security/GrpcPostureInterceptor.java — the deliberate gRPC posture as code: permit-all stamped, credential-bearing calls refused loudly",
                                "server/src/main/java/guide/domain/security/GrpcPostureInterceptor.java", """
                                if (headers.containsKey(AUTHORIZATION)) {
                                    call.close(Status.UNAUTHENTICATED.withDescription(
                                            "this surface authenticates nothing by design; credentials it cannot validate "
                                                    + "are refused, never silently ignored"), new Metadata());
                                    return new ServerCall.Listener<>() {
                                    };
                                }
                                return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                                    @Override
                                    public void sendHeaders(Metadata responseHeaders) {
                                        responseHeaders.put(POSTURE, POSTURE_VERDICT);
                                        super.sendHeaders(responseHeaders);
                                    }
                                }, headers);"""),
                        text("sec:owasp:rest", """
                                The interceptor is registered globally via spring-grpc's @GlobalServerInterceptor, and the yaml exclusion that preceded it was NARROWED to the one autoconfig that would silently inherit the MVC JWT secret — the authenticated option (authorizeRequests(anyRequest().authenticated()) + oauth2ResourceServer(jwt) on the autoconfigured GrpcSecurity builder, BearerTokenAuthenticationInterceptor on the client channel) is now a configuration change away.

                                API3 PROPERTY-LEVEL AUTHORIZATION — closed record DTOs, plaintext never leaves the PII vault (next section). API4 UNRESTRICTED RESOURCE CONSUMPTION — rate limiting (this topic), bounded streaming, HikariCP's pool ceiling. API5 FUNCTION-LEVEL AUTHORIZATION — topic 24's URL rules + method security; GAP: @PreAuthorize covers the authz island only. API6 SENSITIVE BUSINESS FLOWS — key issuance and token exchange prove themselves with credentials IN the request; the rate limiter blunts brute force.

                                API7 SSRF — the UrlAllowlist, implemented and tested. API8 SECURITY MISCONFIGURATION — the helmet headers, the deliberately insecure comparison chain (the misconfiguration demo is literally a chain this topic ships), no default accounts, the narrowed yaml exclusion. API9 INVENTORY MANAGEMENT — /v3/api-docs drift-tested (topic 12), versioned content API, dev-only surfaces (GraphiQL) profile-gated. API10 UNSAFE CONSUMPTION — the outbound side: the webhooks client treats response codes as DATA, timeouts are set not inherited, the allowlist judges what the server consumes. The honest GAP list, so this stays a map and not a brochure: no TLS in dev (the proxy's job in prod), in-memory-only rate limiting, demo routes deliberately open (the teaching inversion), hostname-based SSRF without DNS pinning."""),
                        code("sec:owasp:client", "ts",
                                "client/src/ui/demos/security/lab_demo.tsx — the client half of the 429 contract: the refused branch renders the failure as EVIDENCE (Retry-After travels in the message), not a crash",
                                "client/src/ui/demos/security/lab_demo.tsx", """
                                } else if (result.failure.status === 429) {
                                  setRatePanel({ kind: 'refused', failure: result.failure, retryAfter: windowSeconds });
                                } else {
                                  setRatePanel({ kind: 'error', failure: result.failure });
                                }""")),
                section("pii-retention-and-gdpr", "PII retention and GDPR-style erasure",
                        "Right to be forgotten, implemented as crypto-shred: per-subject AES-GCM keys, erasure that deletes THE KEY while rows survive as noise, and an append-only log that proves it happened.",
                        text("sec:pii:why", """
                                GDPR's erasure right says: on request, a subject's personal data must become GONE — not hidden, not anonymized-in-place, gone. The naive implementation, DELETE FROM users WHERE id = …, fails the moment you inventory where data actually lives: the primary row dies, but the replica lags, last night's backup keeps everything, the analytics export is on someone's laptop, and the debug LOG has the email in it. You cannot enumerate what you cannot see. THE CRYPTO-SHRED ANSWER: encrypt each subject's data under a key belonging to THAT SUBJECT ALONE, and implement erasure as DESTROYING THE KEY — ciphertext survives everywhere as noise nobody can read, and deletion you cannot perform everywhere becomes deletion you performed ONCE.

                                The vault in this app, three ports and a cipher: KeyVault (where keys live and die), PiiVault (the deliberately dumb ciphertext store), ErasureLog (the append-only witness). The key home is the load-bearing wall:"""),
                        code("sec:pii:keyvault", "java",
                                "guide/domain/security/KeyVault.java — the port whose DESTROY method is the entire right-to-be-forgotten implementation",
                                "server/src/main/java/guide/domain/security/KeyVault.java", """
                                /** Mint a fresh AES-256 key for the subject; returns its id. Fails if one already exists. */
                                String createKey(String subjectId);
                                // …
                                /** Erasure: destroy the subject's key. Returns true when a key existed and died. */
                                boolean destroyKey(String subjectId);"""),
                        text("sec:pii:cipher", """
                                The cipher is AES-256-GCM with a fresh random IV per value — AEAD, so tampering FAILS the tag instead of decrypting to plausible garbage; random IVs, so identical plaintexts seal to unrelated ciphertexts (the test pins this). Every field value travels as base64(iv ‖ ciphertext): printable rows, no plaintext at rest, none in logs, none in responses (store returns truncated PREVIEWS only):"""),
                        code("sec:pii:cipher-code", "java",
                                "guide/domain/security/AesGcmFieldCipher.java — fresh IV per encryption, GCM tag for integrity, printable wire form",
                                "server/src/main/java/guide/domain/security/AesGcmFieldCipher.java", """
                                byte[] iv = new byte[IV_BYTES];
                                RANDOM.nextBytes(iv);
                                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                                byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
                                // …
                                return Base64.getEncoder().encodeToString(wire);"""),
                        text("sec:pii:erasure", """
                                The erasure use case is deliberately anti-climactic — that is its proof of concept: destroy the key, log the receipt, and TELL THE TRUTH about what survived. The rows are still counted in the response (ciphertextFieldsStillStored) because honesty about retention is the point; the log entry is the auditable evidence an erasure request legally demands:"""),
                        code("sec:pii:erasure-code", "java",
                                "guide/domain/security/ErasureService.java — the shred: one destroy, one append, the receipt tells exactly what survived as noise",
                                "server/src/main/java/guide/domain/security/ErasureService.java", """
                                boolean keyExisted = keyVault.destroyKey(subjectId);
                                int fields = record == null ? 0 : record.encryptedFields().size();
                                ErasureLogEntry entry = erasureLog.append(subjectId, keyId == null ? "unknown-key" : keyId,
                                        "right-to-be-forgotten: per-subject key destroyed; " + fields
                                                + " ciphertext field(s) retained as permanently unreadable bytes");"""),
                        demo("sec:pii:demo", "security:pii", "The PII crypto-shred vault",
                                "Store a subject's fields (ciphertext previews + key id come back), read them decrypted, erase the subject, and read AGAIN — the same route now answers the typed undecryptable failure. The erasure log shows the append-only audit trail."),
                        text("sec:pii:honesty", """
                                The before/after IS the demo: read-before decrypts; read-after-erase is the SAME route answering a typed failure whose details say erased: true and how many ciphertext fields survive — the distinction between "never stored" and "stored, then made permanently unreadable" is exactly the story an auditor asks for. HONEST LIMITS, same discipline as the SSRF guard: this vault is in-memory — a real deployment keeps keys in a KMS whose delete is itself logged, and the ERASURE LOG lives in a table with INSERT-only grants (the port enforces append-only in the TYPE: no update, no remove exists). And retention DESIGN comes before erasure mechanics: the cheapest PII to erase is the PII you never stored — collect the minimum, pseudonymize where you can (subject IDs here, not names). Crypto-shred is the mechanism of last resort; data minimization is the first.""")),
                section("terminal-companion", "Terminal companion",
                        "The curl tour: measure the helmet on the wire, get a preflight verdict, exhaust a rate limit to its 429, and walk the crypto-shred lifecycle end to end.",
                        text("sec:terminal:intro", """
                                Run the server (dev compose or your IDE), then take the tour. Every claim this topic made is one curl away — headers measured, not promised; verdicts answered by the real chain; the 429 arriving with its Retry-After; the vault's before/after on the same route."""),
                        text("sec:terminal:tour", """
                                The headers, measured — the helmet on the main chain, its absence on the deliberately insecure comparison (note HSTS absent on both: plain HTTP, exactly as the writer intends):"""),
                        code("sec:terminal:headers", "bash",
                                "measure the helmet (and its absence) on the wire",
                                null, """
                                curl -sI http://localhost:8080/api/demo/security/lab/probe | grep -Ei 'x-content-type|x-frame|content-security|referrer|strict-tr'
                                curl -sI http://localhost:8080/api/demo/security/lab/insecure/probe | grep -Ei 'x-content-type|x-frame|content-security|referrer|strict-tr' || echo '(nothing — the relaxed chain writes none of them)'

                                # the annotated lab, both chains fetched live by the server itself:
                                curl -s http://localhost:8080/api/demo/security/lab/headers | head -c 1200; echo"""),
                        text("sec:terminal:cors", """
                                The preflight verdicts — allowlisted origin echoed, unlisted origin refused with 403 (and the lab's live version doing both in one call):"""),
                        code("sec:terminal:cors-curl", "bash",
                                "ask the chain's CORS policy directly",
                                null, """
                                curl -si -X OPTIONS http://localhost:8080/api/demo/security/lab/cors/target \\
                                  -H 'Origin: https://partner.demo.sdg.dev' \\
                                  -H 'Access-Control-Request-Method: POST' | grep -Ei 'HTTP/|access-control'

                                curl -si -X OPTIONS http://localhost:8080/api/demo/security/lab/cors/target \\
                                  -H 'Origin: https://evil.example' \\
                                  -H 'Access-Control-Request-Method: POST' | head -1"""),
                        text("sec:terminal:rate", """
                                The rate limit — provision a tiny bucket (2 per hour, so refill never rescues it), spend it, meet the 429 and its Retry-After, then use the reset seam:"""),
                        code("sec:terminal:rate-curl", "bash",
                                "exhaust a bucket, read the 429 + Retry-After, reset through the seam",
                                null, """
                                curl -s -X POST 'http://localhost:8080/api/demo/security/lab/ratelimited?key=tour&limit=2&window=3600'
                                curl -s -X POST 'http://localhost:8080/api/demo/security/lab/ratelimited?key=tour&limit=2&window=3600'
                                curl -si -X POST 'http://localhost:8080/api/demo/security/lab/ratelimited?key=tour&limit=2&window=3600' | grep -Ei 'HTTP/|retry-after|rate_limited'

                                # the seam the tests use — the bucket starts full again:
                                curl -s -X POST 'http://localhost:8080/api/demo/security/lab/ratelimited/reset?key=tour&limit=2&window=3600'"""),
                        text("sec:terminal:pii", """
                                The crypto-shred lifecycle — store, read, erase, read again (the typed undecryptable failure), and the audit trail:"""),
                        code("sec:terminal:pii-curl", "bash",
                                "walk the vault: seal → decrypt → shred → the same route refuses",
                                null, """
                                curl -s -X POST http://localhost:8080/api/demo/security/pii/store \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{\"subject\":\"usr_tour\",\"fields\":{\"name\":\"Ada\",\"email\":\"ada@example.com\"}}' | head -c 400; echo

                                curl -s http://localhost:8080/api/demo/security/pii/read/usr_tour; echo

                                curl -s -X POST http://localhost:8080/api/demo/security/pii/erase/usr_tour; echo

                                # the same read after erasure — typed, honest about what survived:
                                curl -s http://localhost:8080/api/demo/security/pii/read/usr_tour; echo

                                curl -s http://localhost:8080/api/demo/security/pii/log | head -c 600; echo"""),
                        text("sec:terminal:close", """
                                What the tour proved, in order: the chain writes the headers it claims (and the relaxed chain writes none); the CORS policy answers preflights with echo-or-refuse; the rate limiter honors its 429+Retry-After contract and its reset seam; and the vault's erasure is real — same route, different world. That is the topic's whole method: every defense on this page is a MEASURED behavior of this running app. Security that only exists in a config review is a belief; security you can curl is a control.""")));
    }
}
