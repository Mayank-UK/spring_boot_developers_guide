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
 * Topic 40 — Scaling. Group platform, order 11. The whole topic defends
 * one structural claim: SCALE IS DECIDED BY WHERE STATE LIVES. A service
 * whose callers' truth lives inside its own heap can only grow by buying
 * bigger boxes; a service whose truth lives in shared stores can grow by
 * adding copies. The live demo (scaling:stateless) MEASURES the
 * difference — two simulated replicas behind one router, one shared
 * external store, and a per-instance memory counter that diverges on
 * purpose: routing freely works the moment the value that matters is
 * externalized, and the report shows the alternative losing history as a
 * number, not a vibe. Around that core: load balancing (the proxy and
 * the k8s Service), replicas and the HPA (this repo's real manifests),
 * data at scale (the shared stores and the connection arithmetic that
 * caps your fleet), CDN/edge, backpressure as the honest answer to
 * overload, and capacity as a planning discipline — closing with the
 * curl + k6/ab terminal companion.
 */
public final class ScalingTopic {

    private ScalingTopic() {
    }

    public static Topic topic() {
        return new Topic("scaling", "Scaling",
                "Scale is an architecture property decided by where state lives: statelessness "
                        + "first (measured live — two replicas, one shared store, a private-memory "
                        + "counter that diverges on purpose), then load balancing, replicas and the "
                        + "HPA, data at scale, the CDN edge, backpressure under overload, and capacity "
                        + "as a planning number rather than a hope.",
                TopicGroupId.PLATFORM, 11,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("what-scaling-means", "What scaling actually means",
                        "Not more hardware — more COPIES. Scaling is the property of absorbing more load by adding resources without rewriting the service, and it is decided by one design fact: where the state lives.",
                        text("ws:growth", """
                                Scaling has two directions, and the vocabulary matters because the engineering is completely different. SCALING UP (vertical) buys a bigger box: more cores, more RAM, faster disks — the same single instance, amortizing nothing. SCALING OUT (horizontal) adds more copies of the service side by side and puts something in front to spread the load. Scaling up is trivial until it ends: the box gets expensive, then unavailable, and a hard ceiling remains (the biggest machine your cloud sells). Scaling out looks linear and cheap — until you try it with a service that was not built for it and discover that the second copy breaks users in ways the first never did. That discovery is what this topic is about, because the difference between the two experiences is not luck or tooling: it is ONE design decision made early — where does the state live?

                                THE REAL DEFINITION, stripped of hardware talk: a service scales when you can add capacity (a copy, a cache layer, a shard) WITHOUT changing its code and WITHOUT callers noticing. Read that twice, because it makes scaling an ARCHITECTURE property, not an operations one. The request pipeline topics already built the per-request machinery (routing, validation, the error envelope); performance tuned one instance of it; reliability hardened one instance of it. Scaling asks the orthogonal question: what happens when there are N instances of it, behind one address, serving the same users interchangeably? Everything that answers "any instance can serve any request" is this topic. Everything that answers "but the data has to be somewhere" is the honest half of the answer — the shared stores, which become both the enabler of scale-out and its bottleneck.

                                Two laws frame the ceiling so the rest of the topic stays honest. AMDAHL'S LAW: the speedup from adding parallelism is capped by the sequential fraction — if 10% of every request is a single shared lock (a session store only one box can write, a singleton file), the fleet's throughput ceiling is 10x no matter how many copies you buy. And the USL insight (Universal Scalability Law): past a point, adding copies makes throughput WORSE, because coordination cost (coherence, contention on shared state) grows with the fleet. Both laws say the same thing in different accents: the shared, coordinated, stateful part of your system is the part that decides how far you scale. Which is why the first, longest section of this topic is about removing state from the application tier — and why every later section (load balancing, replicas, data, edge, backpressure, capacity) keeps returning to the same question: is this component a copy, or is it the shared thing?""")),
                section("statelessness", "Statelessness: any replica, any request",
                        "A request that carries everything the server needs, answered by a server that remembers nothing about the caller — the property that makes the rest of the topic possible. The demo measures it: two replicas, one shared store, and a private-memory counter that loses your history the moment routing changes.",
                        text("st:definition", """
                                STATELESS means: every request is SELF-SUFFICIENT. It arrives carrying everything the server needs to answer it (the auth credential, the resource id, the page cursor), and the server answers without depending on anything a PREVIOUS request left in its own memory. Not "no state exists" — state always exists somewhere (carts, sessions, files, counters) — but "no CALLER-AFFINE state lives inside the application instance." That distinction is the whole topic's load-bearing wall, so it deserves the precise version: an instance may hold EPHEMERAL state (in-flight requests, a local metrics counter, a warm cache it is prepared to lose) but must never hold DURABLE state that a caller's next request depends on (a session map, a shopping cart, "the file I uploaded a minute ago"). The test is mechanical and worth memorizing: KILL THE INSTANCE MID-AFTERNOON. If every caller's next request still works — routed to any other copy, answered correctly — the service is stateless. If some users start over, it was not, and the load balancer has been quietly papering over the fact.

                                The classic offender is the HTTP SESSION. The authentication topic walked both worlds deliberately: the session model (server memory keyed by a cookie) is pleasant and fast, and it is exactly the thing that refuses to scale out — instance A holds the session, the load balancer routes the next request to instance B, and B has never heard of you: a logged-in user is suddenly anonymous, a half-filled cart resets. The standard workarounds are both concessions: STICKY SESSIONS pin each user to one instance (the load balancer works harder, rebalancing gets worse, one hot instance can become the whole workload, and the pod's death still loses the user) or SESSION REPLICATION copies the map everywhere (every write multiplied by fleet size, eventually-consistent sessions, a coordination storm the USL warned about). The scalable answer is to move the state OUT: a session store every instance reads (Spring Session over redis is the packaged version), or better for APIs, tokens that carry the state themselves.

                                This app's own JWT resource server is the token shape. The access token is a claims set SIGNED by the issuer — the server verifies a signature, it does not LOOK ANYTHING UP in memory:"""),
                        code("st:jwt-claims", "java",
                                "guide/domain/auth/JwtMinter.java — the whole stateless trick: identity as signed claims, no server-side lookup",
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
                                jwt.sign(new MACSigner(secretHmacKey));
                                return new MintedAccessToken(jwt.serialize(), claimsPreview(claims), now, expiresAt);"""),
                        text("st:jwt-reading", """
                                Every verifying replica needs exactly one thing: the SAME secret the minter signed with (here, the yaml-configured HS256 key — which itself becomes a scaling fact: rotate it, and every replica must learn the new one together). No session map, no affinity, no lookup — pod #6 boots and verifies tokens as well as pod #1, because the caller's state travels WITH the caller. The app's security baseline is honest about the exception it keeps: sessions exist for exactly one teaching demo, and the chain's session posture says so —
                                """),
                        code("st:session-posture", "java",
                                "guide/config/SecurityConfig.java — the deliberate posture: stateless-in-practice, sessions only where a demo teaches them",
                                "server/src/main/java/guide/config/SecurityConfig.java", """
                                // sessions exist for exactly one demo (the session island); every
                                // other surface is stateless-in-practice and never touches one
                                .sessionManagement(session -> session
                                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                                .securityContext(context -> context
                                        .securityContextRepository(new HttpSessionSecurityContextRepository()))"""),
                        text("st:honest-exceptions", """
                                Now the honesty list, because "stateless" is often oversold. ONE: statelessness has a PRICE at logout/revocation — a valid-signed token cannot be un-issued, so revocation needs shared state (a denylist) or short expirations plus rotation; this app chose short-lived access tokens, which is why the refresh grant exists. TWO: some flows are inherently stateful, and the honest move is to externalize them, not to fake them. The refresh-token rotation is the perfect specimen — theft detection REQUIRES remembering which refresh tokens were already spent, so that memory lives in a store (in production: redis or the database, SHARED by every replica), not in any instance's heap:
                                """),
                        code("st:refresh-store", "java",
                                "guide/domain/auth/ExchangeRefreshToken.java — the stateful exception done right: rotation memory lives in a store every replica shares",
                                "server/src/main/java/guide/domain/auth/ExchangeRefreshToken.java", """
                                RefreshFamilyStore.StoredRefreshToken stored =
                                        refreshFamilies.findByTokenHash(OpaqueTokens.sha256Hex(refreshToken));
                                if (stored == null) {
                                    return new Failed<>(Failure.of(FailureKind.UNAUTHORIZED,
                                            "unknown refresh token"));
                                }
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
                        text("st:externalize", """
                                Read that as the pattern for everything stateful: the store is an ADDRESS, not an instance. The compose files wire exactly that arrangement for the data services — every server replica points at the same redis and mongo BY NAME, so a cache increment or a spent-token flag lands where the next request, from any replica, will find it:
                                """),
                        code("st:compose-stores", "yaml",
                                ".docker/docker-compose.yml — the shared-store rails: replicas reach the same redis/mongo by service name, never by instance memory",
                                ".docker/docker-compose.yml", """
                                # Topic 21 (caching): the shared stores by service name — a cache every
                                # replica shares (and a Mongo reachable in compose) start with these
                                # two env flips; application.yml keeps localhost defaults.
                                - SPRING_DATA_MONGODB_URI=mongodb://mongo:27017/guide
                                - SPRING_DATA_REDIS_HOST=redis"""),
                        text("st:demo-intro", """
                                The live demo measures all of it instead of asserting it. Two server personas (a simulated second replica — both live inside this one JVM, and the report's process identity is the honesty note) sit behind one router you control: round-robin (what a load balancer with no affinity does) or pinned to a persona (what a sticky session forces). Every persona keeps its own PRIVATE request counter — server-local state that diverges by construction, exactly like real replicas' metrics. The one value that MATTERS can live in either world: the SHARED external store (the redis arrangement — its increment is the same from anywhere), or the serving persona's OWN memory (the session-in-heap arrangement). The engine is three lines of routing arithmetic:
                                """),
                        code("st:demo-engine", "java",
                                "guide/domain/scaling/ProbeStatelessness.java — the measured claim: local memory answers alone, the shared store answers for everyone",
                                "server/src/main/java/guide/domain/scaling/ProbeStatelessness.java", """
                                InstancePersona served = sticky ? personaByShortId(instanceParam.trim().toLowerCase(Locale.ROOT))
                                        : roundRobinPick();
                                long servingLocal = served.incrementLocal();
                                long reported = LOCAL.equals(stateHome) ? servingLocal : externalStore.increment();
                                return Result.success(build(served, sticky, stateHome, reported));"""),
                        code("st:demo-wiring", "java",
                                "guide/config/ScalingDemoConfig.java — the arrangement in one bean: two personas, one shared store, the real process identity",
                                "server/src/main/java/guide/config/ScalingDemoConfig.java", """
                                @Bean
                                ProbeStatelessness probeStatelessness() {
                                    return new ProbeStatelessness(
                                            List.of(new InstancePersona("server-a"), new InstancePersona("server-b")),
                                            new ExternalCounterStore(),
                                            ProcessIdentity.current());
                                }"""),
                        demo("st:demo", "scaling:stateless", "Prove statelessness, measured",
                                "Route requests through the simulated two-replica fleet: round-robin or pinned, value in the shared external store or in the serving replica's own memory. Watch the per-instance counters diverge (harmless — ephemeral) while the shared store keeps one answer for everyone — then switch the value to local, pin to server-a, send a few requests, and route to server-b: the caller's number goes BACKWARDS, and the report shows how much history a local-memory world just lost to routing. Reset rewinds the fleet."),
                        text("st:verdict", """
                                What the run will show, so you can check it against expectations. With the value EXTERNAL: send four requests under round-robin — each persona's private counter shows 2, the shared store shows 4, and the caller was told 1, 2, 3, 4: one monotonic truth regardless of routing. The private counters HAVE diverged from every request (2 vs 2, then 3 vs 2 if you pin one) — and nothing breaks, which is the second lesson: divergence in ephemeral state is not a bug; DEPENDENCE on it is. With the value LOCAL: pin to server-a, send three requests (told 1, 2, 3), then route one to server-b — the caller is told 1, and the report's lostIfLocal field shows 2: two-thirds of the history evaporated not because anything failed, but because the load balancer did its job. That number — not an outage, not an error, just a silently smaller answer — is what "sticky sessions or nothing" actually costs, and why statelessness comes first in every scaling checklist. The client half is one GET whose query params ARE the experiment:
                                """),
                        code("st:client", "ts",
                                "client/src/ui/demos/scaling/stateless_demo.tsx — the experiment as a URL: store + routing, one GET, no client-side math",
                                "client/src/ui/demos/scaling/stateless_demo.tsx", """
                                const sendRequest = useCallback(() => {
                                  setPanel({ kind: 'loading' });
                                  const instanceParam = routingMode === 'round-robin' ? '' : `&instance=${routingMode}`;
                                  void safeRepositoryCall(async () =>
                                    parseProbe(await requestJson(config, `/demo/scaling/stateless?store=${storeMode}${instanceParam}`)),
                                  ).then((result) => {
                                    if (result.ok) {
                                      applyProbe(result.value);
                                      setPanel({ kind: 'idle' });
                                    } else {
                                      setPanel({ kind: 'error', failure: result.failure });
                                    }
                                  });
                                }, [applyProbe, config, routingMode, storeMode]);"""),
                        text("st:close", """
                                One scope note before moving on, because the word gets abused: STATELESSNESS IS ABOUT THE APPLICATION TIER. The system still has state — sessions-in-redis, carts-in-postgres, tokens-in-cookies — and that state's stores have their OWN scaling stories (the data section takes those up). What you bought is the ability to treat application instances as CATTLE: interchangeable copies you can add, remove, replace, and drain at will, which is precisely the property the next three sections spend — first on spreading load across the copies, then on running and sizing the fleet itself.""")),
                section("load-balancing", "Load balancing: one address, many copies",
                        "Something must stand in front of the fleet and spread the load — the reverse proxy in compose, the Service in k8s. Algorithms matter less than the two contracts: health-driven membership and affinity-free routing.",
                        text("lb:why", """
                                The moment there are two copies, there is a new component: the thing with ONE public address that decides, per request, which copy answers. Load balancing is that decision, and its design space is smaller than it looks. BY LAYER: an L4 balancer spreads TCP streams by IP and port (fast, blind to paths and headers — a nodeport service, a cloud NLB); an L7 balancer reads HTTP and routes by path, host, or header (the reverse proxy, the ingress — slower per byte, infinitely smarter). BY ALGORITHM: round-robin (fair over time, blind to load), least-connections (sends work where in-flight work is lowest — the honest default when request costs vary), weighted (when boxes differ), and consistent hashing (pins by key — not for sessions you should not have, but for CACHE locality: the request that hashed to replica A last time may find its warm cache still there). The choice matters less than teams fear; the two CONTRACTS around it matter more.

                                CONTRACT ONE — MEMBERSHIP IS HEALTH-DRIVEN. A balancer that still routes to a dead copy is an outage machine. The k8s answer is the readiness gate the deployment topic installed: a pod joins the Service's endpoint list only when its readiness probe passes, and leaves the instant it fails — the Deployment's probes are the balancer's truth, not a hand-maintained list. CONTRACT TWO — AFFINITY-FREE BY DEFAULT. The previous section earned the right to route anywhere; the balancer should exercise it (round-robin or least-connections), and sticky configuration should exist only as a labeled concession for the one legacy thing you have not externalized yet. This repo's own edge does the L7 job twice — in compose, nginx proxies by service name (docker's DNS then spreads across that service's containers — a one-line load balancer):
                                """),
                        code("lb:nginx", "nginx",
                                ".docker/nginx.conf — compose's balancer: proxy by service name, docker DNS spreads the copies",
                                ".docker/nginx.conf", """
                                # Proxy API calls to the Spring Boot server (compose service name).
                                location /api/ {
                                    proxy_pass http://server:8080;
                                    proxy_http_version 1.1;
                                    proxy_set_header Upgrade $http_upgrade;
                                    proxy_set_header Connection 'upgrade';
                                    proxy_set_header Host $host;
                                    proxy_cache_bypass $http_upgrade;
                                }"""),
                        text("lb:service-intro", """
                                …and in the cluster, the Service object promotes that idea to a first-class primitive: a stable virtual address (and DNS name) whose endpoint list follows the pods automatically — replicas come and go during rollouts and reschedules, and no client of the Service ever re-configures:
                                """),
                        code("lb:service", "yaml",
                                "deploy/k8s/service.yml — the stable front door: selector-driven membership, named port indirection",
                                "deploy/k8s/service.yml", """
                                spec:
                                  type: ClusterIP
                                  selector:
                                    app.kubernetes.io/name: springboot-developers-guide
                                    app.kubernetes.io/component: server
                                  ports:
                                    - name: http
                                      port: 80
                                      targetPort: http"""),
                        text("lb:verdict", """
                                Two operational notes close the section. GRACEFUL DRAIN is a load-balancing feature, not just a shutdown one: the deployment topic's preStop sleep + readiness failure choreography exists so the balancer stops sending NEW requests before the old pod dies mid-response — scale events (not just deploys) exercise this constantly, which is one more reason statelessness matters: a request that retries against a different replica after a mid-drain failure must WORK. And RETRIES CUT BOTH WAYS: a balancer or client that blindly retries a non-idempotent POST can double-charge a customer; the api-design topic's idempotency keys are the other half of this section — safe horizontal retry is a JOINT property of the balancer's re-route and the API's idempotency discipline. With spreading understood, the next question is the fleet itself: how many copies, kept how healthy, grown how fast.""")),
                section("replicas", "Replicas: the fleet and its autopilot",
                        "N identical copies of the same stateless image — the Deployment runs them, the rollout contract replaces them safely, and the HPA grows them on measured utilization. This repo's manifests are the specimen.",
                        text("rp:idea", """
                                A REPLICA is one pod running the same immutable image as its siblings, interchangeable because the statelessness section made it so. The Deployment object is the fleet's contract, and its first line is the business decision — how many copies:
                                """),
                        code("rp:deployment", "yaml",
                                "deploy/k8s/deployment.yml — the fleet size plus the rollout contract: never below desired, surge one extra",
                                "deploy/k8s/deployment.yml", """
                                spec:
                                  replicas: 2
                                  # The rollout contract: never drop a pod below desired (maxUnavailable: 0)
                                  # while surging one extra (maxSurge: 1) — combined with readiness gating
                                  # this is zero-downtime deploys in four lines of yaml.
                                  strategy:
                                    type: RollingUpdate
                                    rollingUpdate:
                                      maxUnavailable: 0
                                      maxSurge: 1"""),
                        text("rp:probes", """
                                Two replicas is not a scaling strategy — it is an AVAILABILITY floor (one pod can die and the service answers; the rollout contract can replace one and nobody notices). Note what the pair really buys: every copy must pass the gate before carrying traffic, and the gate is the probe pair the deployment topic installed — liveness (restart me) and readiness (stop routing to me), health-driven membership as the load balancer section demanded:
                                """),
                        code("rp:probe-pair", "yaml",
                                "deploy/k8s/deployment.yml — membership gating: liveness vs readiness, the pair the Service listens to",
                                "deploy/k8s/deployment.yml", """
                                livenessProbe:
                                  httpGet:
                                    path: /actuator/health/liveness
                                    port: http
                                  periodSeconds: 10
                                  failureThreshold: 3
                                readinessProbe:
                                  httpGet:
                                    path: /actuator/health/readiness
                                    port: http
                                  periodSeconds: 5
                                  failureThreshold: 2"""),
                        text("rp:hpa-intro", """
                                Beyond the floor, the count should follow load, and the HPA is the autopilot: it watches a utilization signal against the Deployment's resource REQUESTS and drives replicas between bounds. This repo's real policy reads like a sentence — never fewer than two, never more than six, add pods at 70% average CPU, and think for five minutes before shrinking:
                                """),
                        code("rp:hpa", "yaml",
                                "deploy/k8s/hpa.yml — the scale policy: 2–6 replicas on 70% CPU, with a scale-down stabilization window",
                                "deploy/k8s/hpa.yml", """
                                spec:
                                  scaleTargetRef:
                                    apiVersion: apps/v1
                                    kind: Deployment
                                    name: server
                                  minReplicas: 2
                                  maxReplicas: 6
                                  metrics:
                                    - type: Resource
                                      resource:
                                        name: cpu
                                        target:
                                          type: Utilization
                                          averageUtilization: 70
                                  behavior:
                                    scaleDown:
                                      stabilizationWindowSeconds: 300"""),
                        text("rp:verdict", """
                                Three reading notes so the yaml does not oversell itself. ONE — the HPA's math runs against REQUESTS, not usage: 70% of the 250m the Deployment reserved (the capacity section returns to why that number is the fleet's real currency); a Deployment without honest requests has an HPA steering blind. TWO — scale UP fast, scale DOWN reluctantly (that 300s window): a fleet that sheds pods on every traffic dip then scrambles to re-add them thrashes exactly when users are watching, and each cold pod pays JVM boot, warm-up, and pool-building before it carries a single request. THREE — CPU is the default signal but not always the right one: for a service whose queue lives elsewhere (a database-bound API), a custom metric (p95 latency, queue depth, active connections) steers closer to the actual constraint. In compose-land the same lever exists crudely — `docker compose up --scale server=3` — and this repo's compose files deliberately pin container_name, which refuses to scale; the k8s manifests are the real specimen. What the autopilot quietly assumes, and what the next section confronts: SIX replicas only help if the thing they share can take six replicas' worth of traffic.""")),
                section("data-at-scale", "Data at scale: the shared thing",
                        "Every replica points at the same stores — which makes those stores both the enabler and the ceiling. Connection arithmetic, shared caches, and the discipline of keeping the app tier stateless while the data tier scales on its own terms.",
                        text("da:shared-ceiling", """
                                The statelessness section moved caller state OUT of the instances; this section is about where it landed — and the fact that the shared stores now carry the fleet's entire coordination load. The arrangement itself is already wired in this repo: every replica reaches the same redis and mongo by configuration, with localhost defaults outside compose:
                                """),
                        code("da:stores-yml", "yaml",
                                "server/src/main/resources/application.yml — the shared stores as config: every replica, same hosts, twelve-factor overrides",
                                "server/src/main/resources/application.yml", """
                                data:
                                  redis:
                                    host: ${SPRING_DATA_REDIS_HOST:localhost}
                                    port: ${SPRING_DATA_REDIS_PORT:6379}
                                  mongodb:
                                    uri: ${SPRING_DATA_MONGODB_URI:mongodb://localhost:27017/guide?serverSelectionTimeoutMS=2000&connectTimeoutMS=2000}"""),
                        text("da:connection-math", """
                                Now the arithmetic that caps fleets, using this repo's real numbers. Each server replica opens its own HikariCP pool against PostgreSQL — the pool the performance topic sized as a budget:
                                """),
                        code("da:hikari", "yaml",
                                "server/src/main/resources/application.yml — the per-replica pool: the budget every replica multiplies",
                                "server/src/main/resources/application.yml", """
                                datasource:
                                  url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/guide}
                                  # … username and password ride the same twelve-factor placeholders
                                  hikari:
                                    maximum-pool-size: 8"""),
                        text("da:verdict", """
                                Multiply before you celebrate: the HPA's ceiling of six replicas is six pools, 6 × 8 = 48 connections, against PostgreSQL's default max_connections of 100 — WITH the dev tools, the migrations, and the test suite's Testcontainers siblings drawing from the same hundred. The fleet fits. Double the HPA ceiling and it does not; nobody's pod errors — requests just QUEUE for connections (the performance topic's invisible wait), latency climbs, and the dashboards blame the database for what is actually an arithmetic sin. This is the general shape of data-at-scale: the application tier scales by copying, the data tier does not, so every copy MULTIPLIES the load on the shared thing. The remedies, in the order they pay. ONE — keep the per-replica budget honest (8, not 32: the performance topic's arithmetic stands, now multiplied by fleet size). TWO — cache the shared reads: redis in front of postgres is itself a scale lever (the caching topic's decision tree), and a shared cache is one of the few things that gets MORE useful per replica added. THREE — scale the data tier on its own terms: read replicas for read-heavy loads (with the honesty that reads become eventually consistent), sharding for write volume that one primary cannot take (with the honesty that cross-shard queries and transactions now cost design work), and neither one taken lightly — the advanced-architecture topic returns to the consistency bill. FOUR — mind what replicas do to caches and sessions: an in-instance cache is per-copy (a 6-replica fleet with a 60-second local cache serves six slightly-different truths — sometimes fine, sometimes the bug you hunt for a day; the caching topic's invalidation section is the antidote), while a shared cache is one truth with a network hop. The pattern to internalize: the app tier is cattle BECAUSE the data tier is the single careful thing — spend your design attention there, in proportion.""")),
                section("cdn-edge", "CDN and the edge: serve from where the user is",
                        "Bytes that do not reach your fleet cost your fleet nothing. Static assets on a CDN with immutable hashed names, TLS and routing terminated at the edge — the same nginx job, promoted geographically.",
                        text("cd:idea", """
                                A CDN is load balancing's geographic extension: a mesh of PoPs (points of presence) that answer from wherever is closest, so the static half of your product — the React bundle this guide's client ships, the images, the fonts — travels tens of milliseconds instead of hundreds, and never touches the origin fleet at all. The mechanics are pure HTTP caching (the caching topic built the vocabulary: Cache-Control, freshness, validation), pointed at one deliberate property of build output: ASSETS ARE IMMUTABLE. The bundler content-hashes every filename (main-a3f9c2.js), so the file's name IS its content's fingerprint — which licenses the strongest caching header that exists (Cache-Control: public, max-age=31536000, immutable: "never ask again for a year"), because "again" cannot produce a different answer. Deploys change the names; index.html (never long-cached, always revalidated) points at the new ones. That one pairing — hashed assets cached forever at the edge, tiny entry documents always fresh — is the whole static-delivery story, and it is why client builds hash their output by default.

                                The edge also terminates the things you do not want repeated in every replica: TLS (certificates live at the edge — the ingress's tls block and its ssl-redirect annotation do this job in-cluster; a CDN or cloud load balancer does it in front), and routing (path-based splits, redirects, and the API/staffing boundary). This repo's nginx is the miniature of the pattern — static files served from local disk with SPA fallback, API calls proxied onward — and the ingress is its cluster promotion:
                                """),
                        code("cd:ingress", "yaml",
                                "deploy/k8s/ingress.yml — the edge in-cluster: TLS termination, forced https, name-based routing to the Service",
                                "deploy/k8s/ingress.yml", """
                                annotations:
                                  # Force https:// — plain HTTP answers one redirect, nothing else.
                                  nginx.ingress.kubernetes.io/ssl-redirect: "true"
                                spec:
                                  ingressClassName: nginx
                                  tls:
                                    - hosts:
                                        - guide.example.com
                                      secretName: server-tls"""),
                        code("cd:nginx-static", "nginx",
                                ".docker/nginx.conf — the miniature: static from local disk, SPA fallback, never the origin for unknowns",
                                ".docker/nginx.conf", """
                                root   /usr/share/nginx/html;
                                index  index.html;

                                # …

                                location / {
                                    try_files $uri $uri/ /index.html;
                                }"""),
                        text("cd:verdict", """
                                The honest boundary: a CDN serves the SHARED, IMMUTABLE, GET-half of your product — it is not an API cache. API responses are per-user and per-permission, so pushing them to the edge is usually wrong unless they are deliberately public (an RSS feed, a released openapi.json) or carefully keyed (a short max-age on a popular collection endpoint — a judgment call the caching topic's decision tree owns). What the edge always buys an API, regardless: TLS termination, keep-alive reuse, and distance — and, with the compression topic's numbers in hand, gzipped transfer from a nearby PoP instead of identity bytes across an ocean. The division of labor to remember: EDGE for the immutable and public, ORIGIN for the true and per-user, and the boundary between them drawn by cache headers you set on purpose.""")),
                section("backpressure", "Backpressure: honesty under overload",
                        "A fleet at capacity has three options — queue, shed, or collapse — and only two of them are designs. Bounded buffers, mid-stream caps, and dropping the dead: the guide's own code holds the patterns.",
                        text("bp:problem", """
                                Load arrives in spikes, capacity arrives in steps (the HPA needs minutes), so the interesting moment is the gap: MORE CONCURRENT WORK THAN THE FLEET CAN FINISH. Unbounded thinking says queue it all — and an unbounded queue is the delayed OutOfMemoryError the thread-hygiene section warned about, plus a worse failure mode: by the time the HPA's new pods are ready, the queued work is stale (timed-out clients, wasted processing on answers nobody wants) and the fleet spends its fresh capacity digging out instead of serving the present. Backpressure is the honest alternative: the system COMMUNICATES its fullness — by bounding queues (reject when full), by limiting concurrency (semaphores, pool budgets — the HikariCP queue made visible), or by pushing back on the PRODUCER (HTTP 429/503 with Retry-After, TCP window collapse, reactive-streams demand signals) instead of silently accepting work it cannot finish. Overload then degrades into waiting-or-retry at the edges, not collapse at the center.

                                The realtime topic's ticker is the pattern in miniature — a push system whose whole survivability story is bounded state plus honest dropping. Its replay window is a ring with a hard cap (bounded memory per the rule above; a resume older than the window gets what survived, never a hang), and its delivery policy is the load-shedding verdict stated in code — a listener that cannot take the tick (a dead emitter's IOException) is DROPPED, not retried, not buffered for later:
                                """),
                        code("bp:ring", "java",
                                "guide/domain/realtime/TickerFeed.java — bounded by construction: the ring forgets its oldest, never grows",
                                "server/src/main/java/guide/domain/realtime/TickerFeed.java", """
                                ring.addLast(tick);
                                while (ring.size() > RING_CAPACITY) {
                                    ring.removeFirst();
                                }"""),
                        code("bp:drop", "java",
                                "guide/domain/realtime/TickerFeed.java — the slow-consumer policy: a listener that throws is dropped, not retried",
                                "server/src/main/java/guide/domain/realtime/TickerFeed.java", """
                                private void deliver(Tick tick) {
                                    for (Consumer<Tick> listener : listeners) {
                                        try {
                                            listener.accept(tick);
                                        } catch (RuntimeException dead) {
                                            listeners.remove(listener);
                                        }
                                    }
                                }"""),
                        text("bp:cap", """
                                And the files topic's upload guard is the same verdict at the INPUT boundary — capacity enforced MID-STREAM, so hostile or accidental oversize costs a bounded amount of server I/O and then a typed 413, never an unbounded buffer:
                                """),
                        code("bp:guard", "java",
                                "guide/domain/files/GuardedUploadStream.java — the cap enforced as the bytes flow: the moment the limit is crossed, the read stops",
                                "server/src/main/java/guide/domain/files/GuardedUploadStream.java", """
                                private void onByte(byte b) {
                                    if (headFilled < head.length) {
                                        head[headFilled++] = b;
                                    }
                                    received++;
                                    if (received > maxBytes) {
                                        throw new Exceeded(maxBytes, received);
                                    }
                                }"""),
                        text("bp:verdict", """
                                Three placement rules turn those specimens into a strategy. ONE — push the pushback as FAR OUTWARD as possible: shedding at the edge (the balancer's rate limit, the 429 at the gateway) is one cheap answer; shedding at the database (connections exhausted, requests queued invisibly) is the most expensive one. TWO — bound EVERYTHING that can grow, at every tier: queues, batches, emitter lists, uploads, in-flight requests per downstream (the reliability topic's bulkheads are this rule given a name; its circuit breakers are the time dimension — stop offering work to a downstream that is failing). THREE — make the shed path a FIRST-CLASS response, not an exception nobody planned: a typed 429/503 through the error envelope, with Retry-After, with the observability topic's metrics incremented, so overload is a designed condition callers can cooperate with rather than a mystery timeout. The scaling story so far spreads load, sizes fleets, externalizes state, and protects the edges; what remains is knowing how much is left.""")),
                section("capacity", "Capacity: planning with numbers",
                        "The question scaling ultimately answers: how much load can this system take, and how do you know before users do? Requests, limits, utilization targets, and a load test that makes Thursday boring.",
                        text("cp:model", """
                                CAPACITY is the answer to one equation: how much concurrent load the system holds at its latency target, plus HEADROOM, minus the single component that saturates first. Little's law supplies the arithmetic spine (the performance topic used it for pools; here it is the fleet): concurrency = arrival rate × latency. A service answering 200 requests/second at p50 50ms has ~10 requests in flight on average — which virtual threads make cheap, which is exactly why the CONSTRAINT MOVES: the app tier absorbs concurrency almost for free, and the first thing to saturate is a shared store (the connection math from the data section), a downstream API's rate limit, or a pool sized like a budget. Capacity planning is the discipline of finding that first saturating component BEFORE users do — with numbers you can defend: measured p95s at measured rates (the observability topic's metrics), the HPA's scale behavior verified under a real ramp, and utilization targets that leave room to act (a fleet running at 85% CPU has minutes of runway; one at 40% has money parked; the usual band is 50–70% steady-state with alerting before the HPA's ceiling).

                                In k8s the fleet's currency is the REQUEST — the reservation the scheduler bins pods against and the HPA's percentage computes over. This repo's per-pod numbers are a real capacity statement in four lines:
                                """),
                        code("cp:resources", "yaml",
                                "deploy/k8s/deployment.yml — the capacity currency: requests are the reservation (bin-packing, HPA math), limits the ceiling",
                                "deploy/k8s/deployment.yml", """
                                resources:
                                  requests:
                                    cpu: 250m
                                    memory: 512Mi
                                  limits:
                                    cpu: "1"
                                    memory: 768Mi"""),
                        text("cp:verdict", """
                                Read them as the whole story: 250m requested means a node's core hosts four such pods bin-packed, and 70% CPU (the HPA's trigger) means 175m of steady use per pod before the fleet grows; the 1-CPU limit is the ceiling that throttles a runaway pod instead of letting it starve neighbors, and the memory limit is the OOMKill boundary the JVM must respect (-XX:MaxRAMPercentage, the GC section's container rule — the JVM and the orchestrator must agree on the memory the pod actually has). The last mile is PRACTICE: a load test that reproduces your real mix (reads, writes, the one expensive endpoint) against production-shaped infrastructure, run BEFORE the launch and after every big change — the terminal companion shows k6 and ab driving this repo's own demo endpoint. Then the checklist: measure the real p95, find the first saturating resource, check the connection arithmetic (pools × replicas vs max_connections), verify the HPA actually scales when ramped (and scales DOWN after the window), and write the numbers where the next engineer finds them. Performance makes one instance fast; reliability makes it survive failure; CAPACITY is the two of them multiplied by the fleet — and it is only ever a measurement away.""")),
                section("terminal-companion", "Terminal companion",
                        "The topic curled: drive the statelessness probe from curl until the local-memory loss is a number you saw, then load-test it with k6 and ab.",
                        text("tc:intro", """
                                The whole section is two endpoints — the probe and its reset, the same thin controller shape as every demo in the guide:
                                """),
                        code("tc:controller", "java",
                                "guide/presentation/demo/ScalingDemoController.java — raw string parameters, one use case, Result mapped to HTTP",
                                "server/src/main/java/guide/presentation/demo/ScalingDemoController.java", """
                                @GetMapping(value = "/api/demo/scaling/stateless", produces = MediaType.APPLICATION_JSON_VALUE)
                                ProbeStatelessness.Probe probe(
                                        @RequestParam(name = "instance", required = false) String instance,
                                        @RequestParam(name = "store", required = false) String store) {
                                    return unwrap(probeStatelessness.hit(instance, store));
                                }"""),
                        text("tc:tour", """
                                Run the experiment the section promised — external first, then the local-memory world losing your history on camera:
                                """),
                        code("tc:curl", "bash",
                                "the statelessness experiment, end to end",
                                null, """
                                # fresh fleet: personas, shared store, routing cursor all zero
                                curl -s localhost:8080/api/demo/scaling/stateless/reset | python3 -m json.tool | head -8

                                # external world, round-robin ×4: caller told 1,2,3,4 — any replica, same truth
                                for i in 1 2 3 4; do \\
                                  curl -s 'localhost:8080/api/demo/scaling/stateless?store=external' \\
                                  | python3 -c 'import json,sys; p=json.load(sys.stdin); \\
                                  print(f"routed to {p[\"routedTo\"]}, caller told {p[\"reportedValue\"]}, shared={p[\"externalState\"]}")'; done
                                # routed to server-a, caller told 1, shared=1
                                # routed to server-b, caller told 2, shared=2
                                # routed to server-a, caller told 3, shared=3
                                # routed to server-b, caller told 4, shared=4

                                # local world: pin to a ×3 (told 1,2,3), then route to b — backwards:
                                for i in 1 2 3; do curl -s 'localhost:8080/api/demo/scaling/stateless?store=local&instance=a' > /dev/null; done
                                curl -s 'localhost:8080/api/demo/scaling/stateless?store=local&instance=b' \\
                                  | python3 -c 'import json,sys; p=json.load(sys.stdin); \\
                                  print(f"told {p[\"reportedValue\"]} — lostIfLocal={p[\"lostIfLocal\"]} of {p[\"totalRequests\"]}")'
                                # told 1 — lostIfLocal=3 of 4: routing just forgot your history

                                # the honesty line: both "replicas" share one real process id
                                curl -s localhost:8080/api/demo/scaling/stateless | python3 -c 'import json,sys; print(json.load(sys.stdin)["processInstanceId"])'"""),
                        text("tc:load-intro", """
                                Then the capacity half — load tools driving the same endpoint. k6 (scriptable scenarios, thresholds that fail a run) and ab (the one-liner blunt instrument):
                                """),
                        code("tc:load", "bash",
                                "capacity practice: k6 scenario + ab one-liner against the probe",
                                null, """
                                # k6 — a ramp against the probe; thresholds make p95 a gate, not a hope:
                                cat > /tmp/scaling-probe.js <<'EOF'
                                import http from 'k6/http';
                                import { sleep } from 'k6';
                                export const options = {
                                  stages: [
                                    { duration: '30s', target: 50 },
                                    { duration: '1m', target: 50 },
                                    { duration: '15s', target: 0 },
                                  ],
                                  thresholds: { http_req_duration: ['p(95)<250'] },
                                };
                                export default function () {
                                  http.get('http://localhost:8080/api/demo/scaling/stateless');
                                  sleep(0.2);
                                }
                                EOF
                                k6 run /tmp/scaling-probe.js
                                #     checks.........................: 100.00% ✓ 12345 ✗ 0
                                #     http_req_duration..............: avg=3.2ms p(95)=8.7ms

                                # ab — the blunt version: 200 concurrent, 10k requests, keep-alive:
                                ab -k -c 200 -n 10000 http://localhost:8080/api/demo/scaling/stateless | grep -E 'Requests per|99%|Failed'

                                # and the fleet's own numbers, before you believe any of the above:
                                curl -s localhost:8080/actuator/prometheus | grep -E '^system_cpu_usage|^http_server_requests'"""),
                        text("tc:close", """
                                That last line is the topic wearing its own clothes: the demo measured statelessness with a number, the load tools measure capacity with a threshold, and the metrics endpoint is where both sets of numbers live when you are not watching. You now hold the full platform arc — performance made one instance fast, reliability made it survive failure, and this topic made it a FLEET: stateless so any copy can answer, balanced so they share the load, replicated and auto-scaled so the count follows demand, guarded at the edges and honest under overload, and sized by numbers you can defend. The reactive-spring topic takes the last concurrency question — the event-loop alternative to one-thread-per-request — and shows exactly when it earns its complexity.""")));
    }
}
