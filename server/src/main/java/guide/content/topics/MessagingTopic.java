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
 * Topic 22 — messaging. Group caching-messaging, order 2. Why systems
 * message at all (and the honest bill that comes with it), the two broker
 * families (replayable logs with consumer groups vs delete-on-consume
 * queues), a real Redis Streams console — publish, consume in a group,
 * pending, claim, dead letters — delivery guarantees and the exactly-once
 * myth, the transactional outbox over PostgreSQL with a relay that
 * survives crashes on purpose, idempotent consumers as the achievable
 * exactly-once, and Kafka/RabbitMQ taught by comparison. Two live demos:
 * msg:stream (inline at consumer groups) and msg:outbox (inline at the
 * outbox).
 */
public final class MessagingTopic {

    private MessagingTopic() {
    }

    public static Topic topic() {
        return new Topic("messaging", "Messaging & Event Streaming",
                "Async decoupling, load leveling, failure isolation — and the bill: "
                        + "eventual consistency, duplicated delivery, an operational surface. "
                        + "The two broker families (replayable logs vs delete-on-consume "
                        + "queues), a real Redis Streams console (XADD/XREADGROUP/XACK/"
                        + "XPENDING/XCLAIM and the dead-letter story), delivery guarantees "
                        + "and the exactly-once myth, the transactional outbox over "
                        + "PostgreSQL — mutation and event in ONE commit, relayed by a "
                        + "single-flight SKIP LOCKED relay that survives crashes on purpose — "
                        + "idempotent consumers as the achievable exactly-once, and Kafka & "
                        + "RabbitMQ by comparison.",
                TopicGroupId.CACHING_MESSAGING, 2,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("why-messaging", "Why messaging",
                        "Async decoupling, load leveling, failure isolation — and the bill: eventual consistency, duplicated delivery, an operational surface.",
                        text("why:decoupling", """
A MESSAGE makes the sender's question smaller: instead of "do this, now, and tell me when you are done", it says "this happened" and hangs up. That buys three forms of DECOUPLING. UNKNOWN RECEIVERS: the publisher names an event, not a collaborator — post.published does not know the search indexer, the notification fan-out, or the analytics roll-up exist, so adding a fourth consumer is a deployment, not a refactor. TIMING DECOUPLING: the publisher's latency stops being the sum of its dependents' — a checkout that synchronously charges, emails, updates CRM, and invalidates caches is four ways to be slow and four to fail; one event is one way to be fast. FAILURE ISOLATION: the email server being down must not fail checkouts — the broker holds the event while the consumer is repaired, then the backlog drains.
"""),
                        text("why:load-leveling", """
The second purchase is LOAD LEVELING. A system whose traffic arrives in spikes can size every component for the PEAK (expensive hardware idling at 4% most of the day) or put a QUEUE in front of the spike-sensitive part and let a fixed pool drain it at a sustainable rate. The queue converts "spikes we must survive" into "backlogs we must monitor": p99 latency becomes queue depth — a number you can alert on and one that degrades gracefully instead of falling over. The same shape protects any slow consumer from a fast producer: downstream needs a queue and an honest depth alert, not scaling every time upstream speeds up.
"""),
                        text("why:costs", """
Now the bill, read before signing. EVENTUAL CONSISTENCY: the moment "what happened" and "who has reacted" are decoupled in time, every consumer sees a PAST state — the projection lags by queue depth plus processing time, and code that assumes it is current is broken the day it is written (read-your-writes becomes a product design problem). DUPLICATED DELIVERY: at-least-once systems redeliver as POLICY — a crash after processing but before the ack must mean "try again", never "drop it" — so every consumer must survive seeing the same event twice; the fourth and seventh sections are that sentence expanded into machinery. OPERATIONAL SURFACE: a broker is a distributed system you now operate, with its own failure modes, capacity planning, and 3am pages — the optional-service discipline applies triple here.
"""),
                        text("why:when-not", """
And when a METHOD CALL is just better — often. Synchronous is right when the caller NEEDS the answer to proceed (payment authorization, credential checks — anything whose result shapes the response you are already building); request-reply through a broker is a named anti-pattern that adds every cost above while removing the main benefit. Synchronous is right when there is ONE consumer inside the transaction you are already in — an audit row that must commit with the change is a database write, not an event (topic 17's audit demo). And at low volume a queue is indirection without a workload. The test is not "is this important" but "does the caller need to WAIT for the reaction" — no, an event; yes, a call. This topic publishes post.published because publishing has async downstreams; it does not publish "row inserted" for its own storage.
"""),
                        code("why:event", "java",
                                "guide/domain/messaging/DomainEvent.java — the one shape every publisher, outbox row, stream entry, and consumer speaks",
                                "server/src/main/java/guide/domain/messaging/DomainEvent.java", """
                                public record DomainEvent(String eventId, String aggregateType, String aggregateId, String eventType,
                                        Map<String, Object> payload, Map<String, String> headers, Instant occurredAt) {

                                    /** The one aggregate this guide's events describe (the posts substrate). */
                                    public static final String AGGREGATE_POST = "post";

                                    /** The vocabulary word for "this post became published". */
                                    public static final String POST_PUBLISHED = "post.published";
                                    // …
                                    public static DomainEvent postPublished(String postId, Instant occurredAt) {
                                        Map<String, Object> payload = new LinkedHashMap<>();
                                        payload.put("postId", postId);
                                        payload.put("status", "published");
                                        Map<String, String> headers = new LinkedHashMap<>();
                                        headers.put("schema", "1");
                                        return new DomainEvent(UUID.randomUUID().toString(), AGGREGATE_POST, postId,
                                                POST_PUBLISHED, payload, headers, occurredAt);
                                    }
                                }
                                """),
                        text("why:anatomy", """
The vocabulary record rewards a close read because every decision in it recurs on every broker. The event is named in PAST TENSE — post.published, a fact — never PublishPost, a command; commands couple sender to receiver's behavior, facts do not. eventId is the DEDUPE KEY, minted once: an outbox row replayed three times or a stream entry redelivered after a crash carries the SAME id, and that identity is the entire basis of the idempotent-consumer machinery below. aggregateType + aggregateId name the SOURCE — the pair a partitioned broker hashes to keep one aggregate's events ordered. payload is a document, not a hierarchy (consumers evolve independently; a schema version rides the headers). occurredAt is when it HAPPENED — delivery time is a different clock.
""")),
                section("two-families-logs-and-queues", "Two families: logs and queues",
                        "Kafka and Redis Streams are replayable logs with consumer groups; RabbitMQ and SQS are delete-on-consume queues. The differences are not branding.",
                        text("fam:logs", """
Family one: the LOG (Kafka, Pulsar, Redis Streams, Kinesis). Storage is an append-only, time-ordered, REPLAYABLE sequence; consumers are READERS with a bookmark, and nothing is deleted when someone reads it — retention is a policy, not a consequence of consumption. That property generates the family's personality: REPLAY is free, so a consumer can be rebuilt by rewinding its bookmark (re-index the corpus by reading the log again with a new group) and a bug fix can be followed by reprocessing history. OFFSETS put the bookmark on the consumer's side of the contract — the broker does not know what is "done", the reader's committed position does, which is why log systems ask WHEN to commit an offset and why that choice IS your delivery guarantee. CONSUMER GROUPS (next section) are native: N readers share one bookmark family.
"""),
                        text("fam:queues", """
Family two: the QUEUE (RabbitMQ, ActiveMQ, SQS, most "job" systems). The unit of work is a MESSAGE addressed to a queue; delivery is CONSUMPTION — the broker hands the message out, tracks exactly which consumer holds it, and DELETES it on acknowledge. No replay: once acked, the message is gone, and a consumer that needs the data again must find it elsewhere (which is why queue-first architectures grow dead-letter queues and re-drive tooling so carefully). What the family buys instead is ROUTING and WORK SEMANTICS: rich addressability (exchanges and bindings in RabbitMQ; ARNs and policies in SQS), per-message visibility timeouts, priority and TTL, and a broker that is an ACTIVE participant in delivery rather than a passive file. Push fan-out is where queues feel at home; replayable history is where they cannot follow.
"""),
                        text("fam:choice", """
The choice for this guide: REDIS STREAMS, for real, with Kafka and RabbitMQ taught by comparison (eighth section). Three honest reasons. First, the MECHANICS ARE THE LESSON — consumer groups, pending entries, claim-after-idle, dead-lettering are the load-bearing concepts of the whole log family, and Redis Streams exposes each as a command you can watch work (the visibility timeout is XCLAIM's min-idle, not a hidden knob). Second, the OPERATIONAL FIT: compose already runs Redis (topic 20), so the demos ride an existing optional service instead of new infrastructure. Third, the CEILING IS HONEST: Streams' persistence dial is Redis' and retention is MAXLEN trimming — a real log with real semantics, NOT the multi-terabyte, cluster-replicated thing Kafka is. The namespace discipline continues: guide:demo:stream:events and guide:demo:stream:dead, owned by the adapter.
""")),
                section("consumer-groups-and-ordering", "Consumer groups and ordering",
                        "One group, many consumers: each entry goes to exactly one worker; ordering is per-stream (and per-key in Kafka); the group's read position is a bookmark, not a deletion.",
                        text("grp:model", """
A CONSUMER GROUP is the log family's answer to "how do N workers share one stream". The group has ONE shared read position (last-delivered-id); XREADGROUP with the special offset ">" means "entries NO ONE in the group has been delivered yet", and the server atomically moves the group's position as it hands entries out — so with N concurrent consumers, each entry goes to EXACTLY ONE of them: load balancing for free. The other read, offset "0", means "MY OWN un-acknowledged entries" — the crash-recovery read a worker performs on restart. Ordering follows: entries arrive in stream order (Redis stamps each XADD with a millis-sequence id), so ONE consumer sees an ordered suffix of the log; across consumers, order holds only per independent stream. Kafka sharpens that into PER-PARTITION ordering — a topic is many partitions, the KEY decides the partition, and all events for one key land on one partition and therefore one consumer: per-key ordering with parallelism. Our console carries aggregateId on every event so the key idea is visible, though a single stream is one ordering domain.
"""),
                        code("grp:port", "java",
                                "guide/domain/messaging/EventStream.java — the console port: group and workers named once, the six verbs in domain terms",
                                "server/src/main/java/guide/domain/messaging/EventStream.java", """
                                public interface EventStream {

                                    String GROUP = "guide-demo";

                                    String CONSUMER = "worker-1";

                                    String CLAIM_CONSUMER = "worker-2";
                                    // …
                                    List<Delivery> readGroup(String consumer, int count, boolean replayPending);

                                    long ack(List<String> ids);

                                    PendingView pending();

                                    ClaimedEntries claimIdle(String consumer, Duration minIdle);
                                }
                                """),
                        code("grp:read", "java",
                                "guide/data/messaging/RedisStreamMessaging.java — the one line that IS group semantics: '>' delivers the never-delivered, '0' re-reads your own pending",
                                "server/src/main/java/guide/data/messaging/RedisStreamMessaging.java", """
                                ReadOffset offset = replayPending ? ReadOffset.from("0") : ReadOffset.lastConsumed();
                                List<MapRecord<String, Object, Object>> records = ops().read(
                                        Consumer.from(GROUP, consumer),
                                        StreamReadOptions.empty().count(count),
                                        StreamOffset.create(STREAM_KEY, offset));
                                """),
                        demo("grp:demo", "msg:stream", "The stream console",
                                "Publish, consume in a group, inspect the pending ledger, claim idle work, read the dead letters — every verb above as a button."),
                        code("grp:client", "ts",
                                "client/src/ui/demos/msg/stream_demo.tsx — the console's publish: the vocabulary word plus a parsed payload, one POST",
                                "client/src/ui/demos/msg/stream_demo.tsx", """
                                async function publishEvent(
                                  config: AppConfig,
                                  eventType: string,
                                  payloadJson: string,
                                ): Promise<PublishAnswer> {
                                  const payload = payloadJson.trim() === ''
                                    ? {}
                                    : (JSON.parse(payloadJson) as Record<string, unknown>);
                                  const body = await requestJson(config, '/demo/msg/stream/publish', {
                                    method: 'POST',
                                    body: JSON.stringify({ eventType, payload }),
                                  });
                                // …
                                }
                                """),
                        text("grp:console", """
The console beside this text drives exactly those endpoints. POST /publish XADDs your event and answers the broker's stream id (millis-sequence — ordering made visible). POST /consume?max=N&ack=… delivers into worker-1 through the idempotent consumer and XACKs when ack=true — leave ack off and you have staged the crash window the next two sections live in; replayPending=true re-reads worker-1's pending entries (offset "0") — the restart read, felt without restarting. GET /pending is the XPENDING ledger; POST /claim?minIdleMs=… is the takeover; GET /dlq reads the dead letters. The two-consumer story: publish three, consume with ack off, claim with min-idle 0 — worker-2 takes over, and the ledger shows the holder flip.
""")),
                section("delivery-guarantees-and-the-myth", "Delivery guarantees and the exactly-once myth",
                        "At-most-once, at-least-once — and why the third option on the brochure is not what it sounds like. Idempotency is the real answer.",
                        text("gua:two-options", """
                                Every delivery pipeline has two dial positions, defined by WHEN the ack happens relative to the work. AT-MOST-ONCE: acknowledge on DELIVERY — the bookmark moves before processing, so a crash mid-processing LOSES the work; fast and simple, appropriate only for loss-tolerant telemetry (metrics samples, cache hints — absence nobody can detect). AT-LEAST-ONCE: acknowledge AFTER processing — the crash window between "processed" and "acked" means the entry is REDELIVERED and the consumer sees it twice; nothing is lost, duplication is the admitted cost. The entire transactional machinery this topic builds — pending entries, claims, the outbox — exists to make at-least-once WORK, because at-least-once plus idempotent consumers is the only combination both correct and buildable in the general case."""),
                        text("gua:myth", """
And EXACTLY-ONCE, the brochure's third option: the processing effect happening exactly one time, end to end, despite failures. The honest statement is narrow. What exists is atomicity of LOCAL pairs — Kafka's transactional producer plus read_committed consumer makes "commit offset + produce to another Kafka topic" atomic WITHIN Kafka. Exactly-once BETWEEN arbitrary systems (read from Kafka, write to PostgreSQL, under crashes) does not exist as infrastructure; it decomposes into at-least-once delivery plus EXACTLY-ONCE SIDE EFFECTS, and the side-effect part is YOUR code, always. The shipped workaround: make processing IDEMPOTENT — the same event twice leaves the same state as once. That is why this guide's consumer tracks a processed-set, and why the next three sections are one continuous argument.
"""),
                        code("gua:processed-set", "java",
                                "guide/domain/messaging/EventCounters.java — the processed-set: first delivery applies, redelivery is recognized and skipped, the window is bounded",
                                "server/src/main/java/guide/domain/messaging/EventCounters.java", """
                                public synchronized boolean record(DomainEvent event) {
                                    if (processedIds.containsKey(event.eventId())) {
                                        duplicates++;
                                        return false;
                                    }
                                    processedIds.put(event.eventId(), Boolean.TRUE);
                                    if (processedIds.size() > PROCESSED_WINDOW) {
                                        var eldest = processedIds.keySet().iterator().next();
                                        processedIds.remove(eldest);
                                    }
                                    counts.merge(event.eventType(), 1L, Long::sum);
                                    return true;
                                }
                                """),
                        text("gua:receipt", """
That twelve-line method is the achievable exactly-once: a bounded LRU of event ids consulted BEFORE the projection moves, returning applied-or-duplicate so the caller can report it honestly (the console's [DUPLICATE — skipped] badge is this return value on screen). The BOUND is the honest part — 1024 ids, because memory is finite: a duplicate arriving after its id aged out WILL re-apply, and the mitigation is the seventh section's (move the set to a table, or natural idempotency). The proof is executable: the integration test publishes ONE event, consumes it twice, and asserts the counter moved exactly once.
""")),
                section("pending-entries-and-visibility-timeout", "Pending entries and the visibility timeout",
                        "The crash window between delivery and ack: XPENDING is the ledger, XAUTOCLAIM/XCLAIM the reclaim, the delivery counter the fuse, the DLQ the exit.",
                        text("pend:window", """
                                Deliver an entry, crash before the ack, and the system is in its most interesting state: DELIVERED (the group's position moved past it) and UN-ACKED (nobody confirmed completion) — in log terms a PENDING ENTRY, XPENDING's ledger. Every serious queue system has this concept under some name. SQS calls the mechanism a VISIBILITY TIMEOUT: a delivered message is invisible to other consumers for N seconds, then — never acked or extended — it REAPPEARS for anyone to take; the timeout is a bet that the holder is dead after that long. Redis Streams makes the same bet with better instruments: the pending entry carries its HOLDER, its IDLE time, and its DELIVERY COUNT; the reclaim is an explicit command, not a silent reappearance."""),
                        code("pend:claim", "java",
                                "guide/data/messaging/RedisStreamMessaging.java — the takeover: XPENDING filtered by idle, then XCLAIM under the same min-idle",
                                "server/src/main/java/guide/data/messaging/RedisStreamMessaging.java", """
                                PendingMessages idle = ops().pending(STREAM_KEY, GROUP,
                                        Range.unbounded(), PENDING_BATCH, minIdle);
                                if (idle == null || idle.isEmpty()) {
                                    return new ClaimedEntries(List.of(), List.of(), consumer, minIdle.toMillis());
                                }
                                // …
                                List<MapRecord<String, Object, Object>> claimed = ops().claim(STREAM_KEY, GROUP,
                                        consumer, XClaimOptions.minIdle(minIdle).ids(ids.toArray(String[]::new)));
                                """),
                        text("pend:xautoclaim", """
A note on XAUTOCLAIM, stated precisely because the adapter lives it: Redis 6.2 fused the two commands above — scan pending entries past a min-idle and claim them — into one server-side XAUTOCLAIM. Spring Data Redis 4.1 does not surface it, so the adapter performs the two-step form XAUTOCLAIM replaced: XPENDING with an IDLE filter answers the entries (and their delivery counts), XCLAIM with the SAME min-idle moves them. That second min-idle is not redundant: between the two commands another worker may have re-delivered an entry, resetting its idle — MIN-IDLE makes the claim itself re-check the bet instead of stealing live work. Same semantics for our bounded batch, one extra round trip.
"""),
                        text("pend:poison", """
                                The DELIVERY COUNT is the fuse. An entry repeatedly delivered and never acked is either a DEAD WORKER (fix: claim it — the idle time says the holder is gone) or a POISON ENTRY — one whose processing always fails (unparsable payload, a violated invariant, a dependency it crashes). Different endings: the first reprocesses successfully; the second would cycle forever, so past a threshold (ours: three deliveries) it is DEAD-LETTERED — XADDed to a DLQ stream (guide:demo:stream:dead) with a reason, then XACKed out of the group. A DLQ is a work queue for OPERATORS — inspect, fix, replay. The lineage is explicit — SQS maxReceiveCount + DLQ, RabbitMQ dead-letter-exchange, Kafka retry-then-DLQ topics are one policy in three uniforms, and this console rebuilds it on stream primitives: /pending shows the count climbing, /claim?ack=false stages the dying worker (deterministically — min-idle 0, no sleeps), the fourth claim crosses the threshold, /dlq shows the arrival. The integration test walks that exact script."""),
                        code("pend:policy", "java",
                                "guide/domain/messaging/ClaimIdleEntries.java — the fate split: reprocessable entries go through the idempotent consumer, poison dead-letters",
                                "server/src/main/java/guide/domain/messaging/ClaimIdleEntries.java", """
                                for (EventStream.Delivery delivery : claimed.reprocessable()) {
                                    reprocessed.add(consumer.process(delivery));
                                    if (ack) {
                                        toAck.add(delivery.id());
                                    }
                                }
                                for (EventStream.Delivery poison : claimed.poison()) {
                                    deadLettered.add(new EventStream.DeadLetter(poison.id(),
                                            poison.event().eventId(), poison.event().eventType(),
                                            "delivered " + poison.deliveryCount() + " times without an ack — poison",
                                            null, poison.event()));
                                    stream.appendDead(poison.event(),
                                            "delivered " + poison.deliveryCount() + " times without an ack");
                                    toAck.add(poison.id());
                                }
                                """)),
                section("the-transactional-outbox", "The transactional outbox",
                        "The dual-write problem dissected, the outbox as THE fix, the relay with SKIP LOCKED and single-flight — and the fail-after-commit proof, told from the test.",
                        text("out:dual-write", """
The dual-write problem, precisely: a business mutation and the event it should cause are two writes aimed at two systems — DATABASE and BROKER — and no transaction spans both. Publish BEFORE commit: a rollback leaves the world holding an event about something that never happened — phantom events, consumers reacting to ghosts. Publish AFTER commit: a crash in the window between COMMIT and PUBLISH leaves the mutation real and the event nonexistent — THE LOST EVENT, silently, with no error anywhere (the post is published; nobody will ever index it). "Publish inside the transaction" does not exist as a mechanism — the broker cannot join a PostgreSQL commit — and 2PC is the heavyweight exception almost nobody runs voluntarily. The everyday-machinery fix is the TRANSACTIONAL OUTBOX, and the demo beside this text runs it for real.
"""),
                        code("out:v20", "sql",
                                "server/src/main/resources/db/migration/V20__outbox.sql — the outbox table: the event rides the SAME commit as the mutation",
                                "server/src/main/resources/db/migration/V20__outbox.sql", """
                                CREATE TABLE outbox (
                                    id             bigserial PRIMARY KEY,
                                    aggregate_type text NOT NULL,
                                    aggregate_id   text NOT NULL,
                                    event_type     text NOT NULL,
                                    payload        jsonb NOT NULL,
                                    headers        jsonb NOT NULL DEFAULT '{}'::jsonb,
                                    created_at     timestamptz NOT NULL DEFAULT now(),
                                    processed_at   timestamptz NULL,
                                    attempts       int NOT NULL DEFAULT 0 CHECK (attempts >= 0)
                                );

                                -- The relay's claim path: unprocessed rows in arrival order. Partial
                                -- (WHERE processed_at IS NULL) so processed history never pays into it.
                                CREATE INDEX outbox_unprocessed_idx ON outbox (created_at) WHERE processed_at IS NULL;
                                """),
                        code("out:publisher", "java",
                                "guide/data/messaging/OutboxEventPublisher.java — a drop-in publisher whose publish() is just an INSERT that rides the caller's transaction",
                                "server/src/main/java/guide/data/messaging/OutboxEventPublisher.java", """
                                @Override
                                public PublishedEvent publish(DomainEvent event) {
                                    store.append(event);
                                    return new PublishedEvent(null, OUTBOX_STREAM_NAME, event);
                                }
                                """),
                        text("out:mechanism", """
The mechanism, in one breath: instead of writing to the broker inside the business transaction, write the EVENT to a table in the SAME database and the SAME transaction as the mutation — one COMMIT covers both, so they exist together or not at all; a separate RELAY later claims unprocessed rows, XADDs each to the stream, and marks the row processed only after the broker accepted it. The port has TWO implementations — RedisDirectEventPublisher (XADD now, no transactional partner; fine for loss-tolerant telemetry and the console) and OutboxEventPublisher above (an INSERT; inside a caller's @Transactional it rides the thread-bound connection and therefore the caller's commit). The guarantee is a property of the CALLER's bracket:
"""),
                        code("out:tx", "java",
                                "guide/data/messaging/PgPostEventTransaction.java — the bracket: mutation and (optional) event append inside ONE @Transactional method",
                                "server/src/main/java/guide/data/messaging/PgPostEventTransaction.java", """
                                @Override
                                @Transactional
                                public PostMutation transactPublish(String postId, DomainEvent event, boolean failBeforeCommit,
                                        @Nullable TeachingEventPublisher inTransactionPublisher) {
                                    UUID id = parse(postId);
                                    // …
                                    Long newVersion = jdbc.queryForObject(PUBLISH_SQL, Long.class,
                                            now.atOffset(ZoneOffset.UTC), id);
                                    if (failBeforeCommit) {
                                        // …
                                        throw new IllegalStateException("simulated crash BETWEEN the post UPDATE and the "
                                                + "event append — the transaction rolls back, so neither the publish nor "
                                                + "the event survives");
                                    }
                                    // …
                                    if (inTransactionPublisher != null) {
                                        inTransactionPublisher.publish(event);
                                    }
                                    return new PostMutation(postId, title.orElseThrow(), event.eventId(),
                                            newVersion == null ? 0L : newVersion);
                                }
                                """),
                        demo("out:demo", "msg:outbox", "The outbox proof console",
                                "Publish a post transactionally (or the naive way), crash on purpose at both windows, relay the survivors, read the evidence."),
                        text("out:proof", """
The demo makes the proof runnable and the integration test pins it. DIRECTION ONE — COMMITTED MEANS DELIVERED: POST /transactional {publisher:"outbox", failAfterPublish:true} commits (post → published, outbox row → pending) and THEN crashes; the endpoint answers the 500 envelope and state shows pending=1; POST /relay claims the row, XADDs the event, marks it processed; the stream really contains post.published. The crash that loses a direct-publish event loses NOTHING here — the durable copy of the event was never outside the database. DIRECTION TWO — ROLLED BACK MEANS NOTHING: failBeforeCommit throws INSIDE the transaction, the proxy rolls everything back, and the assertions find no post change, no outbox row, nothing in the stream. THE CONTRAST: publisher:"direct" with failAfterPublish commits the post, dies before the XADD, and leaves the event NOWHERE. Same crash, three outcomes, exactly one correct.
"""),
                        code("out:claim-sql", "java",
                                "guide/data/messaging/PgOutboxStore.java — the claim: SKIP LOCKED hands each row to at most one relay, attempts+1, RETURNING the batch — one atomic statement",
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
                        code("out:relay", "java",
                                "guide/data/messaging/OutboxRelayImpl.java — mark AFTER publish (crash = claim again), dead-letter past the threshold, single-flight at the top",
                                "server/src/main/java/guide/data/messaging/OutboxRelayImpl.java", """
                                if (!inFlight.compareAndSet(false, true)) {
                                    return new RelayReport(0, 0, 0, true, List.of(), now);
                                }
                                try {
                                    List<OutboxEntry> batch = store.claimPending(limit);
                                    // …
                                    for (OutboxEntry entry : batch) {
                                        if (entry.attempts() > MAX_ATTEMPTS) {
                                            // …
                                            stream.appendDead(entry.event(), "claimed " + entry.attempts()
                                                    + " times without completing — dead-lettered by the relay");
                                            deadLettered++;
                                        } else {
                                            // …
                                            streamIds.add(stream.append(entry.event()).streamId());
                                            published++;
                                        }
                                        processedIds.add(entry.id());
                                    }
                                    if (!processedIds.isEmpty()) {
                                        store.markProcessed(processedIds);
                                    }
                                    return new RelayReport(batch.size(), published, deadLettered, false, streamIds, now);
                                } finally {
                                    inFlight.set(false);
                                }
                                """),
                        text("out:relay-design", """
Three relay decisions earn their own paragraph. SKIP LOCKED: rows another relay has in flight are INVISIBLE rather than a wait, so N relay replicas (or the ticker racing a manual /relay click) poll the same table without double-claiming and without serializing; the test fires EIGHT concurrent relays over four rows and asserts four DISTINCT event ids in the stream — exactly-once delivery, DB-enforced. SINGLE-FLIGHT: duplicate WORK is wasteful even when duplicate delivery is impossible; the unit test holds a batch open on a latch and proves the second call returns skippedInFlight without touching the store. MARK AFTER PUBLISH: a row is marked processed only after the broker accepted the XADD — a crash between claim and mark leaves the row claimable again, attempts already bumped, exactly the retry you want; a row claimed past MAX_ATTEMPTS is poison and dead-letters. Caveat: a production relay dead-letters on NON-RETRYABLE failures and retries transient ones with backoff — a claim-count threshold conflates a bad event with a bad DAY.
"""),
                        code("out:ticker", "java",
                                "guide/data/messaging/OutboxRelayTicker.java — the poller: eager bean, inert until live activation + live area mode (no eager consumers)",
                                "server/src/main/java/guide/data/messaging/OutboxRelayTicker.java", """
                                @Scheduled(fixedDelay = 1000)
                                public void tick() {
                                    if (relay.canRun()) {
                                        relay.relayNextBatch(clock.instant(), BATCH);
                                    }
                                }
                                """),
                        text("out:poller", """
The poller's shape is topic 20's hard-won lesson restated for polling: the ticker EXISTS from boot but does NOTHING until the messaging area has activated against live infrastructure (migrations applied, Redis pinged, group created — MsgDemoActivation's once-guard) AND the scenario area reads live. Flip the area to fake and the drain pauses observably — pending rows pile up in /state; flip it back and the next tick drains them. No restart, no eager consumer: canRun() costs one boolean read per second until the world is ready, and @EnableScheduling lives on JobsDemoConfig, the home topic 29 gave it (it landed first on the webhook config; the jobs topic relocated it, and the tickers never noticed).
""")),
                section("idempotent-consumers-exactly-once-achievable", "Idempotent consumers: exactly-once, the achievable version",
                        "Dedupe keys, processed-sets with retention, natural idempotency — the three ways to make at-least-once harmless, and the one this guide's consumer uses.",
                        text("idem:options", """
Since duplication is not a bug in at-least-once delivery but its DEFINITION, the consumer is where the guarantee is manufactured — with exactly three tools. NATURAL IDEMPOTENCY: make the side effect duplicate-proof by construction — UPSERT a row keyed by the event's identity, SET absolute values instead of applying deltas, "ensure this user has role X" instead of "add role X"; the second application finds the world already in the target state. Sturdiest option, needs no memory, the honest first question for any consumer design. THE PROCESSED-SET: remember applied event ids and skip repeats — necessary when the side effect has no natural key (a counter, exactly one email, one charge), with the retention caveat from the fourth section. TRANSACTIONAL INGEST: apply the side effect and record the processed id in ONE database transaction — the pair commits together, a redelivery's insert conflicts, the skip is durable; the processed-set done right.
"""),
                        code("idem:consumer", "java",
                                "guide/domain/messaging/IdempotentConsumer.java — one door for both delivery paths (read and claim), so dedupe policy cannot drift between them",
                                "server/src/main/java/guide/domain/messaging/IdempotentConsumer.java", """
                                public Outcome process(EventStream.Delivery delivery) {
                                    boolean first = counters.record(delivery.event());
                                    return new Outcome(delivery.id(), delivery.event(), !first);
                                }
                                """),
                        text("idem:ours", """
                                This guide's consumer takes option two — the bounded processed-set (EventCounters, quoted in the fourth section) behind the single process() door above — because its projection is a per-eventType counter, the one shape with NO natural idempotency: +1 applied twice is +2, wrong, forever. The single-door design is the part worth copying: BOTH delivery paths — the XREADGROUP endpoint and the claim takeover — route through the same IdempotentConsumer, so a takeover reprocess (worker-2 claiming what worker-1 died holding) hits the dedupe set and reports [DUPLICATE — skipped] instead of double-counting; the claim test asserts exactly that. The receipt travels: every consume and claim response carries per-delivery duplicate flags plus the running duplicates-seen tally, turning the guarantee from a promise into an observable. One line: at-least-once delivery + exactly-once side effects = the achievable exactly-once — the side-effect half is always yours.""")),
                section("kafka-and-rabbitmq-by-comparison", "Kafka and RabbitMQ by comparison",
                        "spring-kafka @KafkaListener and spring-amqp @RabbitListener in prose and excerpt; partitions vs queue routing; rebalancing; when to graduate from Redis Streams.",
                        text("cmp:kafka", """
                                KAFKA, the running comparison: a distributed, partitioned, replicated COMMIT LOG built for retention in days and throughput in the hundreds of thousands per second. A topic splits into PARTITIONS — independent append-only logs; the producer routes by KEY HASH (same key, same partition, same ordering — the per-key guarantee our single stream approximates); consumers join a GROUP and the cluster assigns each a subset of partitions (REBALANCING on join/leave — the pause every consumer takes while partitions reassign, the reason cooperative rebalancing exists). The Spring idiom, in prose and excerpt-as-text (no Kafka runs in this repo): a listener method annotated @KafkaListener(topics = "posts.events", groupId = "indexer") receives ConsumerRecord values; AckMode.MANUAL_IMMEDIATE hands the offset-commit decision to your code — the same ack-after-processing decision our XACK makes — and a ConcurrentKafkaListenerContainerFactory with concurrency N fans partitions across N threads. What Kafka ADDS: replication and min.insync.replicas, compacted topics (latest-value-per-key retention, the CDC substrate), and exactly-once WITHIN Kafka, whose limits the fourth section drew."""),
                        text("cmp:rabbit", """
                                RABBITMQ, the queue family's classic: producers address EXCHANGES, exchanges ROUTE to queues via bindings (direct, topic, fan-out, headers), and queues deliver with push semantics, prefetch (QoS) as the backpressure knob, and per-message acknowledgement that DELETES the message. The Spring idiom is @RabbitListener(queues = "post.published.index") over spring-amqp, manual acknowledgement available (AcknowledgeMode.MANUAL) — the same posture as our XACK. Where the log says "the broker forgets nothing until retention", RabbitMQ says "the broker forgets EVERYTHING on ack" — durability means queues and messages marked durable surviving a restart, not history surviving anything. The family's superpower is ROUTING TOPOLOGY: one published event fanning to a queue that keeps it, a queue that TTLs it, and a queue that dead-letters after three rejections — all declared, zero consumer code. And failure isolation for slow consumers is stronger than a log's: push + prefetch + depth caps mean one glacial consumer backs up ITS queue alone. SQS is this family as a managed service, visibility timeout at its center."""),
                        text("cmp:graduate", """
When to graduate off Redis Streams — a checklist, not a ladder. FOR DURABILITY: the moment stream contents are IRREPLACEABLE state (not derivable from the source of truth), Redis' persistence dial is the wrong answer — replicated partitions or a managed log (Kinesis, Pub/Sub) are what "we cannot lose this" means at 3am. FOR VOLUME AND RETENTION: sustained throughput past one node's memory, or retention in days at that volume — MAXLEN trimming will throw away the wrong end of the log. FOR ECOSYSTEM: schema registry, Kafka Connect taps, stream processing (kStreams, Flink) — the surrounding machinery is half the product. NOT FOR CONCEPTS: consumer groups, pending entries, claim-after-idle, dead-lettering, idempotent consumers all exist here, taught on watchable machinery, and they transfer UNCHANGED. The outbox transfers too — the relay's XADD becomes a KafkaTemplate.send or a RabbitTemplate.convertAndSend; the table, the SKIP LOCKED claim, mark-after-publish, and the dead-letter threshold are broker-agnostic by construction. Which was the point of building the pattern on boring machinery first.
""")),
                section("terminal-companion", "Terminal companion",
                        "The curl tour: publish → consume → pending → claim → dlq on the stream console; transactional + relay + state on the outbox proof.",
                        text("term:intro", """
                                The tour runs against a dev server (compose up, or your own run) with PostgreSQL and Redis reachable — the messaging area activates on first call and answers the typed 503 until then. Responses below are trimmed to the parts that teach. The crash calls answer the 500 envelope by design: read them, then read /state — that pairing IS the proof."""),
                        code("term:stream-tour", "bash",
                                "The stream console: publish, consume, stage the crash window, read the ledger, take over, dead-letter",
                                null, """
                                # XADD — the broker answers a millis-sequence id
                                curl -s -X POST localhost:8080/api/demo/msg/stream/publish \\
                                  -H 'content-type: application/json' \\
                                  -d '{"eventType":"demo.ping","payload":{"note":"hello"}}'
                                # → {"streamId":"1760-0","stream":"guide:demo:stream:events","eventId":"…",…}

                                # XREADGROUP into worker-1 — ack=false stages the crash window
                                curl -s -X POST 'localhost:8080/api/demo/msg/stream/consume?max=10&ack=false'
                                # → {"deliveries":[{"id":"1760-0","duplicate":false,…}],"acked":0,…}

                                # the restart read (offset "0"): the SAME delivery again — and the
                                # idempotent consumer flags it
                                curl -s -X POST 'localhost:8080/api/demo/msg/stream/consume?max=10&ack=false&replayPending=true'
                                # → {"deliveries":[{"id":"1760-0","duplicate":true,…}],"duplicatesSeen":1,…}

                                # XPENDING: who holds what, idle how long, delivered how many times
                                curl -s localhost:8080/api/demo/msg/stream/pending
                                # → {"total":1,"perConsumer":{"worker-1":1},
                                #    "entries":[{"id":"1760-0","consumer":"worker-1","idleMs":4200,"deliveryCount":2}]}

                                # the visibility-timeout takeover: worker-2 claims, reprocesses, acks
                                curl -s -X POST 'localhost:8080/api/demo/msg/stream/claim?minIdleMs=0&ack=true'
                                # → {"claimedBy":"worker-2","reprocessed":[{"id":"1760-0","duplicate":true}],
                                #    "deadLettered":[],"acked":1}
                                """),
                        code("term:dlq-tour", "bash",
                                "The poison game: claim without acking three times (the dying worker), then let the fourth delivery dead-letter",
                                null, """
                                curl -s -X POST localhost:8080/api/demo/msg/stream/publish \\
                                  -H 'content-type: application/json' -d '{"eventType":"demo.ping"}' > /dev/null
                                curl -s -X POST 'localhost:8080/api/demo/msg/stream/consume?max=1&ack=false' > /dev/null
                                # three crashy claims: delivery counts 2, 3 — still reprocessed, never acked
                                curl -s -X POST 'localhost:8080/api/demo/msg/stream/claim?minIdleMs=0&ack=false' > /dev/null
                                curl -s -X POST 'localhost:8080/api/demo/msg/stream/claim?minIdleMs=0&ack=false' > /dev/null
                                # the fourth delivery crosses the threshold: poison → DLQ + ack
                                curl -s -X POST 'localhost:8080/api/demo/msg/stream/claim?minIdleMs=0&ack=true'
                                # → {"deadLettered":[{"eventId":"…","reason":"delivered 4 times without an ack — poison"}]…}
                                curl -s localhost:8080/api/demo/msg/stream/dlq
                                """),
                        code("term:outbox-tour", "bash",
                                "The proof: outbox + crash-after-commit delivers; direct + same crash loses; rolled back leaves nothing",
                                null, """
                                POST_ID=9f1c5a52-3d7e-4f8a-9b21-6c4d8e2a7f01

                                # committed → the row survives the crash (the 500 is the proof starting)
                                curl -s -X POST localhost:8080/api/demo/msg/outbox/transactional \\
                                  -H 'content-type: application/json' \\
                                  -d "{\"postId\":\"$POST_ID\",\"publisher\":\"outbox\",\"failAfterPublish\":true}"
                                # → 500 {"error":{"code":"internal_error"}}  — but look:
                                curl -s localhost:8080/api/demo/msg/outbox/state | jq '.stats'
                                # → {"pending":1,"delivered":0,"dead":0}
                                curl -s -X POST localhost:8080/api/demo/msg/outbox/relay
                                # → {"claimed":1,"published":1,"streamIds":["1761-0"],"skippedInFlight":false,…}

                                # the same crash, the naive publisher: THE LOST EVENT
                                curl -s -X POST localhost:8080/api/demo/msg/outbox/transactional \\
                                  -H 'content-type: application/json' \\
                                  -d "{\"postId\":\"$POST_ID\",\"publisher\":\"direct\",\"failAfterPublish\":true}"
                                # → 500 … and state shows pending:0 — the event is nowhere, forever

                                # rolled back → nothing: one transaction, both writes die together
                                curl -s -X POST localhost:8080/api/demo/msg/outbox/transactional \\
                                  -H 'content-type: application/json' \\
                                  -d "{\"postId\":\"$POST_ID\",\"publisher\":\"outbox\",\"failBeforeCommit\":true}"
                                # → 500 … post unchanged, no outbox row, relay has nothing
                                """),
                        text("term:outro", """
Everything the tour did sits on the two ports this topic built — console and outbox, each a port with real adapters behind it, the crash flags staging windows the tests pin from the other side, the scenario toggle governing whether the relay's ticker is live. Flip the msg area to fake and watch pending pile up; flip it back and the next tick drains it — a real relay's operational story, in two curl calls.
""")));
    }
}
