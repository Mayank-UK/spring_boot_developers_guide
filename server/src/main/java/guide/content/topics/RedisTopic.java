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
 * Topic 20 — redis. Group data, order 5. What Redis is (and honestly is
 * not), the optional-service discipline this repo applies to every second
 * store, the five core structures through a namespaced playground wired to
 * StringRedisTemplate AND an in-memory fake, TTL/expiry semantics, pub/sub
 * with a real listener container, and the use cases that justify a second
 * store at all. One live demo: redis:playground (the structures console,
 * inline beside the structures it exercises).
 */
public final class RedisTopic {

    private RedisTopic() {
    }

    public static Topic topic() {
        return new Topic("redis", "Redis",
                "The in-memory data-structure server as a second store: what it is and "
                        + "honestly is not, this repo's optional-service discipline (boots "
                        + "without it, typed 503 while it is gone, scenario-flipped at runtime), "
                        + "strings/hashes/lists/sets/sorted-sets through a namespaced playground "
                        + "over StringRedisTemplate, TTL and the lazy-expiration model, pub/sub "
                        + "with a real listener container — and the use cases worth a second "
                        + "store at all.",
                TopicGroupId.DATA, 5,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("what-redis-is", "What Redis is",
                        "An in-memory data-structure server: the execution model, the speed story told honestly, the persistence dial.",
                        text("redis:model", """
                                Redis is an IN-MEMORY DATA-STRUCTURE SERVER: a process that owns a keyspace — a map from string keys to typed values — and answers commands over a socket (the RESP protocol: line-oriented, readable enough to drive by hand with redis-cli or even nc). Both halves of the phrase carry the weight. IN-MEMORY: the working set lives in RAM, which is the floor under every latency number Redis posts. DATA-STRUCTURE SERVER: the values are not opaque blobs but strings, hashes, lists, sets, sorted sets, bitmaps, hyperloglogs, streams — and the commands operate ON THE STRUCTURE (LPUSH one element onto a list, HGET one field of a hash, ZRANGE one rank-slice of a sorted set) server-side, without shipping the whole value over the wire to compute on it. That combination — RAM plus structure-aware commands plus one round trip — is essentially the whole product; everything else in this topic is consequence."""),
                        text("redis:execution-model", """
                                The execution model: Redis is effectively SINGLE-THREADED at its command core. One thread processes commands in arrival order, which makes each command ATOMIC and makes no command ever observe another half-applied — INCR is a read-modify-write with no lock because there is no concurrency at the core to lock against (Redis 6+ adds threads for network I/O and background housekeeping, but execution against the data stays serialized; the honest caveat is that one slow command — KEYS * over a huge keyspace — stalls everything, which is why production code uses SCAN). And the speed story, told honestly: RAM plus SIMPLICITY, not magic. A GET is a hash-table lookup and a socket write — no planner, no per-write fsync in the hot path unless you ask AOF for it, no rows to lock. The network round trip that costs half a millisecond to PostgreSQL costs the same half millisecond to Redis: the wins are per-command work, atomic structure operations, and data structures (TTLs, pub/sub, sorted sets) that would each be a subsystem in a relational database."""),
                        text("redis:persistence", """
                                Persistence is a survey because it is a DIAL, not a feature. RDB: periodic point-in-time snapshots written by a forked child process — compact, quick to restore, and lossy up to the interval between snapshots (minutes, by default). AOF: an append-only file of the write commands, replayed on start — durability per write tuned through appendfsync (always: safe and slow; everysec: the default compromise, up to one second of loss; no: you are lying to yourself). Redis 7's default combines them — RDB checkpoints inside an AOF tail — and the final position on the dial is NONE: a server started as a pure cache with persistence off, entirely legitimate when every byte is rebuildable (which is true of most of the uses this topic's sixth section advocates). The dev compose in this repo runs redis:7-alpine unconfigured, so snapshots happen on the vendor's defaults — a default this guide accepts because everything it stores there is ephemeral by design."""),
                        code("redis:yml", "yaml",
                                "server/src/main/resources/application.yml — the connection block and the health verdict, the only two yaml lines this topic owns",
                                "server/src/main/resources/application.yml", """
                                data:
                                  redis:
                                    host: ${SPRING_DATA_REDIS_HOST:localhost}
                                    port: ${SPRING_DATA_REDIS_PORT:6379}
                                # …
                                  health:
                                    db:
                                      enabled: false
                                    redis:
                                      enabled: false
                                """),
                        text("redis:config-decisions", """
                                Two decisions travel with those lines. Host and port are env-overridable with localhost defaults — the same twelve-factor shape the PostgreSQL datasource uses, so a compose file can point the app at its redis service without rebuilding anything. And the Redis health indicator is OFF, deliberately: the starter would otherwise mark /actuator/health DOWN whenever the server is absent, but this app is built to serve without Redis (the discipline the next section constructs), so an optional service going missing is not a liveness verdict on the process — it is a per-call, typed, honest 503 from the adapter that tried to reach it. The observability topic revisits this line with the fairer readiness story.""")),
                section("the-optional-service-discipline", "The optional-service discipline",
                        "Redis in compose since Phase 0, never a boot dependency: lazy activation, typed unavailability, the runtime scenario flip.",
                        text("disc:statement", """
                                Redis has been in the compose file since Phase 0, and the application has never needed it to boot. That is not luck — it is the optional-service discipline, and by this third data topic it should read as THIS REPO'S PATTERN rather than a trick. One: the app BOOTS WITHOUT the service — no Redis-shaped bean is constructed, no connection attempted, no health verdict rendered. Two: the first call that actually needs the server ACTIVATES it lazily, under a once-guard — a PING, and only a server that answered gets used — and a connection failure is CLASSIFIED into the error taxonomy's typed UNAVAILABLE failure: the same 503 service_unavailable envelope every degraded dependency in this guide serves, with the area named in its details, never a stack trace and never a failed boot. Three: the SCENARIO FLIP is runtime and reversible — PATCH /api/demo/scenario {"dataMode":"live"} and the very next playground op reads the real server; PATCH it back and the one after that reads the in-memory fake. No restart, no redeploy, and nothing above the port ever learns which one answered."""),
                        code("disc:activation", "java",
                                "guide/data/redis/LiveRedisArea.java — the once-guard: PING under a monitor, failures classified typed-unavailable, subscription started only after a server answered",
                                "server/src/main/java/guide/data/redis/LiveRedisArea.java", """
                                public void ensureConnected() {
                                    if (liveActivated) {
                                        return;
                                    }
                                    synchronized (activationMonitor) {
                                        if (liveActivated) {
                                            return;
                                        }
                                        try {
                                            RedisConnection connection = template.get().getConnectionFactory().getConnection();
                                            try {
                                                connection.ping();
                                            } finally {
                                                connection.close();
                                            }
                                        } catch (RedisConnectionFailureException e) {
                                            throw unavailable(e);
                                        } catch (DataAccessException e) {
                                            // … at activation time, before any command of ours ran, the DAO family means "cannot reach a working server"
                                            throw unavailable(e);
                                        }
                                        RedisMessageListenerContainer container = listenerContainer.get();
                                        if (!container.isRunning()) {
                                            container.start();
                                        }
                                        // Subscription handed off BEFORE the flag flips — a caller that
                                        liveActivated = true;
                                    }
                                }
                                """),
                        text("disc:router", """
                                The routing half is one small class, read at CALL TIME. Note the deliberate difference from the areas you have already met: 'api' is pinned fake by its config (the posts fake is that substrate's seed), 'db' is pinned live (those demos are about the real database and have no fake to serve) — but 'redis' carries NO PIN. It follows the global scenario toggle, so the public PATCH endpoint IS the demo: flipping the mode is the act this section teaches, and the router is where the flip lands. The live path pays the activation first; the fake is a full stand-in (the entire playground contract over ConcurrentHashMaps, next section), not a shrug:"""),
                        code("disc:delegate", "java",
                                "guide/data/redis/ScenarioRoutingRedisPlayground.java — the call-time swap: fake unless the 'redis' area reads live, activation before any live touch",
                                "server/src/main/java/guide/data/redis/ScenarioRoutingRedisPlayground.java", """
                                private RedisPlayground delegate() {
                                    if (scenarioStore.dataModeFor(AREA) != ScenarioDataMode.LIVE) {
                                        return fake;
                                    }
                                    activation.get().ensureConnected();
                                    return liveAdapter.get();
                                }
                                """),
                        text("disc:degrade", """
                                What degradation looks like on the wire: with the service absent and the mode flipped live, an op answers the typed envelope — 503, code service_unavailable, message "Redis is unreachable — the playground area is degraded until the service returns", details naming the area — and the very next call after compose heals the stack succeeds, because the failed activation left its flag DOWN (a healed dependency is retried-activated, once, successfully). The integration test pins exactly this sequence against a full application context pointed at a port where nothing listens: the context loads (nothing Redis-shaped was ever constructed), fake ops answer 200s, the flip produces 503s, the flip back produces 200s again. That test is the discipline's contract, executable — and the same contract topic 19's MongoDB area holds, because optional is a property of the APP, not of the database.""")),
                section("the-core-structures", "The core structures",
                        "Strings, hashes, lists, sets, sorted sets — the question each answers — through a namespaced port with a live adapter and a real fake.",
                        text("struct:five", """
                                Five structures cover nearly every legitimate Redis use, and each earns its keep by matching a SHAPE OF QUESTION. A STRING is the workhorse: one value under one key, optionally with a TTL (the cache), or a counter through the atomic INCR family (the rate limiter). A HASH is an OBJECT: field-level reads and writes — HSET session:42 name "Ada" touches one field without deserializing the whole blob, and HGETALL brings the object home whole. A LIST is a TIMELINE or FEED: LPUSH prepends, LRANGE reads the newest N, LTRIM keeps the list bounded — "the newest 100 events" is three commands, executed server-side. A SET is TAGS and UNIQUENESS: SADD is idempotent, SISMEMBER answers "does this post carry this tag" in one hop, SINTER intersects tag sets for "tagged both". A SORTED SET is a LEADERBOARD or PRIORITY structure: every member carries a score, ZADD upserts it, ZRANGE walks rank order — "top ten" and "everything above priority seven" are slices, not sorts, because the structure IS the index. The wrong question is "where do I put my data in Redis"; the right one is "which structure answers my query without a scan"."""),
                        code("struct:port", "java",
                                "guide/domain/redis/RedisPlayground.java — the teaching surface: one method per structure, bare keys in, typed answers out",
                                "server/src/main/java/guide/domain/redis/RedisPlayground.java", """
                                public interface RedisPlayground {

                                    String AREA = "redis";

                                    record ScoredMember(String member, double score) {
                                    }

                                    String namespaced(String bareKey);

                                    boolean exists(String key);

                                    String set(String key, String value);

                                    Optional<String> get(String key);

                                    boolean delete(String key);

                                    void hashSet(String key, String field, String value);

                                    Map<String, String> hashGetAll(String key);

                                    long listPush(String key, String value);

                                    List<String> listRange(String key, long start, long stop);

                                    long setAdd(String key, String member);

                                    Set<String> setMembers(String key);

                                    long zAdd(String key, double score, String member);

                                    List<ScoredMember> zRange(String key, long start, long stop);

                                    void setWithTtl(String key, String value, Duration ttl);

                                    boolean expire(String key, Duration ttl);

                                    Optional<Long> ttlOf(String key);

                                    long publish(String channel, String message);
                                }
                                """),
                        demo("struct:playground-demo", "redis:playground",
                                "The redis playground — the five structures, namespaced, mode-aware",
                                "Pick an op, fill its operands, run it against whichever store the scenario points at: set/get/del strings, hset/hgetall an object, lpush/lrange a timeline, sadd/smembers a tag set, zadd/zrange a leaderboard, ttl/expire the death sentence, publish to the channel. Every answer reports the namespaced key the server actually saw (guide:demo:…) and the mode that executed it."),
                        text("struct:namespace", """
                                Run a few ops and read the responses closely: every answer carries a namespacedKey of the form guide:demo:league, while you typed league. The namespace is the ADAPTER'S, applied on the way in and surfaced on the way out — and that placement is the lesson. This guide's demos share one Redis with anything else that happens to be running there; a bare league key is a collision waiting for a neighbor, and a demo that prefixes at every call site is a rename waiting to be missed. So routes and use cases pass BARE KEYS (the port's signatures say key, never guide:demo:key), the adapter prefixes EVERY command at the one door they all walk through, results report the FULL key, and the prefix string exists in exactly ONE constant in the data layer. The payoff is operational, not aesthetic: the demo keyspace is greppable (redis-cli --scan --pattern 'guide:demo:*'), cleanable by hand, impossible to mistake for real data — and "own the namespace inside the adapter" is the habit to steal for any multi-app or multi-tenant Redis you ever share."""),
                        code("struct:adapter", "java",
                                "guide/data/redis/StringRedisTemplatePlayground.java — the live adapter: prefix at the door, translate the answer, report the full key",
                                "server/src/main/java/guide/data/redis/StringRedisTemplatePlayground.java", """
                                @Override
                                public String namespaced(String bareKey) {
                                    return RedisNamespace.prefixed(bareKey);
                                }

                                @Override
                                public String set(String key, String value) {
                                    template.opsForValue().set(RedisNamespace.prefixed(key), value);
                                    return "OK";
                                }

                                @Override
                                public Optional<String> get(String key) {
                                    return Optional.ofNullable(template.opsForValue().get(RedisNamespace.prefixed(key)));
                                }

                                // …

                                @Override
                                public void setWithTtl(String key, String value, Duration ttl) {
                                    // SET key value EX ttl — value and death sentence in one atomic write,
                                    template.opsForValue().set(RedisNamespace.prefixed(key), value, ttl);
                                }
                                """),
                        text("struct:console", """
                                The console you just used is a thin React shell over POST /api/demo/redis/op: it sends {op, key, value?, field?, score?, ttlSeconds?} and renders {ok, result, namespacedKey, mode} — the result shows the structure's semantics (a hash as fields, a zrange as scored members, ttl as the typed insight), the namespaced key shows what the store actually holds, and the mode shows which store answered (flip the scenario and re-run the same op to watch it change). The operand rules live in the use case one layer below HTTP — the client only collects what the op of the moment uses:"""),
                        code("struct:console-ts", "ts",
                                "client/src/ui/demos/redis/playground_demo.tsx — the console's request assembly: the op of the moment decides which operands ship",
                                "client/src/ui/demos/redis/playground_demo.tsx", """
                                const body: Record<string, unknown> = { op, key };
                                if (OPS_NEEDING_VALUE.has(op)) body.value = value;
                                if (op === 'hset') body.field = field;
                                if (op === 'zadd') body.score = Number(score);
                                if (op === 'set' || op === 'expire') body.ttlSeconds = Number(ttlSeconds);
                                """)),
                section("ttl-and-expiry", "TTL and expiry",
                        "SET EX and EXPIRE, the lazy-expiration model with its active housekeeping cycle, the -2/-1/N answer made typed, cache-friendly defaults.",
                        text("ttl:model", """
                                Every key can carry an EXPIRY — a deadline, held in milliseconds beside the value. The writes are SET key value EX 60 (value and death sentence in one atomic command) and EXPIRE key seconds (arm a TTL on a key that already exists; it answers false when there is nothing to arm, a fact rather than an error). The read is TTL, whose wire answer overloads its sign: -2 no such key, -1 no expiry, N seconds remaining, rounded up. This repo's port collapses the two non-positive answers into an empty Optional and lets EXISTS disambiguate — precisely how redis clients compose the three-way integer into a fact — and the demo's ttl op serves the typed result of that composition (exists, hasExpiry, secondsRemaining). How the server REAPS expired keys is the part worth internalizing: expiration is LAZY. A key whose deadline passed is simply treated as absent on its next access — nothing happens AT the moment of expiry; no event fires, no memory frees. Beside the lazy path runs an ACTIVE cycle sampling keys that carry expirations a handful of times per second, evicting the already-dead that nobody ever reads again — housekeeping for the forgotten, not a scheduler you can lean on."""),
                        code("ttl:live", "java",
                                "guide/data/redis/StringRedisTemplatePlayground.java — the expiry trio against the real server: atomic SET EX, EXPIRE, getExpire translated",
                                "server/src/main/java/guide/data/redis/StringRedisTemplatePlayground.java", """
                                @Override
                                public void setWithTtl(String key, String value, Duration ttl) {
                                    // SET key value EX ttl — value and death sentence in one atomic write,
                                    template.opsForValue().set(RedisNamespace.prefixed(key), value, ttl);
                                }

                                @Override
                                public boolean expire(String key, Duration ttl) {
                                    return Boolean.TRUE.equals(template.expire(RedisNamespace.prefixed(key), ttl));
                                }

                                @Override
                                public Optional<Long> ttlOf(String key) {
                                    Long seconds = template.getExpire(RedisNamespace.prefixed(key));
                                    if (seconds == null || seconds < 0) {
                                        // -1 no expiry, -2 no key — collapsed; exists() disambiguates.
                                        return Optional.empty();
                                    }
                                    return Optional.of(seconds);
                                }
                                """),
                        code("ttl:fake", "java",
                                "guide/data/redis/InMemoryRedisPlayground.java — the fake mirrors the lazy model: expiry checked on access, TTL rounded up",
                                "server/src/main/java/guide/data/redis/InMemoryRedisPlayground.java", """
                                @Override
                                public Optional<Long> ttlOf(String key) {
                                    Entry entry = live(key);
                                    if (entry == null || entry.expiresAtMillis == 0) {
                                        return Optional.empty();
                                    }
                                    long remaining = entry.expiresAtMillis - System.currentTimeMillis();
                                    return Optional.of(Math.max(1, (remaining + 999) / 1000));
                                }

                                // …

                                private Entry live(String key) {
                                    String full = RedisNamespace.prefixed(key);
                                    Entry entry = store.get(full);
                                    if (entry == null) {
                                        return null;
                                    }
                                    if (entry.expired(System.currentTimeMillis())) {
                                        store.remove(full, entry);
                                        return null;
                                    }
                                    return entry;
                                }
                                """),
                        text("ttl:defaults", """
                                Two disciplines close the section. First, WRITES THAT DESERVE A TTL GET IT ATOMICALLY: setWithTtl is SET ... EX — never the two-step set-then-expire that loses the race when the process dies between the commands — and the console's set op attaches ttlSeconds the same way, which is why that operand sits beside set by default. Second, cache-friendly defaults are structural, not aspirational: an entry without a TTL is an IMMORTAL LEAK wearing a cache's clothes, so the CacheBackend port two sections down makes the TTL a required parameter of put — no caller can forget. And the testing honesty this repo holds itself to: TTL assertions never sleep-race. Immediately after SET EX 60 the server answers TTL with exactly the seconds you asked (it rounds remaining milliseconds up), so the live test asserts the 59–60 band on the spot; determinism by arithmetic, not by waiting.""")),
                section("pub-sub", "Pub/sub",
                        "Fire-and-forget channels against streams, a real listener container under a pattern subscription, and a bounded ring as the demo's window.",
                        text("pub:model", """
                                Pub/sub is Redis's other personality: PUBLISH channel message and PSUBSCRIBE pattern are DISPATCH, not storage. The server holds the subscription table — which connections listen on which channels — pushes each published message to every matching subscriber, and FORGETS IT. No persistence, no replay, no acknowledgment: a subscriber that was offline when the message flew has missed it, permanently, and nobody will tell either side. That honesty makes pub/sub right for PRESENCE-shaped problems — live notifications, cache-invalidation hints, chat where a lost line to a dropped connection is tolerable — and wrong for anything where a missed message is a bug. The latter need is what Redis STREAMS answer (XADD/XREAD/XAUTOCLAIM, consumer groups, pending-message reclaim — the delivery-guarantee machinery topic 22 builds out properly, there and on Kafka and RabbitMQ). The choosing rule is one sentence: if a consumer falling behind or restarting must not lose work, you need a stream or a queue, not a channel; pub/sub is the cheapest thing that works when it works."""),
                        text("pub:wiring", """
                                This demo's wiring is the production shape in miniature. A RedisMessageListenerContainer owns a subscription thread and a dispatch executor; against it stands one MessageListener registered on the PATTERN guide:demo:chan:* (PSUBSCRIBE — every channel under the demo namespace, however many the console invents), and every delivery decodes from the wire's raw bytes into the bounded recent-messages ring that GET /api/demo/redis/recent snapshots newest-first. How the container enters the context is this repo's hard-won lesson: it is BUILT ON DEMAND, never registered as a bean — because a RedisMessageListenerContainer is a SmartLifecycle bean, and Spring's lifecycle processor RESOLVES AND STARTS SmartLifecycle beans at context refresh even when their definitions are @Lazy. A registered container subscribed at boot and failed it with Redis absent (autoStartup=false stops the starting, but controller slices still died resolving the connection factory it never had); the activation builds it instead, exactly when a live PING has proven the server answers, and a destroy() hook stops what it started because no registered lifecycle will. Publishing is the template's convertAndSend to the namespaced channel; the receiver count it returns is informational, the ring is the truth:"""),
                        code("pub:container", "java",
                                "guide/config/RedisDemoConfig.java — the subscription container BUILT on demand, never registered: @Lazy cannot stop the lifecycle processor, so the activation constructs it",
                                "server/src/main/java/guide/config/RedisDemoConfig.java", """
                                private static RedisMessageListenerContainer buildListenerContainer(RedisConnectionFactory factory,
                                        RecentMessageListener listener) {
                                    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
                                    container.setConnectionFactory(factory);
                                    container.addMessageListener(listener, new PatternTopic(RedisNamespace.CHANNEL_PATTERN));
                                    container.addMessageListener(listener,
                                            new ChannelTopic(RedisNamespace.PREFIX + "chan:alive"));
                                    container.afterPropertiesSet();
                                    return container;
                                }
                                """),
                        code("pub:listener", "java",
                                "guide/data/redis/RecentMessageListener.java — the delivery seam: wire bytes to strings, ring takes retention",
                                "server/src/main/java/guide/data/redis/RecentMessageListener.java", """
                                @Override
                                public void onMessage(Message message, byte[] pattern) {
                                    String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
                                    String body = new String(message.getBody(), StandardCharsets.UTF_8);
                                    recent.append(channel, body);
                                    // The pattern this delivery matched — unused, but named here so the
                                    Objects.requireNonNull(pattern);
                                }
                                """),
                        text("pub:fake", """
                                In fake mode the round trip short-circuits, honestly: the fake's publish appends straight into the same ring and reports one receiver — no broker, same window. That is the port/adapter split paying its usual rent: the use case and the endpoint cannot tell, and do not care, whether the message crossed a socket; only the mode field on the response says which world executed it. Publish from the console, then refresh the recent list: in live mode the entry arrived through the subscription container — the real two-hop trip, publisher to server to subscriber, over the wire — while in fake mode it was written directly into the window. The bounded ring itself is a small standing lesson: a subscription can out-deliver any reader, so the demo keeps the newest twenty and drops the rest — a WINDOW, never a queue.""")),
                section("the-use-cases-worth-a-second-store", "The use cases worth a second store",
                        "Cache, rate limiter, session store, leaderboards, distributed locks — why Redis fits each, what never belongs, and the clean seam topic 21 will talk through.",
                        text("use:cases", """
                                None of the above is a reason to put your SOURCE OF TRUTH in Redis — persistence is a dial, queries are by key or by structure, and relationships do not exist there. The reasons to add it as a SECOND store are specific jobs where its shapes are load-bearing. CACHE (topic 21): the cache-aside pattern — read the cache, miss, read PostgreSQL, refill WITH a TTL — and Redis fits because TTLs are native, the store is SHARED by every replica (an instance-local cache answers differently per pod), and eviction under memory pressure is the server's problem, not the application's. RATE LIMITER (topic 25): INCR plus EXPIRE is a fixed-window counter, atomic without locks, shared across instances — the limiter that survives horizontal scaling because the count lives in exactly one place. SESSION STORE (topics 23 and 40): a session is a blob under an id with a natural TTL; Redis makes session expiry an expiration, lets any replica serve any session, and keeps churn out of the relational backups. LEADERBOARDS and priority: a sorted set IS the ranking — ZADD to update, ZRANGE to read the top, no re-sorting ever. DISTRIBUTED LOCKS: SET key token NX EX is the single-instance lock primitive — with the standing caveat that the multi-node generalization (Redlock) is contested (Kleppmann's critique: its safety rests on timing assumptions that fencing tokens must backstop), so reach for leases with fencing or a consensus store when correctness is existential."""),
                        text("use:honesty", """
                                What does NOT belong is the mirror of that list: anything whose loss is a bug. Redis promises best-effort durability — up to a second of loss at everysec, worse under memory pressure and eviction — so transactions, order history, invoices, and anything auditable stay in PostgreSQL, where WAL, constraints, and transactions live. The seam this repo builds for the first job on the list is deliberately narrow: a CacheBackend port — get, put-with-TTL, evict — with Redis as one adapter beside it. Topic 21's cache-aside flow and @Cacheable machinery talk through that contract. The port carries no namespace, no structures, no statistics, because a cache's CALLER should not know what serves it: an in-memory TTL map satisfies the same three methods, and that substitutability is the entire point of the port —"""),
                        code("use:cache-port", "java",
                                "guide/domain/redis/CacheBackend.java — the seam topic 21 consumes: three methods, TTL mandatory, caller ignorant of the server",
                                "server/src/main/java/guide/domain/redis/CacheBackend.java", """
                                public interface CacheBackend {

                                    Optional<String> get(String key);

                                    void put(String key, String value, Duration ttl);

                                    void evict(String key);
                                }
                                """),
                        text("use:when", """
                                When to add the second store at all is an architecture question with a boring answer: when a measured read path is dominated by recomputable lookups (the cache), when coordination state must be shared and short-lived (sessions, limits, locks), or when a query IS a data structure (leaderboards, timelines). Before any of those, the single relational database this guide runs is simpler to operate, back up, and reason about — one engine, one dialect, one truth. Redis earns its container by taking load off that engine or answering a shape it cannot answer in one round trip; it never earns it by holding the only copy of anything.""")),
                section("terminal-companion", "Terminal companion",
                        "The curl tour: the TTL lifecycle, a leaderboard in four commands, publish-to-recent, and the scenario flip.",
                        text("term:intro", """
                                The tour runs against a dev server (compose up, or your own run). Every op is one POST to /api/demo/redis/op; responses below are trimmed to the parts that teach. The scenario endpoints are GET/PATCH /api/demo/scenario and GET /api/demo/redis/mode — flip the mode mid-tour and watch the same ops answer from the other store, namespace and all."""),
                        code("term:ttl-tour", "bash",
                                "The TTL lifecycle: write with a death sentence, inspect it, replace it, then miss it",
                                null, """
                                # write with a death sentence attached (SET key value EX 60)
                                curl -s -X POST localhost:8080/api/demo/redis/op \\
                                  -H 'content-type: application/json' \\
                                  -d '{"op":"set","key":"session:ada","value":"u-4711","ttlSeconds":60}'
                                # → {"ok":true,"result":"OK","namespacedKey":"guide:demo:session:ada","mode":"fake"}

                                # inspect: the typed three-way TTL answer
                                curl -s -X POST localhost:8080/api/demo/redis/op \\
                                  -H 'content-type: application/json' -d '{"op":"ttl","key":"session:ada"}'
                                # → "result":{"exists":true,"hasExpiry":true,"secondsRemaining":60}

                                # re-arm for one second, wait past it, then read: expired means ABSENT
                                curl -s -X POST localhost:8080/api/demo/redis/op \\
                                  -H 'content-type: application/json' \\
                                  -d '{"op":"expire","key":"session:ada","ttlSeconds":1}'
                                sleep 2
                                curl -s -X POST localhost:8080/api/demo/redis/op \\
                                  -H 'content-type: application/json' -d '{"op":"get","key":"session:ada"}'
                                # → {"ok":true,"result":null,"namespacedKey":"guide:demo:session:ada","mode":"fake"}
                                """),
                        code("term:zadd-tour", "bash",
                                "A leaderboard in four commands: ZADD upserts, ZRANGE walks rank order",
                                null, """
                                curl -s -X POST localhost:8080/api/demo/redis/op -H 'content-type: application/json' \\
                                  -d '{"op":"zadd","key":"league","value":"ada","score":10}'
                                curl -s -X POST localhost:8080/api/demo/redis/op -H 'content-type: application/json' \\
                                  -d '{"op":"zadd","key":"league","value":"grace","score":12}'
                                curl -s -X POST localhost:8080/api/demo/redis/op -H 'content-type: application/json' \\
                                  -d '{"op":"zadd","key":"league","value":"knuth","score":9}'
                                curl -s -X POST localhost:8080/api/demo/redis/op -H 'content-type: application/json' \\
                                  -d '{"op":"zrange","key":"league","start":0,"stop":-1}'
                                # → "result":[{"member":"knuth","score":9.0},{"member":"ada","score":10.0},
                                #             {"member":"grace","score":12.0}]  — score order, no sort command run
                                """),
                        code("term:pub-flip-tour", "bash",
                                "Publish to the pattern, read the delivery window, then flip the scenario and feel the optional service",
                                null, """
                                # fire-and-forget: publish, then read what the subscription delivered
                                curl -s -X POST localhost:8080/api/demo/redis/op -H 'content-type: application/json' \\
                                  -d '{"op":"publish","key":"chan:playground","value":"hello from curl"}'
                                # → "result":{"channel":"guide:demo:chan:playground","receivers":1,…}
                                curl -s localhost:8080/api/demo/redis/recent
                                # the same message in the ring — live mode delivered it through the listener

                                # the scenario flip: same ops, other store
                                curl -s localhost:8080/api/demo/redis/mode
                                curl -s -X PATCH localhost:8080/api/demo/scenario \\
                                  -H 'content-type: application/json' -d '{"dataMode":"live"}'
                                curl -s -X POST localhost:8080/api/demo/redis/op -H 'content-type: application/json' \\
                                  -d '{"op":"set","key":"flip","value":"now on the real server"}'
                                # redis up    → "mode":"live", namespacedKey guide:demo:flip
                                # redis down  → 503 {"error":{"code":"service_unavailable",…,"area":"redis"}}
                                curl -s -X PATCH localhost:8080/api/demo/scenario \\
                                  -H 'content-type: application/json' -d '{"dataMode":"fake"}'
                                """),
                        text("term:outro", """
                                Everything this tour did is one port: the console, the curl, and the tests all speak RedisPlayground, and the store behind each call was a scenario decision. Flip it as often as you like — the namespace, the TTLs, and the typed degradation travel with whichever adapter answers the door.""")));
    }
}
