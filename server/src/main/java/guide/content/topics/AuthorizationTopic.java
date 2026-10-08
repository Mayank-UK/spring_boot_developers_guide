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
 * Topic 24 — Authorization. Group auth-security, order 2. Built directly
 * on topic 23's baseline: one SecurityFilterChain extended with the
 * authz island (the sanctioned append above the open fallthrough), the
 * same seeded users, the same session machinery reused by the act-as
 * switch. Three enforcement layers taught as three shapes — URL rules,
 * method annotations, in-use-case policy — over one deny-by-default
 * evaluator. The quoted code IS the island: domain/authz holds every
 * policy, presentation/demo/authz the routes, config/AuthzDemoConfig
 * the wiring (and @EnableMethodSecurity).
 */
public final class AuthorizationTopic {

    private AuthorizationTopic() {
    }

    public static Topic topic() {
        return new Topic("authorization", "Authorization",
                "May you? Roles and authorities, method security with SpEL, permissions "
                        + "and scopes, ownership as the object-level check, and the "
                        + "deny-by-default discipline — every verdict rendered live from "
                        + "the app's own /api/demo/authz island, one act-as switch away.",
                TopicGroupId.AUTH_SECURITY, 2,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("authentication-vs-authorization", "Authentication vs authorization",
                        "Are you who you claim — versus — may you do what you asked. Two questions, two failure modes, and only one of them makes noise.",
                        text("authz:split", """
                                AUTHENTICATION (topic 23) establishes WHO; AUTHORIZATION decides MAY. The sentence sounds trivial until you watch the confusion's real cost: an authentication failure is LOUD — a failed login, a 401 in the log. An authorization failure can be SILENT for years: an endpoint that forgets its role check does not crash; it ANSWERS — happily, correctly, to the wrong people. The recent run of API breaches were mostly authorization bugs, not authentication bugs: valid tokens, valid logins, invalid questions answered — and no failed login in the log, because nobody ever failed to log in.

                                The wire-level echo is the status pair you know: 401 UNAUTHORIZED (misnamed — it means UNAUTHENTICATED) versus 403 FORBIDDEN (authenticated, and the answer is no). Keeping the pair straight is diagnostic gold: a 403 storm means policy or role-assignment trouble; a 401 storm means broken token machinery. And the envelope matters as much as the status: a denial is an ANSWER, not an accident — the same {error:{code,message,requestId}} every failure here carries, with the reason inside."""),
                        text("authz:island", """
                                This topic's island extends topic 23's baseline EXACTLY as its extension-point javadoc intended: rules appended ABOVE the open fallthrough, never a rewrite. The /api/demo/authz/** surface is guarded; its two open doors are deliberate — the matrix (a reference table, no user data) and the act-as switch (the tour's login stand-in: it establishes a session for any seeded user WITHOUT a password, because authorization assumes authentication already happened; a real deployment never ships that). Everything else demands an authenticated caller first:"""),
                        code("authz:island-rules", "java",
                                "guide/config/SecurityConfig.java — the sanctioned append: the authz island above the open fallthrough",
                                "server/src/main/java/guide/config/SecurityConfig.java", """
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
                                .requestMatchers("/api/**").permitAll()"""),
                        text("authz:layers", """
                                Read that append as the FIRST of three enforcement layers this topic teaches, because the island deliberately uses all three: URL RULES (the chain's authorizeHttpRequests — coarse, per-route, runs before any controller), METHOD ANNOTATIONS (@PreAuthorize/@PostAuthorize — the service contract, per-operation, SpEL-expressive), and IN-USE-CASE POLICY (the check inside the domain operation itself — the last line, reached by every caller regardless of transport). Each layer catches what the others cannot: the URL rule never lets an anonymous request near the code; the annotation states the contract next to the signature it guards; the in-code check protects the operation when a job, a listener, or another use case invokes it with no URL and no controller in sight. Defense in depth is not paranoia — it is the acknowledgment that each layer has a blind spot.

                                The substrate under all of it is deliberately small: six OwnedDocuments (owners alice/bob/carol, visibilities private/team/public), three actions (read, list, publish), three roles (reader, staff, admin) — the same three users topic 23 seeded, because authorization questions only make sense about REAL identities.""")),
                section("roles-and-authorities-rbac", "Roles and authorities — RBAC",
                        "ROLE_ADMIN is just a string with a convention in front of it — and that is the strength of role-based access control, not its weakness.",
                        text("rbac:authority-anatomy", """
                                Spring Security's atom is the GRANTEDAUTHORITY: a string the authentication carries, checked everywhere authorization happens. The ROLE_ prefix is a NAMING CONVENTION, not a type system: hasRole('ADMIN') means "an authority literally named ROLE_ADMIN", and hasAuthority('ROLE_ADMIN') is the same check spelled honestly. Topic 23's UserDetailsService bridge used .roles("admin","staff"), which uppercases and prefixes for you — the convenience half of every ROLE_ confusion; this topic's act-as switch builds the same authorities explicitly. Two consequences: mixing conventions silently fails (hasRole('ROLE_ADMIN') checks ROLE_ROLE_ADMIN; lowercase-stored roles miss uppercase checks), and authorities need not be roles at all — a permission (document:read), a flag (2fa-enabled), a scope-derived grant: the string is yours to define.

                                ROLE-BASED ACCESS CONTROL then is one small data structure: roles are NAMED BUNDLES of allowed actions, users carry roles, and the authorization question reduces to LOOKUPS. This app's whole matrix is one map, seeded in one method — deliberately readable, deliberately tiny:"""),
                        code("rbac:role-policy", "java",
                                "guide/domain/authz/RolePolicy.java — the whole RBAC model as one map; unknown roles allow nothing",
                                "server/src/main/java/guide/domain/authz/RolePolicy.java", """
                                public static RolePolicy seeded() {
                                    Map<String, Set<AuthzAction>> matrix = new LinkedHashMap<>();
                                    matrix.put("reader", new LinkedHashSet<>(List.of(AuthzAction.READ, AuthzAction.LIST)));
                                    matrix.put("staff", new LinkedHashSet<>(List.of(
                                            AuthzAction.READ, AuthzAction.LIST, AuthzAction.PUBLISH)));
                                    matrix.put("admin", new LinkedHashSet<>(List.of(
                                            AuthzAction.READ, AuthzAction.LIST, AuthzAction.PUBLISH)));
                                    return new RolePolicy(matrix);
                                }

                                public boolean roleAllows(String role, AuthzAction action) {
                                    return matrix.getOrDefault(role, Set.of()).contains(action);
                                }"""),
                        text("rbac:role-or-permissions", """
                                Sit with that map and ask the question every team eventually argues about: is "admin" a ROLE or a pile of PERMISSIONS? The honest answer: a role is a JOB-SHAPED SUMMARY of permissions — admin: [read, list, publish] is a permission set wearing a name. The trade: roles are CHEAP TO CHECK and EXPENSIVE TO REFINE (every exception becomes a new role — soon you have admin-readonly-except-fridays); permissions are the reverse (re-bundle, every holder updates at once). Discipline: keep ROLES few and stable (they end up in tokens and audit logs — renaming one is a migration), keep the ROLE→PERMISSION mapping in DATA, and when a role needs a fifth exception, split it instead of overloading the check.

                                The matrix's fatal dimension is COMBINATORICS: three roles × three actions is nine cells; ten roles × forty verbs is four hundred, and nobody reviews four hundred cells. Both axes stay as small as the product honestly needs, and every cell must be JUSTIFIABLE in one sentence. The rendered verdict table below holds every user × action × document verdict — 54 cells computed by the same evaluator the guarded endpoints consult, never a table pasted beside the code that could drift from it:"""),
                        code("rbac:matrix-derived", "java",
                                "guide/domain/authz/EvaluateMatrix.java — the rendered table IS the live policy: no hardcoded duplicates to drift",
                                "server/src/main/java/guide/domain/authz/EvaluateMatrix.java", """
                                List<MatrixRow> rows = new ArrayList<>();
                                for (UserPrincipal user : users) {
                                    for (AuthzAction action : rolePolicy.knownActions()) {
                                        for (OwnedDocument document : documents) {
                                            DenyByDefault.Verdict verdict = evaluator.decide(user, action.wireName(), document);
                                            rows.add(new MatrixRow(user.username(), user.roles(), action.wireName(),
                                                    document.id(), document.title(), document.ownerId(),
                                                    document.visibility().wireName(), verdict.allowed(),
                                                    verdict.checkedBy(), verdict.reason()));
                                        }
                                    }
                                }"""),
                        demo("rbac:matrix-demo", "authz:matrix",
                                "The authorization matrix",
                                "Act as alice (admin+staff), bob (staff), or carol (reader) — a session switch with no password, reusing topic 23's login machinery. Read the role→action matrix and the full user×action×document verdict table, rendered live from the real policies. Watch the document list RE-FILTER per caller (bob never sees alice's private rows; alice sees everything). Then fire the guarded probes — read alice's private document, publish bob's — and watch each 403 arrive as this guide's error envelope (code forbidden, a requestId, the reason), labeled with which enforcement layer fired: the URL rule, the @PreAuthorize/@PostAuthorize annotation, or the in-use-case policy. The self-invocation trap runs live at the end."),
                        text("rbac:client-window", """
                                The client half never teaches, it windows — the demo component calls the island with the same guarded-fetch helper every auth demo uses (cookie jar included, so the act-as session rides along), and renders a DENIAL as evidence rather than an accident: the failure's status, code, and requestId ARE the demo's content. One helper drives every guarded probe, labeling which layer the route demonstrates:"""),
                        code("rbac:matrix-client", "ts",
                                "client/src/ui/demos/authz/matrix_demo.tsx — every guarded probe renders its verdict, 403 envelopes included",
                                "client/src/ui/demos/authz/matrix_demo.tsx", """
                                /** One guarded call, one labeled verdict — 200s summarized, 403s as the envelope. */
                                const runProbe = async (
                                  label: string,
                                  layer: string,
                                  path: string,
                                  init: RequestInit | undefined,
                                ): Promise<void> => {
                                  const result = await authApiJson<Record<string, unknown>>(path, init, (body) =>
                                    isRecord(body) ? body : {},
                                  );
                                  if (result.ok) {
                                    const effect = typeof result.value['effect'] === 'string'
                                      ? ` — ${String(result.value['effect'])}`
                                      : '';
                                    setProbe({ label, layer, status: 200, summary: `200 allowed${effect}` });
                                    return;
                                  }
                                  const failure = result.failure;
                                  setProbe({
                                    label,
                                    layer,
                                    status: failure.status,
                                    summary: `${String(failure.status)} ${failure.code} — ${failure.message} (requestId ${failure.requestId ?? 'n/a'})`,
                                  });
                                };""")),
                section("method-security-preauthorize-and-spel", "Method security — @PreAuthorize and SpEL",
                        "The authorization decision moved to the line every caller already crosses — the method invocation — and the expression language is where role checks grow into policy.",
                        text("method:enable", """
                                URL rules cannot see ARGUMENTS or RETURN VALUES; in-code checks are invisible at the boundary. METHOD SECURITY splits the difference: an annotation on the method, evaluated by a PROXY around it, with the invocation's actual parameters in scope. One switch turns it on — @EnableMethodSecurity — and from that moment annotated beans are wrapped in a proxy that runs the expression BEFORE (or AFTER) the body. This guide mounts the switch on the authz topic's own configuration, keeping the URL baseline (topic 23) and the annotation machinery separable decisions:"""),
                        code("method:enable-code", "java",
                                "guide/config/AuthzDemoConfig.java — the switch, and the bean SpEL will call by name",
                                "server/src/main/java/guide/config/AuthzDemoConfig.java", """
                                @Configuration
                                @EnableMethodSecurity
                                public class AuthzDemoConfig {

                                    // …

                                    /** Named "authz" — the string method-security SpEL references. */
                                    @Bean(name = "authz")
                                    AuthzPermissions authzPermissions(DocumentStore documentStore, OwnershipPolicy ownershipPolicy) {
                                        return new AuthzPermissions(documentStore, ownershipPolicy);
                                    }"""),
                        text("method:spel-anatomy", """
                                The publish route's annotation is the full anatomy in one line: hasAnyRole('ADMIN','STAFF') and @authz.canPublish(#id, authentication). Read it in pieces. BUILT-IN PREDICATES — hasRole, hasAnyRole, hasAuthority, isAuthenticated — cover the coarse checks, composed with and/or/not. METHOD PARAMETERS — #id is the actual path variable the caller presented, bound by name (Boot compiles with -parameters; lose that flag and #id becomes an unhappy surprise). THE SECURITY CONTEXT — authentication is the current Authentication. And the piece that keeps annotations honest at scale: BEAN REFERENCES — @authz.canPublish(...) calls a NAMED SPRING BEAN, the seam where the expression meets real policy instead of string-matching inline. SpEL's and SHORT-CIRCUITS: carol (reader) dies at hasAnyRole, the bean never runs; bob (staff) passes the role half and dies in the bean (staff, not owner); alice passes both. Three users, one line, three verdicts — and the route:"""),
                        code("method:publish-annotation", "java",
                                "guide/presentation/demo/authz/AuthzDemoController.java — the annotation composes a built-in predicate with a bean-referenced policy check",
                                "server/src/main/java/guide/presentation/demo/authz/AuthzDemoController.java", """
                                @PostMapping(value = "/api/demo/authz/documents/{id}/publish",
                                        produces = MediaType.APPLICATION_JSON_VALUE)
                                @PreAuthorize("hasAnyRole('ADMIN', 'STAFF') and @authz.canPublish(#id, authentication)")
                                public Map<String, Object> publish(@PathVariable String id) {
                                    UserPrincipal caller = currentCaller();
                                    return switch (actAsGuardedAction.execute(caller, AuthzAction.PUBLISH.wireName(), id)) {
                                        case Success<ActAsGuardedAction.GuardedOutcome> success -> {
                                            // …
                                        }
                                        case Failed<ActAsGuardedAction.GuardedOutcome> failed ->
                                                throw AppException.of(failed.failure());
                                    };
                                }"""),
                        code("method:bean-check", "java",
                                "guide/domain/authz/AuthzPermissions.java — the bean the expression calls: real OwnershipPolicy, fail-closed edges",
                                "server/src/main/java/guide/domain/authz/AuthzPermissions.java", """
                                public boolean canPublish(String documentId, Authentication authentication) {
                                    if (authentication == null || !authentication.isAuthenticated()) {
                                        return false;
                                    }
                                    OwnedDocument document = documentStore.findById(documentId).orElse(null);
                                    if (document == null) {
                                        return false;
                                    }
                                    return ownershipPolicy.canPublish(asPrincipal(authentication), document);
                                }"""),
                        text("method:post-authorize", """
                                @PostAuthorize is the same machinery pointed at the RETURN VALUE — the check runs AFTER the body, on what is about to be handed back: returnObject.owner() == authentication.name() is a pure ownership verdict on the fetched row. Its cost is honest and worth naming: the work HAS RUN — the query executed, the row left the store — before the verdict; @PostAuthorize filters the ANSWER, not the FETCH. This guide's specimen route isolates that property deliberately (its fetch is a raw peek with no policy), and it is BLUNTER than the policy on purpose: an admin fails it on a stranger's document, because pure ownership is all it says. The two annotations, side by side on the island:"""),
                        code("method:post-authorize-code", "java",
                                "guide/presentation/demo/authz/AuthzDemoController.java — the @PostAuthorize specimen: verdict on the return object",
                                "server/src/main/java/guide/presentation/demo/authz/AuthzDemoController.java", """
                                @GetMapping(value = "/api/demo/authz/documents/{id}/owner-view",
                                        produces = MediaType.APPLICATION_JSON_VALUE)
                                @PostAuthorize("returnObject.owner() == authentication.name")
                                public OwnerView ownerView(@PathVariable String id) {
                                    return switch (actAsGuardedAction.peek(id)) {
                                        case Success<OwnedDocument> success -> OwnerView.from(success.value());
                                        case Failed<OwnedDocument> failed -> throw AppException.of(failed.failure());
                                    };
                                }"""),
                        text("method:proxy-trap", """
                                THE PROXY UNDERNEATH is AOP again — topic 06's machinery, re-armed for security. @PreAuthorize is advice; the bean callers inject is a CGLIB proxy; the annotation runs in the proxy, NOT in your object. And therefore topic 06's SELF-INVOCATION TRAP inherits directly: a method calling its own guarded method goes through THIS, the raw target, and NO CHECK RUNS. For logging that was a missing line in a trace; for security it is a HOLE — the guarded method executes for a caller the annotation would have denied. The specimen, one bean, two methods:"""),
                        code("method:self-invocation-specimen", "java",
                                "guide/domain/authz/SelfInvocationSpecimen.java — the trap: the internal call bypasses the proxy and its @PreAuthorize",
                                "server/src/main/java/guide/domain/authz/SelfInvocationSpecimen.java", """
                                @PreAuthorize("hasRole('ADMIN')")
                                public String adminOnlyNote() {
                                    return "the admin-only note (you passed a REAL @PreAuthorize check)";
                                }

                                public String adminOnlyNoteFromInside() {
                                    return adminOnlyNote();
                                }"""),
                        text("method:trap-tested", """
                                Test-proven, not asserted-as-lore: as bob (staff), the direct call through the injected proxy DENIES with AccessDeniedException — and the internal call on the same line RETURNS the note, check never run. The island's specimen endpoint runs both live, and the suite pins the asymmetry:"""),
                        code("method:trap-test", "java",
                                "server/src/test/java/guide/presentation/demo/authz/AuthzDemoIntegrationTest.java — the trap, asserted: direct denied, internal walked through",
                                "server/src/test/java/guide/presentation/demo/authz/AuthzDemoIntegrationTest.java", """
                                @Test
                                void theSelfInvocationTrapIsRealAndAsserted_bobWalksThroughTheInternalCall() throws Exception {
                                    MockHttpSession session = actAs("bob");
                                    mockMvc.perform(get(SELF_INVOCATION).session(session))
                                            .andExpect(status().isOk())
                                            .andExpect(jsonPath("$.caller.username").value("bob"))
                                            .andExpect(jsonPath("$.directCall.checked").value(true))
                                            .andExpect(jsonPath("$.directCall.allowed").value(false))
                                            .andExpect(jsonPath("$.internalCall.checked").value(false))
                                            .andExpect(jsonPath("$.internalCall.allowed").value(true));
                                }"""),
                        text("method:trap-fixes", """
                                The fixes are structural, and they rhymed in topic 06: DON'T self-call guarded methods — split the bean so the guarded entry is only ever entered from outside (this guide's shape: the controller calls the use case; the use case never calls itself through annotations); or route through the proxy explicitly (self-injection, or AopContext.currentProxy() with exposeProxy — heavier, and a smell that the bean is doing two jobs). The audit trick: grep for calls to your own @PreAuthorize'd method names from within their own class — a real code-review step at security-minded shops.

                                One wiring fact completes the layer: a denial throws AccessDeniedException from INSIDE the handler, and Spring MVC offers it to @ControllerAdvice BEFORE the security filter chain's translation — so the ADVICE decides its wire shape. Without one, this app's generic fallback would render a 500 envelope (a denial is not a server fault); with it, every method-security 403 is the same {error:{code:forbidden,message,requestId}} every other failure uses:"""),
                        code("method:advice", "java",
                                "guide/presentation/demo/authz/AuthzMethodSecurityAdvice.java — Spring Security's denial, translated into the one error envelope",
                                "server/src/main/java/guide/presentation/demo/authz/AuthzMethodSecurityAdvice.java", """
                                @ExceptionHandler(AccessDeniedException.class)
                                ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception,
                                        HttpServletRequest request) {
                                    String requestId = requestId(request);
                                    log.warn("request denied: kind=forbidden status=403 requestId={} layer=method-security",
                                            requestId);
                                    return ResponseEntity.status(FailureKind.FORBIDDEN.httpStatus())
                                            .body(ErrorResponse.of(FailureKind.FORBIDDEN.errorCode(),
                                                    "access denied by method security — the @PreAuthorize/@PostAuthorize "
                                                            + "verdict was deny",
                                                    requestId, null));
                                }"""),
                        text("method:choosing-builtins", """
                                BUILT-IN VS CUSTOM predicates is the closing judgment of this section. Built-ins (hasRole & family) are auditable at a glance and enough for coarse gates; they rot when the expression grows past one line — a SpEL string is CODE nobody type-checks, and a 200-character expression is a policy you cannot review. The BEAN-referenced check is the escape hatch: the annotation stays a readable CONTRACT ("call authz.canPublish"), the logic lives in a tested, typed Java method, and the SpEL never grows past a call. Rule of thumb the island demonstrates: if the check needs the OBJECT (not just the role), it needs the bean.""")),
                section("permissions-and-scopes", "Permissions and scopes",
                        "Roles are the summary; permissions are the lines; scopes are someone else's permissions, delegated — three vocabularies that must never share a drawer.",
                        text("scopes:permissions", """
                                Zoom IN on the role bundle and you find PERMISSIONS — the fine-grained verbs (document:read, document:publish, invoice:approve) that roles merely package. The two-level model (user → roles → permissions) is what makes RBAC survivable at scale: people are assigned ROLES (few, stable, job-shaped), code checks PERMISSIONS (many, precise, refactorable), and re-bundling a role updates every holder at once. The cost is discipline: check the permission, not the role — code that asks hasRole('ADMIN') when it means hasAuthority('document:publish') hard-codes the bundle and turns the next re-organization into a code change. This guide's matrix keeps actions first-class (READ/LIST/PUBLISH) precisely so the checks are verb-shaped; a role only ever answers "which verbs does this bundle grant".

                                Zoom OUT and the failure mode of everything-is-a-role appears: role EXPLOSION — can-publish-on-weekends, temp-admin-view-only — each exception a new bundle nobody can reason about. The cure moves the EXCEPTIONS out of roles entirely: ownership (next section) answers "may I touch MY rows" without any role; feature FLAGS answer "is this enabled"; and the role matrix shrinks back to what people actually are. Design the matrix SMALL AND EXPLICIT: every cell justifiable in a sentence, every addition a deliberate act someone reviews — the same discipline as the dependency budget (topic 31)."""),
                        text("scopes:oauth", """
                                A SCOPE is none of the above, and the confusion is expensive. OAuth's scopes (topic 23 minted them in its tokens) are DELEGATED AUTHORIZATION: the permissions a USER granted to a CLIENT APP — "this calendar app may read my calendar" is the scope calendar:read: not "the app can read calendars" but "ALICE allowed THIS APP to read HER calendar". The subtlety the industry regularly fumbles: a scope is a CEILING on the delegation, not a grant — the request must still pass the app's own authorization (alice's delegate with calendar:read still cannot read bob's calendar); scope ≠ ROLE (a role describes the PRINCIPAL, a scope the DELEGATION's breadth); and audience matters (a token minted for client A, presented to service B, must be refused).

                                On the wire: the resource server validates the token (topic 23), a JwtAuthenticationConverter maps claims onto GrantedAuthorities, and the SAME machinery from this topic decides. That converter is where issued-trust meets local-policy, and where this guide deliberately stops: a real multi-client deployment adds it, and scope-shaped authorities (SCOPE_read) and role-shaped ones (ROLE_admin) coexist in one expression language, kept distinct by the naming conventions that opened the RBAC section. Last honesty note: scopes are strings the ISSUER chose — trust the signature, verify the vocabulary, never let a scope bypass your own policy.""")),
                section("ownership-the-object-level-check", "Ownership — the object-level check",
                        "Roles answer what KINDS of things you may do; ownership answers the row in your hands. Row-level security is where authorization stops being architecture and becomes data.",
                        text("ownership:row-level", """
                                Every authorization question eventually gets specific: not "may bob read documents" but "may bob read THIS document". That is ROW-LEVEL (object-level) authorization, and it has exactly two honest implementations. CHECK-AFTER-FETCH: run the query, hold the row, compare owner to caller — simple, and the shape @PostAuthorize formalizes; its risk is the middle state (the row HAS left the store — a bug downstream of the check, a serializer mistake, a log line, and it leaked). FILTER-IN-QUERY: make the authorization PART of the query — WHERE owner = :caller OR visibility = 'public' OR … — so unauthorized rows never exist as objects at all; what the caller cannot see, the caller cannot leak. The island's list endpoint is the filtered shape at the use-case layer:"""),
                        code("ownership:filtered-list", "java",
                                "guide/domain/authz/ListVisibleDocuments.java — the query-shapes-authz lesson: the policy filters, unseeable is unleakable",
                                "server/src/main/java/guide/domain/authz/ListVisibleDocuments.java", """
                                public List<VisibleDocument> execute(UserPrincipal caller) {
                                    return documentStore.findAll().stream()
                                            .filter(document -> ownershipPolicy.canRead(caller, document))
                                            .map(document -> VisibleDocument.of(document,
                                                    ownershipPolicy.readReason(caller, document)))
                                            .toList();
                                }"""),
                        text("ownership:policy", """
                                The policy itself is three rules in precedence order, and the PRECEDENCE is the design: OWNER first (your rows, full control — no role needed), ADMIN second (the escape hatch every real system needs and every audit log must surface), then TEAM-AND-TEAM-VISIBILITY (the staff role may read rows explicitly shared to the team — a grant that never reaches private rows and never grants publish). PUBLIC visibility is a read rule, not an ownership rule: anyone authenticated. Note what the policy does NOT say — that staff can publish, that owners need a role, that admin is checked before ownership; each absence is deliberate, and each would be a bug if inverted:"""),
                        code("ownership:policy-code", "java",
                                "guide/domain/authz/OwnershipPolicy.java — owner, admin, team+visibility: three rules, explicit precedence",
                                "server/src/main/java/guide/domain/authz/OwnershipPolicy.java", """
                                public boolean canRead(UserPrincipal caller, OwnedDocument document) {
                                    return switch (document.visibility()) {
                                        case PUBLIC -> true;
                                        case PRIVATE, TEAM -> caller.username().equals(document.ownerId())
                                                || caller.hasRole("admin")
                                                || (document.visibility() == OwnedDocument.Visibility.TEAM
                                                        && caller.hasRole(TEAM_ROLE));
                                    };
                                }

                                public boolean canPublish(UserPrincipal caller, OwnedDocument document) {
                                    return caller.username().equals(document.ownerId()) || caller.hasRole("admin");
                                }"""),
                        text("ownership:jpa", """
                                Push the same lesson into SQL and it becomes WHERE-clause discipline: check-after-fetch is SELECT * FROM docs WHERE id = ? followed by an if — the row crossed the wire unguarded; the filtered shape adds AND (owner = ? OR visibility = 'public' OR …) — one statement, no middle state. Spring Data JPA meets you there: SPECIFICATIONS (topic 18) compose the predicate programmatically — hasOwner(authentication.name).or(isPublic()) — and SecurityEvaluationContextExtension lets @Query SpEL reference the principal directly (…and o.ownerId = ?#{authentication.name}), so the authorization rides inside the typed query the filter built.

                                The trap both shapes share: DENYING IS NOT DELETING. A filtered list answers "what may I see"; a check-after-fetch 403 answers "this exists but is not yours" — and high-security systems collapse the two on purpose, answering 404 for unauthorized objects so EXISTENCE never leaks (enumerating ids cannot map your private surface). This guide keeps 403-with-reason for teachability and says so on every denial; the trade is yours to make consciously. What is NOT a trade: the 403-vs-404 decision must be made ONCE, centrally — an API that mixes them per-endpoint leaks by inconsistency.""")),
                section("deny-by-default-fail-closed", "Deny by default, fail closed",
                        "The safest system state is NO — and the discipline is arranging your code so that NO is what happens when nothing else does.",
                        text("deny:principle", """
                                DENY BY DEFAULT is the posture, FAIL CLOSED is the mechanism, and they are one habit: when anything is unknown, absent, or errored, the answer is NO. Unknown ACTION → deny (a new endpoint that forgot to register its action denies everyone — the SAFE direction: the bug reports itself instead of opening a hole). Unknown ROLE → deny. Unknown OBJECT → deny. The ERROR SIDE of every integration is the deny side: the policy engine times out → deny; the role lookup fails → deny. Systems fail OPEN by accident — a catch-block that returns true, an Optional the code orElse(true)s — and each accidental opening is a breach no test flags, because the OPEN path works beautifully.

                                The ALLOWLIST is deny-by-default's data shape: enumerate what is permitted, deny everything else — versus the DENYLIST, which enumerates forbidden things and admits everything unlisted. Denylists are always one entry behind: you block 'rm -rf', someone sends 'rm -rf '. Allowlists are smaller, reviewable line by line, and their FAILURE MODE is the safe one (a missing entry denies; a missing denylist entry admits). This guide's baseline is the inverted teaching example — permitAll fallthroughs, one protected island — and its own config says a real service flips that first. The evaluator that gives the island its spine:"""),
                        code("deny:evaluator", "java",
                                "guide/domain/authz/DenyByDefault.java — every early return denies; only explicit allowance allows",
                                "server/src/main/java/guide/domain/authz/DenyByDefault.java", """
                                public Verdict decide(UserPrincipal caller, String action, OwnedDocument document) {
                                    AuthzAction known = AuthzAction.fromWire(action).orElse(null);
                                    if (known == null) {
                                        return Verdict.deny("deny-by-default",
                                "unknown action '" + action + "' — not in the matrix, denied (fail closed)");
                                    }
                                    if (!rolePolicy.anyRoleAllows(caller.roles(), known)) {
                                        return Verdict.deny("role-policy",
                                "no role of " + caller.username() + " allows '" + known.wireName() + "'");
                                    }
                                    return switch (known) {
                                        case READ -> ownershipPolicy.canRead(caller, document)
                                                ? Verdict.allow("ownership-policy", ownershipPolicy.readReason(caller, document))
                                                : Verdict.deny("ownership-policy",
                                                        ownershipPolicy.readReason(caller, document));
                                        // …
                                    };
                                }"""),
                        text("deny:parse", """
                                Read that method top to bottom: EVERY early return except the last is a DENY — that shape IS the property. The action arrives as a STRING (the wire's native tongue), and the parse is deliberately an Optional, not an exception: an unknown verb is an ordinary deny with a reason, never a 500, never a special case the caller can distinguish. The enum keeps the KNOWN set closed at compile time; the Optional keeps the UNKNOWN set denied at runtime; together they are fail-closed parsing:"""),
                        code("deny:parse-code", "java",
                                "guide/domain/authz/AuthzAction.java — the known set closed at compile time, the unknown set denied at runtime",
                                "server/src/main/java/guide/domain/authz/AuthzAction.java", """
                                /** The deny-by-default parse: unknown strings return empty, never throw. */
                                public static Optional<AuthzAction> fromWire(String value) {
                                    if (value == null) {
                                        return Optional.empty();
                                    }
                                    return Arrays.stream(values())
                                            .filter(action -> action.wireName().equals(value.toLowerCase(Locale.ROOT)))
                                            .findFirst();
                                }"""),
                        text("deny:explain", """
                                The verdict record carries WHICH POLICY ANSWERED and WHY — not decoration, operability. Decisions that cannot explain themselves cannot be AUDITED ("who denied this?"), DEBUGGED ("why is carol denied?" — the reason is in the response), or TESTED against regressions (assert on checkedBy, not just allowed). The tour shows the reasons verbatim in 403 envelopes. Fail closed, explain always.""")),
                section("authorization-in-the-wild", "Authorization in the wild",
                        "Three gatekeepers at three altitudes, the policy engine when the matrix outgrows the codebase, and the audit log that makes every decision reviewable.",
                        text("wild:layers-recap", """
                                Production systems converge on the three-layer shape this island demonstrates. URL RULES are the perimeter: one config block, per-route, before any controller — right for COARSE gates (authenticated-only areas, admin-only prefixes), wrong for anything needing an argument or a row. ANNOTATIONS are the contract: @PreAuthorize beside the signature it guards, SpEL reaching parameters, beans, the context — right for PER-OPERATION rules, and the most readable place a policy can live. IN-CODE checks are the last line: the use case consulting the evaluator before it acts — mandatory for operations reachable WITHOUT an HTTP entry (jobs, listeners, other use cases), and the only layer that travels with the logic. The island's guarded read is that layer verbatim — Result in, typed failure out, the reason riding the failure:"""),
                        code("wild:guarded-action", "java",
                                "guide/domain/authz/ActAsGuardedAction.java — the last line: the policy inside the operation, failures as values",
                                "server/src/main/java/guide/domain/authz/ActAsGuardedAction.java", """
                                public Result<GuardedOutcome> execute(UserPrincipal caller, String action, String documentId) {
                                    OwnedDocument document = documentStore.findById(documentId).orElse(null);
                                    if (document == null) {
                                        return Result.failed(Failure.of(FailureKind.NOT_FOUND,
                                "no document '" + documentId + "'",
                                java.util.Map.of("checkedBy", "deny-by-default")));
                                    }
                                    DenyByDefault.Verdict verdict = evaluator.decide(caller, action, document);
                                    if (!verdict.allowed()) {
                                        return Result.failed(Failure.of(FailureKind.FORBIDDEN, verdict.reason(),
                                java.util.Map.of("checkedBy", verdict.checkedBy())));
                                    }
                                    return Result.success(perform(action, document, verdict));
                                }"""),
                        text("wild:placement", """
                                The placement judgment in one paragraph: put the check at the LOWEST layer that has everything the decision needs, and repeat it above only when the outer layer buys something — an early cheap denial, a readable contract, an audit point. URL rule + annotation + in-code on one operation is three different failure domains: the rule protects code that has not run, the annotation states intent where reviewers read, the in-code check protects callers that never crossed either. The island's publish route is the full stack: the URL rule keeps anonymous out, the annotation denies carol (role half) and bob (bean half) before the body runs, and the use case re-checks — a hypothetical scheduler invoking the same operation obeys the same verdict.

                                POLICY ENGINES are where this goes when the matrix outgrows the codebase: Open Policy Agent (OPA) and friends externalize policy into a DECISION SERVICE — your code sends (subject, action, resource, context), OPA answers allow/deny from Rego policies versioned apart from deploys. The trade: centralized, auditable, even product-editable policy — against a network hop (or sidecar), a second language, and a NEW failure domain whose error side must be DENY (an unreachable engine that fails open is worse than no engine). The practical path: start with the three layers in code (they are free); extract the EVALUATOR behind a port when rules churn faster than releases; reach for OPA when multiple SERVICES must share the policy."""),
                        text("wild:audit", """
                                AUDIT LOGGING closes the loop: authorization that is not logged is authorization you cannot investigate. Log the ALLOWS, not just the denies — the question after an incident is "what DID they reach", and a log of refusals answers nothing; log the DECISION POINTS (who, what action, which object, which layer, why) in a structured line greppable by user and by object; and log the ESCAPE-HATCH USES loudest — every admin override is where an investigation starts. This guide's shapes: verdicts carry checkedBy+reason (the audit fields and the API fields are the same fields, on purpose), and the advice logs every denial with its requestId — the correlation key across every log in the pipeline. One caution topic 25 expands: audit logs are themselves sensitive — they record who did what — so they inherit the access discipline they enforce.

                                Where to go from here: topic 25 hardens the SURROUNDINGS (headers, CORS, CSRF, rate limits, injection) so the decisions this topic taught survive contact with hostile traffic; the architecture topic (32) re-meets these layers as the dependency rule in action; and the terminal companion below walks every verdict in this topic by curl, envelope by envelope.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole island by curl: switch identities, read the live matrix, get denied three different ways, publish as admin, and watch the trap fire — plain curl only.",
                        text("terminal:intro", """
                                With the stack running (server on :8080), the tour walks every layer. The act-as switch is the vehicle: POST /api/demo/authz/act-as with a username establishes a session (cookie jar plays the browser) — no password, because authorization assumes authentication happened; switching is how you watch the SAME endpoint answer differently per identity. Seeded users: alice (admin+staff), bob (staff), carol (reader)."""),
                        code("terminal:act-as-matrix", "bash",
                                "act as alice, then read the live matrix (the island's open reference door)",
                                null, """
                                # act as alice — the jar plays the browser; note: no password, tour-only
                                curl -s -c /tmp/authz.jar -X POST localhost:8080/api/demo/authz/act-as \\
                                  -H 'content-type: application/json' \\
                                  -d '{"username":"alice"}'
                                # → {"user":{"username":"alice","roles":["admin","staff"]},"via":"session (act-as)"}

                                # the role→action matrix, rendered from the REAL RolePolicy
                                curl -s localhost:8080/api/demo/authz/matrix
                                # → …"roleMatrix":[{"role":"reader","allowedActions":["read","list"]},
                                #     {"role":"staff","allowedActions":["read","list","publish"]}, …]

                                # one user's full verdict column — computed, not hardcoded
                                curl -s localhost:8080/api/demo/authz/matrix \\
                                  | python3 -c 'import json,sys; print([r for r in json.load(sys.stdin)["rows"] if r["username"]=="carol" and r["action"]=="publish"])'
                                # → [{"allowed":false,"checkedBy":"role-policy",
                                #      "reason":"no role of carol allows 'publish'"}]"""),
                        code("terminal:filtering", "bash",
                                "the query-shapes-authz lesson: same endpoint, three different lists",
                                null, """
                                # alice (admin) sees all six documents — just the ids, for the comparing eye
                                curl -s -b /tmp/authz.jar localhost:8080/api/demo/authz/documents \\
                                  | python3 -c 'import json,sys; print([d["id"] for d in json.load(sys.stdin)["documents"]])'
                                # → ["doc-alice-private","doc-alice-team","doc-bob-private",
                                #     "doc-bob-team","doc-carol-private","doc-roadmap"]

                                # switch to bob (staff): alice's and carol's PRIVATE rows vanish
                                curl -s -c /tmp/authz.jar -b /tmp/authz.jar -X POST localhost:8080/api/demo/authz/act-as \\
                                  -H 'content-type: application/json' -d '{"username":"bob"}'
                                # → {"user":{"username":"bob", …}} — the switch took
                                curl -s -b /tmp/authz.jar localhost:8080/api/demo/authz/documents
                                # → each doc carries its id and its visibleBecause: "team visibility + the staff role",
                                #     "caller owns this document", … — alice's and carol's private rows are GONE

                                # carol (reader): the team-shared rows vanish too — no staff role
                                curl -s -c /tmp/authz.jar -b /tmp/authz.jar -X POST localhost:8080/api/demo/authz/act-as \\
                                  -H 'content-type: application/json' -d '{"username":"carol"}' >/dev/null
                                curl -s -b /tmp/authz.jar localhost:8080/api/demo/authz/documents \\
                                  | python3 -c 'import json,sys; print([d["id"] for d in json.load(sys.stdin)["documents"]])'
                                # → ["doc-carol-private","doc-roadmap"]"""),
                        code("terminal:denials", "bash",
                                "three denials, three layers, one envelope shape",
                                null, """
                                # anonymous: the URL-RULE layer answers, before any controller
                                curl -s -o /dev/null -w '%{http_code}\\n' localhost:8080/api/demo/authz/documents
                                # → 401

                                # back to bob — the in-use-case layer: a policy 403 in OUR envelope
                                curl -s -c /tmp/authz.jar -b /tmp/authz.jar -X POST localhost:8080/api/demo/authz/act-as \\
                                  -H 'content-type: application/json' -d '{"username":"bob"}' >/dev/null
                                curl -s -b /tmp/authz.jar localhost:8080/api/demo/authz/documents/doc-alice-private
                                # → {"error":{"checkedBy":"ownership-policy",
                                #     "message":"private to alice (and admins) — caller is neither",
                                #     "code":"forbidden","requestId":"…"}}

                                # the ANNOTATION layer, bean half: bob (staff) passes hasAnyRole,
                                # then @authz.canPublish denies — not owner, not admin
                                curl -s -b /tmp/authz.jar -X POST \\
                                  localhost:8080/api/demo/authz/documents/doc-alice-private/publish
                                # → {"error":{"code":"forbidden",
                                #     "message":"access denied by method security — …","requestId":"…"}}

                                # the ANNOTATION layer, role half: carol (reader) dies at hasAnyRole
                                curl -s -c /tmp/authz.jar -b /tmp/authz.jar -X POST localhost:8080/api/demo/authz/act-as \\
                                  -H 'content-type: application/json' -d '{"username":"carol"}' >/dev/null
                                curl -s -b /tmp/authz.jar -X POST \\
                                  localhost:8080/api/demo/authz/documents/doc-carol-private/publish
                                # → {"error":{"code":"forbidden", …}} — same verdict, role half"""),
                        code("terminal:publish-and-trap", "bash",
                                "publish as admin (the escape hatch), then watch the self-invocation trap fire",
                                null, """
                                # alice publishes bob's private doc — owner-or-admin, admin side
                                curl -s -c /tmp/authz.jar -b /tmp/authz.jar -X POST localhost:8080/api/demo/authz/act-as \\
                                  -H 'content-type: application/json' -d '{"username":"alice"}' >/dev/null
                                curl -s -b /tmp/authz.jar -X POST \\
                                  localhost:8080/api/demo/authz/documents/doc-bob-private/publish
                                # → {"enforcedBy":"method security (@PreAuthorize: role SpEL + @authz bean), …",
                                #     "effect":"visibility private→public — now readable by all"}

                                # the @PostAuthorize specimen: alice is ADMIN but not the OWNER —
                                # returnObject.owner() == authentication.name is blunter than policy
                                curl -s -b /tmp/authz.jar \\
                                  localhost:8080/api/demo/authz/documents/doc-bob-team/owner-view
                                # → {"error":{"code":"forbidden", …}}

                                # the trap, live as bob: direct call checked, internal call NOT
                                curl -s -c /tmp/authz.jar -b /tmp/authz.jar -X POST localhost:8080/api/demo/authz/act-as \\
                                  -H 'content-type: application/json' -d '{"username":"bob"}' >/dev/null
                                curl -s -b /tmp/authz.jar \\
                                  localhost:8080/api/demo/authz/specimens/self-invocation
                                # → {"caller":{"username":"bob", …},
                                #     "directCall":{"checked":true,"allowed":false},
                                #     "internalCall":{"checked":false,"allowed":true,"returned":"the admin-only note …}}"""),
                        text("terminal:close", """
                                A dozen curls later you have watched every verdict this topic taught: an identity switch re-shaping a list, three layers refusing three requests in ONE envelope shape, an admin escape hatch changing policy state, a return-object check blunter than the policy, and a security annotation silently skipped by an internal call. The machinery lives in domain/authz (policies, evaluator, use cases), data/authz (the seeded documents), presentation/demo/authz (the island and the denial advice), and config/ (the wiring, @EnableMethodSecurity, the one sanctioned rule-append to the baseline chain). Topic 25 hardens the world around these decisions — headers, CORS, CSRF, rate limits — so the verdicts survive hostile traffic.""")));
    }
}
