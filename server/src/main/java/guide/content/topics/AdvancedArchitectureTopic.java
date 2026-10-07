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
 * Topic 44 — Advanced architecture. Group platform, order 15. The FINAL
 * curriculum topic, platform-last on purpose: it is the synthesis the whole
 * guide was building toward — the consistency map (what a transaction
 * actually guarantees, and where its border lies), the DDD vocabulary
 * honestly sized against this repo's own ports, the monolith-first argument
 * (this app IS one, enforced by its own dependency-rule test), what you
 * trade away the moment work spans services (the outbox), and the three
 * patterns that buy consistency back where ACID cannot reach: sagas
 * (compensation instead of rollback), event sourcing (state as a function
 * of history), and CQRS (reads shaped as reads). Both live demos run REAL
 * in-memory engines in domain/patterns/ — arch:saga (a three-step order
 * orchestrator with a fail-at-step dial and an observable compensation
 * trail) and arch:eventstore (an append-only log plus replay-to-state
 * projection, deterministic folds) — and the topic closes where the
 * architecture ends: one trace id across every boundary, the W3C
 * traceparent topic 33 built.
 */
public final class AdvancedArchitectureTopic {

    private AdvancedArchitectureTopic() {
    }

    public static Topic topic() {
        return new Topic("advanced-architecture", "Advanced Architecture",
                "The synthesis topic: the consistency map (what a transaction guarantees, and where "
                        + "its border lies), the DDD vocabulary this repo's ports already speak, the "
                        + "monolith-first argument its own dependency-rule test enforces — and the three "
                        + "patterns that buy consistency back once work spans services: sagas that "
                        + "COMPENSATE instead of rolling back (a real three-step order orchestrator with "
                        + "a fail dial and an observable compensation trail), an EVENT STORE whose state "
                        + "is a pure replay of its log, and CQRS that lets reads be shaped as reads. It "
                        + "all closes with one trace id crossing every boundary. Both demos run real "
                        + "in-memory engines — no infrastructure, on purpose: the patterns are the lesson.",
                TopicGroupId.PLATFORM, 15,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("consistency-map", "The consistency map: what guarantees what",
                        "Every architecture decision in this guide was secretly a consistency decision. This section draws the map the rest of the topic walks: one transaction, one aggregate — and everything beyond that border is a pattern.",
                        text("cm:framing", """
                                EVERY TOPIC BEFORE THIS ONE made an implicit promise about consistency. The database topics promised ACID: a transaction's writes land together or not at all, and isolation keeps concurrent transactions from interleaving badly. The caching topic promised less: a cached answer may be stale, and the section was about choosing HOW stale. The messaging topic promised eventual consistency and spent most of its length buying back enough guarantees to make "eventual" trustworthy. The reliability and scaling topics promised that failure and replication exist, which is the same admission wearing operational clothes. ADVANCED ARCHITECTURE is the discipline of making those promises EXPLICIT — of knowing, for every piece of state you own, which consistency regime owns it.
                                
                                The map has three zones. ZONE ONE — INSIDE ONE TRANSACTION: a single database commit over a single aggregate is the only place you get atomicity, isolation and durability for free. A row and its audit entry (topic 17 wrote them in one commit), a post and its outbox row (topic 22) — these are ZONE ONE facts, and the discipline is keeping them there. ZONE TWO — INSIDE ONE PROCESS, OUTSIDE ONE TRANSACTION: a saga's steps, a job's retries, a cache invalidation beside a write. Here you get ATOMICITY OF INTENT (the outbox row commits with the business change) but not atomicity of EFFECT (the relay delivers later). ZONE THREE — ACROSS PROCESSES: two services, two databases, no shared commit. Here there is NO atomicity to be had at all, and every distributed-systems pattern — saga, outbox, idempotent consumer — is a different way of converting zone three's missing atomicity into zone two's eventual, observable convergence.
                                
                                The zone-one border is exactly one commit wide, and the whole trick of this topic's first code is how little it takes to straddle it honestly:
                                """),
                        code("cm:outbox-insert", "java",
                                "guide/data/messaging/PgOutboxStore.java — the outbox INSERT: the business mutation and the intent-to-publish ride ONE commit (zone one), delivery happens later (zone two)",
                                "server/src/main/java/guide/data/messaging/PgOutboxStore.java", """
                                private static final String INSERT_SQL = \"\"\"
                                                INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, headers)
                                                VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))\"\"\";
                                """),
                        text("cm:zones", """
                                Read that INSERT as a map legend. The row it writes is the EVENT's zone-one half: inside the caller's transaction it is exactly as durable as the business change beside it — either both exist or neither does. What the row REPRESENTS — the delivery to a stream, the side effect on some other system — lives in zone two until a relay claims it. Nothing in this guide ever claims zone three; even the third-party topic's integrations are single-service calls whose consistency story is timeouts, retries and blast-radius containment, not cross-database atomicity. Keep the three zones in your head for the rest of the topic: the saga is zone-two machinery (steps inside one process, effects across time), the event store is zone-one storage with zone-two reads (the log is atomic; the projection is derived), and tracing — the closing section — is how you WATCH convergence happen in any zone.
                                
                                One more framing decision, because "advanced architecture" invites grandiosity: this topic's advanced-ness is not in adopting exotic technology. Every engine it shows is in-memory, dependency-free, a few dozen lines — because the PATTERNS are what survive technology choices. The saga does not care whether its steps run in-process or over Kafka; the event store does not care whether the log lives in Postgres or a purpose-built database; CQRS does not care what the read model is made of. Learn the invariants first, then buy (or don't buy) the infrastructure that serves them.""")),
                section("ddd-vocabulary", "The vocabulary: DDD, honestly sized",
                        "Bounded contexts, aggregates, entities, values, domain events, ubiquitous language — the DDD words this guide has been using since topic 05 without naming them. Named now, and checked against real ports.",
                        text("ddd:contexts", """
                                DOMAIN-DRIVEN DESIGN gave this guide most of its vocabulary two hundred pages ago; this section is the receipt. A BOUNDED CONTEXT is a boundary inside which a word means exactly one thing: this app is deliberately one bounded context (a teaching guide), which is why "post" can mean one thing everywhere — the moment a second context arrived (comments did not, but imagine billing), "post" would fork into guide-post and billing-line-item, and the boundary between them would be the most important interface in the system. An AGGREGATE is a cluster of objects that commit together — the consistency boundary from the last section wearing an object-model costume: a post AND its tags change in one transaction because they are one aggregate; two posts never do. An ENTITY has identity (two posts with identical content are still two posts); a VALUE OBJECT does not (a title string, a money amount — equal is equal). And the UBIQUITOUS LANGUAGE is the rule that code, tests, docs and conversation all use the same words — why this repo's classes are CreatePost and PostStatus rather than DataManager and DtoWrapper.
                                
                                The two DDD artifacts this topic actually builds on are the DOMAIN EVENT and the PORT — and both already exist in this repo, which is the honest way to teach them:
                                """),
                        code("ddd:event", "java",
                                "guide/domain/messaging/DomainEvent.java — the event record: a FACT that happened (past tense, named so), its identity minted once, its source named — topic 22's shape, quoted as the DDD artifact it is",
                                "server/src/main/java/guide/domain/messaging/DomainEvent.java", """
                                public record DomainEvent(String eventId, String aggregateType, String aggregateId, String eventType,
                                        Map<String, Object> payload, Map<String, String> headers, Instant occurredAt) {
                                """),
                        text("ddd:event-anatomy", """
                                Field by field, that record IS the DDD definition. eventType is past-tense ("post.published" — it HAPPENED; a command would be present-tense and imperative, and the difference is load-bearing: events report, commands request). aggregateType + aggregateId name the SOURCE aggregate, which is exactly the pair a partitioned broker hashes to keep one aggregate's events ordered. eventId is the dedupe identity from the messaging topic — minted once, never regenerated, because an event replayed three times is still ONE event. And the record is immutable with defensive copies because a fact published to N consumers must not be mutable through any of them. Sagas and event sourcing in the next sections are built from exactly this shape: the saga's trail entries are events; the event store's log is a stream of them.
                                
                                The PORT is DDD's other face — the boundary where your domain meets a world it does not control — and this repo has been drawing them since the resilience topic put a circuit breaker behind one:
                                """),
                        code("ddd:port", "java",
                                "guide/domain/reliability/BreakerPort.java — the port as DDD boundary: the domain speaks its own vocabulary (state names, a typed rejection), the library stays outside",
                                "server/src/main/java/guide/domain/reliability/BreakerPort.java", """
                                public interface BreakerPort {
                                
                                    // … (javadoc: a refused call surfaces as BreakerOpenException — the
                                    //  DOMAIN's own type, so no library exception ever crosses this boundary)
                                    <T> T execute(Supplier<T> call);
                                
                                    // … snapshot, forceHalfOpen, reset — all plain data and ops
                                    String state();
                                """),
                        text("ddd:close", """
                                That is a hexagonal-architecture port in four lines of teaching code: the domain's vocabulary on the inside, an adapter contract on the outside, the library (Resilience4j there, a fake here, a stub in tests) a wiring decision. The third-party topic ran three whole integrations on the same shape, and this topic's engines keep the discipline — OrderSagas and EventStore import the JDK and guide.core only, which is why the dependency-rule test in the next section never had to grow an exemption for them. DDD, honestly sized, is not a framework or a folder structure: it is naming things what they are (event, aggregate, port), drawing boundaries where consistency actually breaks (aggregate, context), and letting the boundaries carry the vocabulary. If a pattern in this topic feels like ceremony, ask which of those three it serves — if the answer is none, skip it. YAGNI is an architecture tool too.""")),
                section("monolith-first", "Monolith first",
                        "The guide itself is the argument: one deployable, one database, hard internal boundaries — the modular monolith, enforced by the build. What splitting costs, and what to do before you pay it.",
                        text("mono:argument", """
                                THIS APPLICATION IS A MODULAR MONOLITH, and that is a decision, not an accident. One process (one deployable, one JVM, one transaction manager), one PostgreSQL, hard internal boundaries (presentation → domain ← data, enforced by a test, next paragraph) — and the boundaries are the whole trick. A modular monolith with soft boundaries is just a big ball of mud with extra folders; with HARD boundaries it is a distributed system that happens to share a process, and it keeps every option open: the areas that need independent scaling later can be extracted BECAUSE they already speak through ports rather than reaching into each other's tables. The extraction cost is paid at the boundary, so the boundary is where you invest.
                                
                                The monolith-first argument is empirical, not aesthetic. A service split buys independent deploys, independent scaling and failure isolation — every one of which you can also get, mostly, inside one process: this repo scales areas independently with virtual threads and bounded pools, isolates failures behind breaker ports and bulkheads, and deploys everything on one container image whose build is five minutes. What a split COSTS is the consistency map from the first section: the moment two areas share no commit, every cross-area workflow becomes a saga, every read becomes eventually consistent, and one trace id has to be threaded through everything (the closing section). Most systems pay that cost before they have the problem it solves — the classic premature distribution, the microservice divide-and-be-conquered.
                                
                                So the discipline is: monolith, hard boundaries, and MECHANICAL enforcement — because review-enforced boundaries erode and test-enforced ones do not:
                                """),
                        code("mono:rule", "java",
                                "server DependencyRuleTest — the architecture as a build failure: the spine, checked mechanically on every test run",
                                "server/src/test/java/guide/arch/DependencyRuleTest.java", """
                                @Test
                                void domainNeverImportsTheDataOrPresentationLayers() {
                                    assertNoViolations(violationsFor(scan()), "presentation → domain ← data");
                                }
                                """),
                        code("mono:ledger", "java",
                                "guide/domain/arch/ArchExemptions.java — the ledger: every deliberate bend of the rule carries a reason, and staleness is a failure",
                                "server/src/main/java/guide/domain/arch/ArchExemptions.java", """
                                public record DomainExemption(String file, String importPrefix, String reason) {
                                }
                                """),
                        text("mono:close", """
                                Two quotes, one architecture. The test scans every source file's imports and fails the build on any edge that points outward — the layer diagram this topic's diagrams draw, expressed as an assertion a CI run evaluates. And where the rule deliberately bends (a teaching exhibit, a serialization annotation), the bend is a LEDGER ENTRY with a reason, and the same test fails on stale entries — an exemption that cannot state its reason is not an exemption, it is an unreviewed violation. That is what "hard boundary" means in practice: not a diagram, not a convention, a red build. When you DO split a service out of this monolith, the extraction seam is already cut: the port the area speaks through becomes the new service's API, the adapter becomes its client, and the ledger entry that legalized the one awkward import becomes a design review. Split along the boundaries you already enforce — never discover them during the split.""")),
                section("between-services", "Between services: the consistency you lose",
                        "The moment a workflow spans two commits, ACID is gone. What actually breaks (the dual-write), the shape of the fix (the transactional outbox this repo really runs), and why the relay's claim query is the pattern's beating heart.",
                        text("btw:breaks", """
                                CUT THE MONOLITH between two areas that used to share a commit, and the first casualty is the write path. Before: one transaction wrote the post AND notified the consumer — one commit, both effects, impossible to have one without the other. After: the post write commits in your database and the publish goes to a broker — TWO systems, TWO commits, and every combination is now reachable: post saved and publish lost (the customer's order exists, nobody ships it), publish delivered and post lost (the warehouse ships an order that never existed), both, neither. This is the DUAL-WRITE problem, and it has no atomic fix — you cannot commit two systems in one operation. What you can do is make the INTENT durable in zone one and the EFFECT reliable in zone two: that is the TRANSACTIONAL OUTBOX, and this guide did not build a toy of it in this topic — topic 22 built the real one, on PostgreSQL, and this section quotes it.
                                
                                The port first, because the pattern is a port before it is a table:
                                """),
                        code("btw:port", "java",
                                "guide/domain/messaging/OutboxStore.java — the outbox port: append rides a caller's transaction; claim hands each row to at most one relay",
                                "server/src/main/java/guide/domain/messaging/OutboxStore.java", """
                                public interface OutboxStore {
                                
                                    // … (OutboxEntry + OutboxStats records — the row and the queue-state, counted)
                                    void append(DomainEvent event);
                                
                                    // … claimPending hands rows to AT MOST ONE concurrent caller,
                                    //  bumping attempts as a side effect — a row's attempt count IS its crash history
                                    List<OutboxEntry> claimPending(int limit);
                                """),
                        code("btw:publisher", "java",
                                "guide/data/messaging/OutboxEventPublisher.java — the drop-in half of the fix: publish() is an INSERT, atomic with the caller's business write by construction",
                                "server/src/main/java/guide/data/messaging/OutboxEventPublisher.java", """
                                @Override
                                public PublishedEvent publish(DomainEvent event) {
                                    store.append(event);
                                    return new PublishedEvent(null, OUTBOX_STREAM_NAME, event);
                                }
                                """),
                        text("btw:claim", """
                                The publisher's three lines carry the whole zone-one argument: JdbcTemplate borrows the CALLER's transaction, so the outbox INSERT and the business mutation are one commit — the dual-write's first half is simply gone. The returned PublishedEvent carries a null stream id on purpose: nothing has been delivered yet, and code that needs the truth must ask the outbox. But a row nobody delivers is just a slower dual-write — the pattern's second half is the RELAY, and the relay's hard part is the claim: N relay replicas poll the same table, and the same row must never ride two deliveries. One SQL statement does it:
                                """),
                        code("btw:skip-locked", "java",
                                "guide/data/messaging/PgOutboxStore.java — the claim: FOR UPDATE SKIP LOCKED hides other relays' in-flight rows, one statement, no waiting, no double-claim",
                                "server/src/main/java/guide/data/messaging/PgOutboxStore.java", """
                                private static final String CLAIM_SQL = \"\"\"
                                                UPDATE outbox SET attempts = attempts + 1
                                                WHERE id IN (
                                                    SELECT id FROM outbox
                                                    WHERE processed_at IS NULL
                                                    ORDER BY created_at, id
                                                    LIMIT ?
                                                    FOR UPDATE SKIP LOCKED)
                                                RETURNING id, aggregate_type, aggregate_id, event_type,
                                                          payload::text AS payload, headers::text AS headers,
                                                          attempts, created_at, processed_at\"\"\";
                                """),
                        text("btw:close", """
                                FOR UPDATE SKIP LOCKED is the load-bearing phrase: rows another relay has locked are INVISIBLE to this one — not a wait, not an error — so N replicas claim disjoint batches with no coordination and no serialization, and the outer UPDATE's attempts bump makes each claim one recorded attempt (the dead-letter threshold reads the same counter). Between services, that is the consistency you can actually have: the write side atomic with intent, the delivery side at-least-once, and the DUPLICATION admitted openly — which is why the consumer's dedupe set (the eventId minted once, from the DDD section) is not an optimization but the pattern's other half. At-least-once plus idempotent consumers is the only combination both correct and buildable; the saga engine next makes the same bargain for WORKFLOWS that a workflow's author can watch.""")),
                section("sagas", "Sagas: compensation instead of rollback",
                        "A business transaction that outlives one commit: steps that change the world, and compensations that change it back — in reverse order, observably. The live engine has a fail dial and an audit trail.",
                        text("sg:idea", """
                                A SAGA is a sequence of local transactions where each step COMMITS its effect, and failure is handled not by rolling back (impossible — step one's commit is already durable) but by COMPENSATING: running a business-meaningful undo for every COMPLETED step, in REVERSE completion order. Reserve stock, charge the card, ship the parcel; if the ship step fails, the two completed steps unwind — REFUND THE CARD, release the stock — and if a failure lands after the parcel left (a confirmation timeout, say), the recall joins the unwind at the front. The refund is the lesson: it is not a database rollback, it is an action the world can observe — the customer sees the money return, days later, with an apology. A saga trades the illusion of atomicity for the reality of EVENTUAL CONSISTENCY WITH A VISIBLE TRAIL, and the trail is not a debugging aid, it is the guarantee: a compensated saga leaves the world exactly as it found it, and the record proves it.
                                
                                The live engine is real domain code — an orchestrator, three steps, a world of stock, a balance and a shipment list. Each step checks the dial AND the world; here is step one:
                                """),
                        code("sg:step", "java",
                                "guide/domain/patterns/OrderSagas.java — one forward step: the dial and the world both get to refuse; success mutates the world and remembers what must be undone",
                                "server/src/main/java/guide/domain/patterns/OrderSagas.java", """
                                // ---- step 1: reserve stock (compensable — release) ----
                                if (failAtStep == 1 || items > world.stockOnHand) {
                                    steps.add(new StepEntry("reserve-stock", "failed",
                                            failAtStep == 1
                                                    ? "injected failure at step 1 — the dial, not the world"
                                                    : "needs " + items + " of a stock of " + world.stockOnHand));
                                    return finishByCompensating(sagaId, items, amountCents, failAtStep, steps, compensations, completed);
                                }
                                world.stockOnHand -= items;
                                steps.add(new StepEntry("reserve-stock", "completed",
                                        "stock " + (world.stockOnHand + items) + " → " + world.stockOnHand));
                                completed.add("reserve-stock");
                                """),
                        text("sg:two-failures", """
                                Read the guard twice, because it teaches the two failure families a production saga actually serves. The DIAL (failAtStep == 1) is deterministic injected failure — the demo's knob, the tests' certainty. The WORLD refusing (items > stockOnHand) is semantic failure — the business itself said no, no dial involved, and the second saga against an exhausted stock fails exactly this way in the live demo. Distinguishing them matters downstream: an injected failure is an exercise, a semantic failure is a fact your inventory process should hear about. Either way the orchestrator's next move is the same, and it is the pattern's signature — the unwind:
                                """),
                        code("sg:unwind", "java",
                                "guide/domain/patterns/OrderSagas.java — the unwind: completed steps compensated in REVERSE completion order — last done, first undone",
                                "server/src/main/java/guide/domain/patterns/OrderSagas.java", """
                                for (int index = completed.size() - 1; index >= 0; index--) {
                                    switch (completed.get(index)) {
                                        case "ship-order" -> {
                                            world.shipments.remove(world.shipments.size() - 1);
                                            compensations.add(new StepEntry("recall-shipment", "compensated",
                                                    "shipment recalled before it left the building"));
                                        }
                                        // … refund-payment restores the balance; release-stock returns the items
                                        default -> throw new IllegalStateException("no compensation defined for " + completed.get(index));
                                    }
                                }
                                """),
                        code("sg:reading", "java",
                                "guide/domain/patterns/OrderSagas.java — the reading line every compensated saga answers with: the world identical, the trail the proof",
                                "server/src/main/java/guide/domain/patterns/OrderSagas.java", """
                                "world after unwind: stock " + world.stockOnHand + ", balance " + world.balanceCents
                                        + "¢, shipments " + world.shipments.size()
                                        + " — identical to before the saga started; no partial state survives.",
                                """),
                        demo("sg:demo", "arch:saga", "The saga engine, live",
                                "Start an order saga with the items/amount dials and a fail-at-step dial (never, or at reserve / charge / ship). The report renders the whole trail: forward steps with completed / failed / skipped outcomes, the compensations in reverse completion order, the shared world's final state (stock, balance, shipments), and the orchestrator's own reading lines. Run the happy path and watch the world move; fail at step 2 and watch exactly one compensation (release-stock — the only completed step); fail at step 3 and watch the two completed steps unwind — refund before release, REVERSE completion order, because ship failed before its effect existed to undo. Every number deterministic — the curl tour at the end pins them."),
                        text("sg:close", """
                                What the engine adds on purpose, and what production would add, are both worth naming. PURPOSELY PRESENT: the audit trail (steps, compensations, the world's state — a saga without a readable trail is a liability), the shared WORLD (second sagas see first sagas' effects — eventual consistency is not a diagram, it is the stock counter moving), and the compensations as first-class records. PRODUCTION ADDS, in prose because they are infrastructure, not invariants: steps that call OTHER SERVICES (the same orchestrator shape, a port per step), DURABLE saga state (a saga interrupted mid-flight must resume or compensate from a journal — the outbox pattern again, one layer up), FORWARD RECOVERY where retry beats compensation (the reliability topic's backoff), and the semantic lock (topic 18's optimistic versioning) so two sagas cannot interleave on one aggregate. The thin controller serving the demo is the same one-line shape every demo uses:
                                """),
                        code("sg:controller", "java",
                                "guide/presentation/demo/PatternsDemoController.java — the saga trigger: validate the dials at the edge, one engine call, the whole trail in the answer",
                                "server/src/main/java/guide/presentation/demo/PatternsDemoController.java", """
                                @PostMapping(value = "/api/demo/arch/saga/start", produces = MediaType.APPLICATION_JSON_VALUE)
                                OrderSagas.SagaReport startSaga(
                                        @RequestParam(name = "items", defaultValue = "2") int items,
                                        @RequestParam(name = "amountCents", defaultValue = "1999") long amountCents,
                                        @RequestParam(name = "failAtStep", defaultValue = "0") int failAtStep) {
                                    requireInRange(items, OrderSagas.MIN_ITEMS, OrderSagas.MAX_ITEMS, "items");
                                    requireInRange(amountCents, OrderSagas.MIN_AMOUNT_CENTS, OrderSagas.MAX_AMOUNT_CENTS, "amountCents");
                                    requireInRange(failAtStep, 0, OrderSagas.MAX_FAIL_AT_STEP, "failAtStep");
                                    return orderSagas.start(items, amountCents, failAtStep);
                                }
                                """)),
                section("cqrs", "CQRS: reads are not writes",
                        "Command Query Responsibility Segregation — the observation that the question 'what shall we change?' and the question 'what shall we show?' want different models. You already run it; this section names where.",
                        text("cqrs:idea", """
                                CQRS is usually introduced with diagrams of separate databases and event buses; its actual content is one sentence: THE WRITE MODEL answers commands (validate, enforce invariants, change state), THE READ MODEL answers queries (assemble what a screen needs), and NOTHING FORCES THEM TO BE THE SAME CLASS. The observation is old — databases had reporting replicas before it had a name — and its honest size is: sometimes the shapes differ enough that sharing a model taxes both sides. A write model wants the aggregate: fully validated, consistent, small. A read model wants the screen: joined, flattened, denormalized, paginated. Forcing one class to do both is where N+1 queries and forty-field DTOs come from.
                                
                                This repo has run CQRS since the data topics without once building a second database. The JPA topic's projections are the purest case — read SHAPES that are deliberately not the entity:
                                """),
                        code("cqrs:interface-projection", "java",
                                "guide/data/jpa/PostSummary.java — the read model as interface projection: no entity instantiated, no persistence context, nothing dirty-checkable — columns straight to the screen",
                                "server/src/main/java/guide/data/jpa/PostSummary.java", """
                                public interface PostSummary {
                                
                                    String getTitle();
                                
                                    PostStatus getStatus();
                                
                                    int getVersion();
                                }
                                """),
                        code("cqrs:class-projection", "java",
                                "guide/data/jpa/PostTitleView.java — the read model as constructor expression: type-checked, derived values allowed, still not the write aggregate",
                                "server/src/main/java/guide/data/jpa/PostTitleView.java", """
                                public record PostTitleView(String title, PostStatus status) {
                                }
                                """),
                        text("cqrs:spectrum", """
                                Those two shapes are CQRS at its smallest separation: same database, same transaction, different MODELS for the two directions — and for most systems that is all CQRS should ever be. The spectrum then widens by need, each step buying query freedom at the price of staleness or sync work. STEP TWO, separate READ STORES: the caching topic's cache-aside and the scaling topic's read replicas — the read model materialized elsewhere, consistency eventual, invalidation the cost. STEP THREE, the content API this guide serves: the WRITE side is the topic registry compiled into the server; the READ side is a projection shaped per screen — you are reading a read model right now. STEP FOUR, fully independent read paths that REPLAY events to stay current — which is where CQRS meets the next section: an event store's projection IS a read model that is always one fold away from the truth.
                                
                                What CQRS is NOT, deliberately: it is not event sourcing (you can do each without the other, and mostly should), it is not eventually consistent by necessity (models can split inside one transaction), and it is not an architecture — it is a local decision applied per query, per screen, per aggregate. Apply it where the read shape and write shape have actually diverged; a CRUD screen over one table gains nothing from a projection layer except indirection. The guide's own rule of thumb held since topic 11: the API serves the CLIENT's question, not the entity's columns — that was CQRS vocabulary all along.""")),
                section("event-sourcing", "Event sourcing: state as a function of history",
                        "Store the facts, derive the state — append-only, replay-to-read. The live engine folds a cart stream with the replay log narrated, and seals it at checkout.",
                        text("es:idea", """
                                EVENT SOURCING inverts the usual storage question. Instead of "what IS the state?" (a row holding the answer), it asks "what HAPPENED?" (a log of facts) — and derives state by replaying: your cart is not a table row, it is item-added, item-added, item-removed, checked-out, folded in order. The inversion buys four things, and charges real costs. IT BUYS: a complete audit trail for free (the log IS the truth — no shadow audit table to keep honest); time travel (state at any moment is a fold up to any position — debugging, reports, dispute resolution); naturally occurring integration (the log is the domain-events feed the outbox had to synthesize); and honest deletes (GDPR's crypto-shred from the security topic, one layer down). IT CHARGES: unfamiliar queries (folding to answer "how many carts are open?" is a full scan until you build projections — which is exactly CQRS step four), schema evolution (old events must fold under new code forever), and storage that only grows.
                                
                                The live engine is small enough to hold in your head, which is the point — the CONTRACT is the lesson, infrastructure just serves it:
                                """),
                        code("es:append", "java",
                                "guide/domain/patterns/EventStore.java — append: the fact validated against the stream's CURRENT FOLD (remove only what the history contains; a sealed stream accepts nothing), then stored forever",
                                "server/src/main/java/guide/domain/patterns/EventStore.java", """
                                public synchronized StoredEvent append(String streamId, String type, Map<String, Object> payload) {
                                    require(streamId != null && STREAM_ID.matcher(streamId).matches(),
                                            "stream must be 1-40 chars of lowercase letters, digits and dashes (e.g. cart-1)");
                                    Map<String, Object> body = payload == null ? Map.of() : Map.copyOf(payload);
                                    Projection current = fold(streamId);
                                    switch (type) {
                                        // … item-added validates its payload and the seal the same way
                                        case ITEM_REMOVED -> {
                                            String item = requireItem(body);
                                            if (current.sealed()) {
                                                throw conflict(streamId, "already checked out — a sealed stream accepts no new facts");
                                            }
                                            if (!current.items().contains(item)) {
                                                throw AppException.of(Failure.of(FailureKind.VALIDATION,
                                                        "cannot remove '" + item + "' — the stream's own history doesn't contain it",
                                                        null));
                                            }
                                        }
                                """),
                        text("es:fold", """
                                Notice what append does NOT do: it never writes a cart row, never updates a total, never stores derived state — the ONLY write in the class is adding an event to the log. The validation consults the current fold (the same derivation every read runs) and refuses facts the history cannot own — you cannot remove an item your own log never added, checkout an empty cart, or append to a stream checkout sealed — but the guard reads a derivation, it is not a second state. Reads are derivations too, and there is exactly one:
                                """),
                        code("es:replay", "java",
                                "guide/domain/patterns/EventStore.java — the fold: pure, deterministic, narrated — every read derives state from an empty beginning",
                                "server/src/main/java/guide/domain/patterns/EventStore.java", """
                                for (StoredEvent event : events) {
                                    switch (event.type()) {
                                        case ITEM_ADDED -> {
                                            items.add(itemOf(event.payload()));
                                            long price = priceOf(event.payload());
                                            prices.add(price);
                                            totalCents += price;
                                            replayLog.add(event.eventId() + " " + ITEM_ADDED + " " + itemOf(event.payload())
                                                    + " " + price + "¢ → items " + items + ", total " + totalCents + "¢");
                                        }
                                        // … item-removed subtracts; checked-out seals
                                        default -> throw new IllegalStateException("the store never appended a " + event.type());
                                    }
                                }
                                """),
                        code("es:replay-endpoint", "java",
                                "guide/domain/patterns/EventStore.java — replay: the only state-reading operation there is",
                                "server/src/main/java/guide/domain/patterns/EventStore.java", """
                                public synchronized Projection replay(String streamId) {
                                    if (!streams.containsKey(streamId)) {
                                        throw AppException.of(Failure.of(FailureKind.NOT_FOUND,
                                                "no stream named '" + streamId + "' — append to it first", null));
                                    }
                                    return fold(streamId);
                                }
                                """),
                        demo("es:demo", "arch:eventstore", "The event store, live",
                                "Append facts to a cart stream (item-added with a price, item-removed, checked-out) and read both views side by side: the RAW LOG (positions, event ids, payloads — what actually happened) and the PROJECTION (items, total, status, and the replay log narrating each fold step). Remove an item the log never contained and the store refuses the fact with its typed reason; check out and the stream seals — every later append is refused. Append to a second stream and watch the inventory grow. Same log, same folds, every time — determinism is the demo."),
                        text("es:wiring", """
                                The composition root stays one screen long — two in-memory engines, wired like every port in this guide, replaceable by persistent implementations without touching a line above them:
                                """),
                        code("es:config", "java",
                                "guide/config/PatternsDemoConfig.java — the topic's whole wiring: two pure-domain engines, no infrastructure, on purpose",
                                "server/src/main/java/guide/config/PatternsDemoConfig.java", """
                                @Bean
                                OrderSagas orderSagas() {
                                    return new OrderSagas();
                                }
                                
                                @Bean
                                EventStore patternsEventStore() {
                                    return new EventStore();
                                }
                                """),
                        text("es:close", """
                                When event sourcing meets the rest of this topic, three connections close. TO THE OUTBOX: an event-sourced aggregate's log IS the outbox — the events are already facts, already durable, already ordered; the relay is a consumer at a position (which is why purpose-built event stores ship catch-up subscriptions). TO SAGAS: a saga's journal wants to be an event stream too — steps and compensations as facts, resume-by-replay instead of a bespoke state table. TO CQRS: the fold you watched is a read model computed on demand; scale it by folding ASYNCHRONOUSLY into a projection store and you have the full pattern — eventual consistency between log and projection, deliberately chosen, one replay away from the truth at all times. Production additions, all prose here because none change the contract: snapshots (fold from the last snapshot, not from zero — an optimization, invisible to callers), optimistic concurrency (append with an expected version; this engine's positions are the seam), and schema versioning (the headers envelope the messaging topic put on every event is where a version rides)."""),
                        code("es:client", "ts",
                                "client/src/ui/demos/arch/eventstore_demo.tsx — the client half: one POST per fact, stream/type/item/price riding the URL, then both views reloaded",
                                "client/src/ui/demos/arch/eventstore_demo.tsx", """
                                const path =
                                  type === 'cart.item-added'
                                    ? `/demo/arch/eventstore/append?stream=${stream}&type=${type}&item=${encodeURIComponent(item)}&priceCents=${priceCents}`
                                    : type === 'cart.item-removed'
                                      ? `/demo/arch/eventstore/append?stream=${stream}&type=${type}&item=${encodeURIComponent(item)}`
                                      : `/demo/arch/eventstore/append?stream=${stream}&type=${type}`;
                                """)),
                section("tracing-across-boundaries", "Tracing across boundaries",
                        "The architecture's last artifact is not a pattern but an instrument: one trace id, minted at the edge, carried across every boundary this topic drew. W3C traceparent, hand-built where you can watch it happen.",
                        text("tr:why", """
                                EVERY PATTERN in this topic makes work ASYNCHRONOUS or DISTRIBUTED — and invisible. A saga's compensation runs seconds after its failure; an outbox delivers minutes later; a projection lags its log. The instrument that keeps all of it debuggable is DISTRIBUTED TRACING: one TRACE ID minted at the first hop, carried on every boundary crossing, attached to every span and every log line, so that "why is this order stuck" becomes "grep one id" instead of archaeology across six systems. Topic 33 built the whole pipeline — JSON logs with the id in MDC, Micrometer Tracing spans, a self-call that PROVES propagation — and this section quotes the three moments that matter for architecture, because tracing is where the architecture becomes observable.
                                
                                Moment one: the W3C TRACE CONTEXT header, hand-built. Instrumented HTTP clients inject this automatically; the observability topic built it BY HAND exactly once, so you can see what the automation writes on the wire:
                                """),
                        code("tr:traceparent", "java",
                                "guide/data/obs/RestClientTraceProbe.java — the traceparent built by hand: version-traceid-spanid-flags, the entire W3C format in one string concatenation",
                                "server/src/main/java/guide/data/obs/RestClientTraceProbe.java", """
                                Span current = active.currentSpan();
                                if (current == null) {
                                    return Result.failed(Failure.of(FailureKind.UNAVAILABLE,
                                            "no active trace context on this thread — serve /trace over HTTP so the server "
                                                    + "observation opens one", null));
                                }
                                String traceId = current.context().traceId();
                                String traceparent = "00-" + traceId + "-" + current.context().spanId() + "-01";
                                """),
                        text("tr:hop", """
                                Moment two: the RECEIVING side proving what crossed. The hop endpoint echoes the header it actually received beside the trace id its own thread carried — if those disagree, propagation is broken, and the demo says so instead of drawing a confident picture:
                                """),
                        code("tr:hop-echo", "java",
                                "guide/presentation/demo/ObsDemoController.java — the receiving boundary: the header that arrived, the MDC this thread carried, the work's facts — one answer",
                                "server/src/main/java/guide/presentation/demo/ObsDemoController.java", """
                                HopResponse hop(@RequestParam(defaultValue = "direct curl") String origin,
                                        @RequestHeader(value = "traceparent", required = false) String traceparent) {
                                    RunTracedHop.HopFacts facts = runTracedHop.execute(origin);
                                    return new HopResponse(traceparent, MDC.get("traceId"), facts);
                                }
                                """),
                        code("tr:log-line", "java",
                                "guide/domain/obs/RunTracedHop.java — the business beat logged INSIDE the trace: this is the line that arrives with the trace id already in MDC",
                                "server/src/main/java/guide/domain/obs/RunTracedHop.java", """
                                LOGGER.info("traced hop from {}: post {} job {} outcome {}", origin,
                                        postId == null ? "skipped" : postId, jobId == null ? "skipped" : jobId,
                                        outcome == null ? "skipped" : outcome);
                                """),
                        text("tr:close", """
                                Moment three is that log line: one INFO beat, emitted on the hop's thread, INSIDE the trace the incoming traceparent started — so it reaches the log ring carrying the same id as the spans, and topic 33's demo could render spans and log lines under one number. The Boot 4 gotcha the guide paid for is worth repeating here because it is architectural: without tracing baggage explicitly enabled, traces WORK but log lines never carry the id — the instrumentation exists and the CORRELATION silently doesn't, the worst of both. For this topic's boundaries the rules are short: every boundary crossing carries trace context (HTTP header, message header — the DomainEvent's headers envelope exists for exactly this), every async handoff RE-attaches it (the span wraps the hop, not the method that launched it), and the saga/event-store engines above are deliberately synchronous and clockless so their trails are complete without it — which is itself the honest boundary: when determinism is free, take it; when it isn't, take a trace id.""")),
                section("terminal-companion", "Terminal companion",
                        "The topic curled: run a saga to compensation, seal a stream, and watch one trace id cross a real HTTP boundary — all from curl, every number deterministic.",
                        text("tc:intro", """
                                Both engines reset to constructed state, and every answer depends only on the calls you make since — the tour's comments are true for anyone who runs it. The saga half first: the happy path, then the dial at step 2, then the world that proves the unwind:
                                """),
                        code("tc:curl-saga", "bash",
                                "the saga tour: happy path, dialed failure, the trail read back, the world restored",
                                null, """
                                # the happy path: three steps completed, the world moved
                                curl -s -X POST 'localhost:8080/api/demo/arch/saga/start?items=2&amountCents=1999&failAtStep=0' \\
                                  | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["sagaId"], r["state"], [s["outcome"] for s in r["steps"]], "stock:", r["world"]["stockOnHand"])'
                                # saga-1 completed ['completed', 'completed', 'completed'] stock: 4
                                
                                # the dial at step 2: charge fails, exactly one compensation (release-stock),
                                # the world back to its constructed state
                                curl -s -X POST 'localhost:8080/api/demo/arch/saga/start?items=3&amountCents=2500&failAtStep=2' \\
                                  | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["state"], [s["outcome"] for s in r["steps"]], [c["name"] for c in r["compensations"]], "stock:", r["world"]["stockOnHand"])'
                                # compensated ['completed', 'failed', 'skipped'] ['release-stock'] stock: 6
                                
                                # the trail reads back — the audit survives the call that made it
                                curl -s localhost:8080/api/demo/arch/saga/saga-2 | python3 -c 'import json,sys; print(json.load(sys.stdin)["reading"][1])'
                                # compensations run in REVERSE completion order: [release-stock] — last completed, first undone.
                                
                                # back to constructed state (world, history, ids)
                                curl -s -X POST localhost:8080/api/demo/arch/saga/reset > /dev/null
                                """),
                        text("tc:store", """
                                The event store half: append the mug and the book, remove the mug, check out (the stream seals), then read the two views — the raw log and the projection — and watch a late append get the store's typed refusal:
                                """),
                        code("tc:curl-store", "bash",
                                "the event-store tour: append, remove, seal, replay, and the refusal after the seal",
                                null, """
                                # three facts: add mug, add book, remove mug (positions 1..3, ids evt-1..3)
                                curl -s -X POST 'localhost:8080/api/demo/arch/eventstore/append?stream=cart-1&type=cart.item-added&item=mug&priceCents=1200' > /dev/null
                                curl -s -X POST 'localhost:8080/api/demo/arch/eventstore/append?stream=cart-1&type=cart.item-added&item=book&priceCents=2500' > /dev/null
                                curl -s -X POST 'localhost:8080/api/demo/arch/eventstore/append?stream=cart-1&type=cart.item-removed&item=mug' \\
                                  | python3 -c 'import json,sys; a=json.load(sys.stdin)["after"]; print("v"+str(a["version"]), a["items"], a["totalCents"], a["status"])'
                                # v3 ['book'] 2500 open
                                
                                # checkout seals the stream; the fold says so, forever after
                                curl -s -X POST 'localhost:8080/api/demo/arch/eventstore/append?stream=cart-1&type=cart.checked-out' \\
                                  | python3 -c 'import json,sys; a=json.load(sys.stdin)["after"]; print(a["status"], a["sealed"])'
                                # checked-out True
                                
                                # the raw log vs the projection: history on the left, derivation on the right
                                curl -s localhost:8080/api/demo/arch/eventstore/stream/cart-1 | python3 -c 'import json,sys; print([(e["position"], e["type"]) for e in json.load(sys.stdin)["events"]])'
                                # [(1, 'cart.item-added'), (2, 'cart.item-added'), (3, 'cart.item-removed'), (4, 'cart.checked-out')]
                                curl -s localhost:8080/api/demo/arch/eventstore/state/cart-1 | python3 -c 'import json,sys; print(json.load(sys.stdin)["replayLog"][-1])'
                                # evt-4 cart.checked-out → history sealed: every later read of this stream folds the same state
                                
                                # a late append is REFUSED — the typed conflict envelope, not a 500
                                curl -s -X POST 'localhost:8080/api/demo/arch/eventstore/append?stream=cart-1&type=cart.item-added&item=late-mug&priceCents=500' \\
                                  | python3 -c 'import json,sys; print(json.load(sys.stdin)["error"]["code"])'
                                # conflict
                                
                                # and the empty log again
                                curl -s -X POST localhost:8080/api/demo/arch/eventstore/reset > /dev/null
                                """),
                        text("tc:trace", """
                                And the closing move — one trace id across a real HTTP boundary, topic 33's instrument aimed at this topic's architecture. Send a W3C traceparent of your own; the hop echoes what arrived and what its thread carried, and the trace endpoint assembles the spans and the log lines under the one id:
                                """),
                        code("tc:curl-trace", "bash",
                                "the tracing tour: hand-send a traceparent, watch it arrive, then the self-call proof",
                                null, """
                                # hand-send a W3C traceparent — the hop echoes it with the MDC's trace id
                                curl -s -H 'traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01' \\
                                  'localhost:8080/api/demo/obs/trace/hop?origin=topic-44-tour' \\
                                  | python3 -c 'import json,sys; r=json.load(sys.stdin); print("saw:", r["traceparentSeen"][:55], "| thread:", r["traceIdFromMdc"])'
                                # saw: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 | thread: 4bf92f3577b34da6a3ce929d0e0e4736
                                
                                # the full proof: the app traces ITSELF and answers with spans + logs, one id
                                curl -s localhost:8080/api/demo/obs/trace | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["traceId"], "spans:", len(r["timeline"]), "logs:", len(r["traceLogs"]))'
                                """),
                        text("tc:close", """
                                That is the guide's last terminal tour, and its shape is the curriculum's argument in miniature: a consistency problem (zones), a vocabulary for it (events, ports, aggregates), a monolith with hard boundaries to solve it in first, the patterns that buy consistency back when boundaries become services (outbox, saga, event store, projections), and one trace id to watch the whole machine work. Forty-four topics ago the first tour asked the app for its health; this one asks it for its ARCHITECTURE — and the app, being the guide, answers in JSON.""")));
    }
}
