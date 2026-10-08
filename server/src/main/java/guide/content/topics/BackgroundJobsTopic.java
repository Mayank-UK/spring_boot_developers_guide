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
 * Topic 29 — Background jobs. Group realtime-jobs, order 2. The work that
 * must not ride a request: @Async and its executor knobs, the observable
 * queue with retries, backoff, a dead-letter booth and replay, @Scheduled
 * and cron, email as the canonical job, and the two numbers that watch it
 * all. Built around a real in-app queue (domain/jobs + data/jobs) whose
 * every state is queryable — the console enqueues deterministic failures
 * and watches the machinery earn its keep.
 */
public final class BackgroundJobsTopic {

    private BackgroundJobsTopic() {
    }

    public static Topic topic() {
        return new Topic("background-jobs", "Background Jobs",
                "The work that must not ride a request: @Async and the executor "
                        + "knobs, an observable job queue with exponential backoff and a "
                        + "dead-letter queue you can replay from, @Scheduled and cron, email "
                        + "as the canonical job — and the two numbers that catch a queue "
                        + "on fire.",
                TopicGroupId.REALTIME_JOBS, 2,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("why-work-moves-background", "Why work moves to the background",
                        "Latency budgets, failure isolation, retry-ability — and the decision table for what stays in the request and what must never.",
                        text("why:latency", """
                                Every request carries a LATENCY BUDGET the caller actually experiences, and most interesting work blows straight through it. A welcome email costs 800ms against an SMTP relay; a report needs minutes; a retention sweep should not race your checkout's timeout. None of it belongs between "user clicked" and "user saw confirmation" — the mail leaving now versus in four seconds is invisible, the report is READ LATER BY DESIGN. Moving it to the background is how a 2-second request becomes a 50ms one without deleting work.

                                The second is FAILURE ISOLATION. A slow dependency inside your request makes YOUR endpoint slow — the retrying kind makes it slow fifteen times. Inside a JOB the same dependency produces one failed attempt, a reschedule, another try — while your endpoint has already answered. The request path stays independent of the flakiest things you talk to; the job path EXPECTS failure as normal. That inversion — failure as data on a record instead of an exception in a call stack — is this topic's whole mental shift.

                                The third is RETRY-ABILITY, a property of the SHAPE of the work: DESCRIBED (arguments as data, any worker can run it later), IDEMPOTENT (twice is harmless — at-least-once is the only guarantee a retrying system honestly gives; the messaging topic's consumer discipline), DECOUPLED (the enqueuer's transaction does not wait on the job). Email has all three. A payment capture never has the third — payments belong in request/response or a saga (topic 44), not a best-effort queue."""),
                        text("why:not-everything", """
                                What NOT to move is the more important half. The TRANSACTIONAL CORE stays synchronous: anything that must commit atomically with the caller's writes — "create the order AND reserve the stock" is one transaction, not a job; splitting it means compensations, the saga pattern's expensive territory. Anything the NEXT LINE OF CODE depends on stays synchronous: reading your own write through a queue is a distributed-systems bug wearing a feature's clothes. Anything with a HARD latency SLA was never a candidate.

                                The honest decision table: work moves to the background when it is SLOW-but-tolerant (email, receipts, webhooks out), BULK (reports, exports, reindexing), PERIODIC (retention sweeps like the security topic's PII cleanup) or FLAKY-EXTERNAL (a network you do not operate). It stays in the request when it is atomic with the caller, read-by-the-next-line, or SLA-critical. Between the two sits @Async — slow-but-tolerant work that has not yet earned a queue's machinery.

                                The queue's vocabulary is a CLOSED ENUM, worth stealing: a job type is a contract between the enqueuer and a handler registered at boot, so an open string type would let any caller invent work nobody executes — a queued record that can never run is a dead letter with extra steps:"""),
                        code("why:vocabulary", "java",
                                "guide/domain/jobs/JobType.java — the closed vocabulary: adding work means adding its handler beside it",
                                "server/src/main/java/guide/domain/jobs/JobType.java", """
                                public static Optional<JobType> fromWireName(String raw) {
                                    if (raw == null || raw.isBlank()) {
                                        return Optional.empty();
                                    }
                                    return Arrays.stream(values())
                                            .filter(type -> type.wireName.equals(raw.trim()))
                                            .findFirst();
                                }"""),
                        text("why:three-tiers", """
                                The demo ships three archetypal types: send-welcome-email (the canonical external job — this topic's middle third is about why email specifically), generate-report (the heavy job), cleanup-pii (the periodic sweep). All three are slow-but-tolerant, none atomic with a caller's transaction — the shapes 90% of real background work falls into.

                                One framing before the machinery: background work in Spring is TWO unrelated primitives answering different questions. @Async answers "how do I get this OFF the request thread?" — an executor, a proxy, nothing else. The queue answers "how do I get this to SURVIVE failure, retry with patience, and be VISIBLE while it waits?" Confusing them is the classic mistake: @Async has no retry, no persistence, no dead letters; when the process dies mid-task, the task is gone. This topic builds @Async first, then the queue, then the line between them with a working specimen of each.""")),
                section("anatomy-async-executor", "Anatomy of @Async and the executor",
                        "One annotation, one proxy, one pool — and four knobs whose failure modes you must be able to name before you turn them.",
                        text("async:machinery", """
                                @Async is the framework's cheapest background move: annotate a method, register @EnableAsync, and calls to it RETURN IMMEDIATELY while the body runs on an executor thread. The mechanism is the aop topic's proxy machinery wearing a different hat — Spring wraps your bean in a proxy that submits the call as a task and hands the caller a placeholder return. Every proxy caveat applies VERBATIM: a self-invoked @Async method (this.runSlowWork(...) inside the same class) BYPASSES the proxy and runs synchronously, annotation and all — the aop topic's self-invocation trap, cross-quoted because it bites identically here. Public methods, injected beans, calls through the proxy; nothing else is async.

                                The executor is where the teaching lives, because @Async with an unconfigured executor is a LATENT ACCIDENT: the pool you did not choose has knobs you did not read:"""),
                        code("async:executor", "java",
                                "guide/config/JobsDemoConfig.java — the executor: four knobs, four failure modes",
                                "server/src/main/java/guide/config/JobsDemoConfig.java", """
                                @Bean
                                ThreadPoolTaskExecutor jobsDemoExecutor() {
                                    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                                    executor.setCorePoolSize(2);
                                    executor.setMaxPoolSize(4);
                                    executor.setQueueCapacity(8);
                                    executor.setThreadNamePrefix("jobs-demo-");
                                    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
                                    executor.setWaitForTasksToCompleteOnShutdown(true);
                                    executor.setAwaitTerminationSeconds(10);
                                    return executor;
                                }"""),
                        text("async:knobs", """
                                CORE (2) is the idle cost: threads that exist and wait even at 3am. MAX (4) is the ceiling that keeps a thread explosion from becoming the outage it meant to absorb. QUEUE (8) is latency debt: work admitted but not yet running — it smooths bursts AND hides saturation, turning "we are overloaded" into "everything is a bit slow" for six hours before anyone pages. Read MAX and QUEUE TOGETHER: a ThreadPoolExecutor only grows beyond core when the QUEUE IS FULL — JDK semantics that invert the intuition (with core 2, queue 8, max 4, the ninth concurrent task gets QUEUED, not a third thread).

                                The REJECTION POLICY is what happens when core + queue + max cannot absorb one more. The default, AbortPolicy, THROWS — RejectedExecutionException into the caller's request, a 500. CallerRunsPolicy is the choice this guide teaches: the SUBMITTING thread runs the task itself. Nothing dropped, nothing thrown; the component feeding the pool too fast is slowed BY ITS OWN WORK — backpressure, literally implemented. The trade: under saturation your request threads become job threads (the console shows tickets whose threadName is the CALLER, not jobs-demo-N) — an error converted into a graceful slowdown. The last two lines are shutdown honesty: a SIGTERM drains in-flight tasks for up to ten seconds.

                                The specimen is one method. Note what rides along: the executor is NAMED (an unqualified @Async binds whatever executor exists — how unrelated work ends up competing on one pool), and the thread's NAME lands on the ticket — what makes saturation observable later:"""),
                        code("async:specimen", "java",
                                "guide/data/jobs/AsyncWorkService.java — the specimen: one annotation, one named executor, observable state",
                                "server/src/main/java/guide/data/jobs/AsyncWorkService.java", """
                                @Async("jobsDemoExecutor")
                                public void runSlowWork(String ticketId, long delayMs) {
                                    board.markRunning(ticketId, Thread.currentThread().getName(), clock.instant());
                                    try {
                                        Thread.sleep(delayMs);
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt(); // shutdown honesty: stop, stay flagged
                                    }
                                    board.markDone(ticketId, clock.instant());
                                }"""),
                        text("async:proxy-intro", """
                                The controller's half is the other load-bearing half — open the ticket on the board (the caller's receipt, observable from the first millisecond), then hand the work through THE INJECTED BEAN. The comment in the source is doing teaching work, because this is the line where the bug lives:"""),
                        code("async:proxy-call", "java",
                                "guide/presentation/demo/JobsDemoController.java — the call goes through the proxy; the ticket is the caller's receipt",
                                "server/src/main/java/guide/presentation/demo/JobsDemoController.java", """
                                AsyncWorkBoard.AsyncTicket ticket = asyncWorkBoard.open(
                                        "work_" + UUID.randomUUID(), delayMs, java.time.Instant.now());
                                // through the PROXY (injected bean) — never self-invoked: an
                                // internal call would skip it and run right here on the request thread
                                asyncWorkService.runSlowWork(ticket.id(), delayMs);
                                return ticket;"""),
                        text("async:enough", """
                                WHEN @Async IS ENOUGH: fire-and-forget with bounded tolerance — analytics events, cache warmups, "also send a receipt". If the task is lost to a deploy, the world survives. WHEN IT IS NOT: anything you would want to answer questions about afterwards — did it run? how many times did it try? @Async's answers are "probably" and "once". The queue's answers are records. The console gives you both side by side: a job that fails twice and earns its success; an async ticket that merely completes. Same slowness, two contracts.""")),
                section("retries-and-backoff", "Retries and backoff",
                        "Fail, wait 1s, fail, wait 2s, succeed — the curve, the cap, the jitter, the budget, and the idempotency tax. The demo core.",
                        text("retry:curve", """
                                A retry without backoff is a tight-loop DOS against your own dependency: it answered "421, try later" and you asked again four milliseconds later. The CADENCE is exponential backoff — after failure n, wait 1s · 2^(n−1), capped. Growth respects the CONSUMER: a dependency down from overload does not need CPR at one-second intervals. The CAP respects YOUR freshness promises: unbounded growth leaves work "in flight" for hours, arriving after the world it described moved on. The curve as pure math, straight into its unit test:

                                The killing half is the BUDGET: maxAttempts (three, here) after which the job does not retry — it dead-letters, next section. A budget converts "keeps failing" from an infinite condition into a bounded episode with a known worst case (1 + 2 = 3 seconds of waiting here). Budgets also cap the idempotency tax: every retry is a potential re-execution, so fewer, well-spaced attempts mean fewer duplicates your handlers must tolerate."""),
                        code("retry:math", "java",
                                "guide/domain/jobs/JobBackoff.java — growth, then a cap, both load-bearing",
                                "server/src/main/java/guide/domain/jobs/JobBackoff.java", """
                                public static Duration delayAfter(int failedAttempts) {
                                    if (failedAttempts < 1) {
                                        throw new IllegalArgumentException("backoff starts after the first failure");
                                    }
                                    long exponent = Math.min(failedAttempts - 1, 2);
                                    Duration delay = BASE_BACKOFF.multipliedBy(1L << exponent);
                                    return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
                                }"""),
                        text("retry:jitter", """
                                JITTER exists because failure SYNCHRONIZES. Ten thousand jobs enqueued by a deploy fail at t=0 with identical schedules, retry together at t=1s against the still-recovering dependency: the thundering herd, and YOUR retries are it. A random fraction of the delay on top (here 0–100%, injected as a DoubleSupplier so tests pin it to zero and production gets a Random) decorrelates the herd into a drizzle the endpoint can drink — the webhooks topic measured this; AWS's "Exponential Backoff and Jitter" note made it standard:

                                The deeper design question is WHERE THE DECISION LIVES. This queue's answer: one place, the worker, at the exact moment a handler throws. It publishes the running state first (a crash mid-handler leaves a RUNNING record as evidence), then one try/catch holds every outcome. Handlers stay dumb — do the work, or throw — so the retry policy can change without touching a single one:"""),
                        code("retry:decision", "java",
                                "guide/domain/jobs/JobWorker.java — one try/catch holds every outcome; time arrives as a parameter so tests never sleep",
                                "server/src/main/java/guide/domain/jobs/JobWorker.java", """
                                private JobRecord execute(JobRecord claimed, Instant now) {
                                    // publish the in-flight state first: a crash mid-handler leaves a
                                    // RUNNING record as evidence, not an invisible limbo
                                    JobRecord running = claimed.running(now);
                                    queue.save(running);
                                    int attemptNumber = claimed.attempts().size() + 1;
                                    JobHandler handler = handlers.get(claimed.type());
                                    // …
                                    try {
                                        handler.execute(running);
                                        JobRecord done = running.succeeded(new JobRecord.Attempt(attemptNumber, now,
                                                JobRecord.Attempt.Outcome.SUCCEEDED, null));
                                        queue.save(done);
                                        return done;
                                    } catch (Exception failure) {
                                        String error = errorOf(failure);
                                        if (attemptNumber >= claimed.maxAttempts()) {
                                            JobRecord dead = running.dead(new JobRecord.Attempt(attemptNumber, now,
                                                    JobRecord.Attempt.Outcome.DEAD, error));
                                            queue.save(dead);
                                            return dead;
                                        }
                                        Instant nextDue = now.plus(JobBackoff.withJitter(
                                                JobBackoff.delayAfter(attemptNumber), jitter));
                                        JobRecord retrying = running.awaitingRetry(new JobRecord.Attempt(attemptNumber, now,
                                                JobRecord.Attempt.Outcome.FAILED, error), nextDue);
                                        queue.save(retrying);
                                        return retrying;
                                    }
                                }"""),
                        text("retry:idempotency", """
                                Read that catch block as a contract. Handler side: THROWING is the only failure signal — a handler that swallows its own exceptions has told the queue a lie; the job reads succeeded while the mail never left. Queue side: it is at-least-once, because a retry after an ambiguous failure re-executes the handler. IDEMPOTENCY is therefore the admission price — the messaging topic built the disciplined consumer (dedupe on a stable id, side effects keyed so replays converge); a job handler needs the same shape. Welcome mail twice is harmless; a ledger credit twice is a refund bug. Key side effects on the job's identity or the payload's natural key.

                                The demo reproduces all of it DETERMINISTICALLY: failAttempts=N injects N transport-shaped failures before the handler does real work, so the entire episode replays identically from curl or a test. The suite drives the worker with constructed instants — no sleeps — and asserts the EXACT schedule: attempt 1 fails at T0, nothing is due at T0+500ms (backoff is a promise, not a hint), attempt 2 fails at T0+1s, attempt 3 succeeds at T0+3s, and the recording sender holds exactly one mail:"""),
                        demo("retry:console-demo", "jobs:console",
                                "The jobs console",
                                "Enqueue work with a deterministic failure injection and watch the queue earn its keep: the two observability numbers up top (depth, oldest pending age), state chips, one job's full attempt trace, the dead-letter booth with replay (fix the poison, or re-poison it and watch it walk back), the @Async contrast specimen with per-ticket thread names — caller-runs saturation visible — and what the recording sender actually sent. Live-ish by polling at the worker's own one-second beat while anything is pending."),
                        text("retry:demo-tour", """
                                The tour worth taking: enqueue a send-welcome-email with failAttempts=2 and the trace fills in real time — attempt 1 failed (SMTP 421), nextAttemptAt one second out, attempt 2 failed, then succeeded, and the emails pane gains exactly one row. Enqueue with failAttempts=9 and you meet the budget: three attempts, then the record goes dark and moves to the dead letters — next section's territory. Between the two, watch the depth and oldest-age numbers while attempts wait out their backoff: the dashboard catching a queue mid-episode.""")),
                section("dead-letter-and-replay", "Dead letters and replay",
                        "The queue's confession booth: where exhausted work parks with its full error trail, and what an operator does next.",
                        text("dlq:booth", """
                                When attempt three fails, everything so far has been automatic — the dead-letter queue is where automation deliberately STOPS. State goes DEAD, nothing retries it, and NOTHING DELETES IT EITHER: the DLQ is a parking lot whose product is FORENSICS. The attempt history DIAGNOSES: three identical 421s is a dependency that was down for three seconds (replay, move on); 421, timeout, connection-refused is a dependency still dying (fix it first); three identical NPEs with your class name in them is a bug no replay will ever cure. A dead letter without its history is a shrug wearing a badge.

                                The exit is REPLAY — an OPERATOR'S deliberate act, not a policy. Replay ENQUEUES A FRESH RECORD — same type, same payload, new id, attempts from zero — rather than resurrecting the dead one. The dead record STAYS as evidence ("replayed" is a fact about your response, not a rewrite of the history that justified it), and the fresh record's budget is INTACT — replaying into two spent attempts would dead-letter after one more failure, an execution, not a retry:"""),
                        code("dlq:replay-record", "java",
                                "guide/domain/jobs/JobRecord.java — replay derives a fresh queued record; the dead one stays as evidence",
                                "server/src/main/java/guide/domain/jobs/JobRecord.java", """
                                public JobRecord replayedAs(String newId, int newFailFirstAttempts, Instant now) {
                                    if (state != JobState.DEAD) {
                                        throw new IllegalStateException("only dead-lettered records are replayed: " + id);
                                    }
                                    return new JobRecord(newId, type, payload, JobState.QUEUED, maxAttempts, newFailFirstAttempts,
                                            List.of(), null, now, null, null, now, replays + 1);
                                }"""),
                        text("dlq:poison", """
                                The use case enforces the booth's one rule — only the dead may be replayed — and models the operator's FIX: you replay after repairing something, and in this console the injected failure IS the bug, so the replay request's failAttempts knob is the repair in demo form. Replay with the injection cleared (failAttempts=0) and the fresh record succeeds on its next tick; replay with the injection KEPT and it walks right back into the booth — which is what replaying without a fix deserves.

                                A POISON MESSAGE — one that fails deterministically forever (a payload nobody can parse, a bug keyed to its exact contents) — is the booth's permanent resident, and that is a FEATURE. In a real broker (RabbitMQ's dead-letter exchanges, SQS's DLQs, Redis Streams' delivery-count cutoffs — the messaging topic built one by hand) the poison row sits until a human removes it: contained, visible, billable to a person, instead of burning worker cycles in an infinite loop. A queue without a DLQ has two settings for persistent failure — retry forever (the loop) or drop silently (the lie). The booth is the third: contained, visible, waiting.

                                The endpoints: GET /api/demo/jobs/dead lists the booth oldest-first with full trails; POST /api/demo/jobs/dead/{id}/replay takes the optional fix knob and answers with the fresh queued record. Replay of anything not dead is a 409 conflict.""")),
                section("schedules-and-cron", "Schedules and cron",
                        "fixedDelay, fixedRate, and the six fields of cron — plus the drift and overlap traps that decide between them.",
                        text("schedule:modes", """
                                @Scheduled is the other time primitive, and its three shapes answer different questions. FIXEDDELAY spaces tick ENDS from tick STARTS: the next tick begins one period after the last COMPLETED. FIXEDRATE spaces starts from starts, whether or not the last tick finished. CRON names calendar moments ("0 0 3 * * *" — 3am daily) for when the BUSINESS cares about wall-clock rather than cadence. The trap is overlap: fixedRate STACKS or skips when a tick overruns — fine for idempotent telemetry, catastrophic for a tick that mutates shared state. fixedDelay cannot overlap BY CONSTRUCTION: a slow tick simply delays the next. This guide's tickers all choose fixedDelay for that reason. Rule of thumb: fixedDelay for polling and draining, fixedRate for heartbeats, cron for the business calendar.

                                The cron ANATOMY: six space-separated fields — second minute hour day-of-month month day-of-week (Spring's leading SECONDS field is the classic porting bug from five-field crontab). The traps live in the LAST TWO fields: day-of-month and day-of-week are OR-ed when both are restricted, and a timezone-pinned cron must say so — @Scheduled(zone = "Europe/Berlin") or the JVM default decides what "3am" means, which is how a UTC deploy moved your nightly job eight hours.

                                This queue's scheduled half is one deliberate line of policy-free delegation — the TICKER carries nothing, the WORKER decides everything, so the scheduler cannot diverge from the tested code path:"""),
                        code("schedule:ticker", "java",
                                "guide/data/jobs/JobWorkerTicker.java — no policy, one delegation, fixedDelay so a slow pass delays instead of stacking",
                                "server/src/main/java/guide/data/jobs/JobWorkerTicker.java", """
                                /** One beat: claim and run everything time has made due. */
                                @Scheduled(fixedDelay = 1000)
                                public void tick() {
                                    worker.runOnce(clock.instant());
                                }"""),
                        text("schedule:heartbeat", """
                                And because "the scheduler is alive" should be PROVABLE from outside, the demo carries a heartbeat — a tick that does nothing but remember when it happened, in a bounded ring, one instant per second forever. The cheapest possible observability of the @Scheduled machinery itself, on the console as cadence evidence:

                                Registration is one class-level decision, made deliberately here: @EnableScheduling USED to live on the webhooks config (where the guide's first ticker landed); this topic RELOCATED it to the jobs config, which owns time-driven work, and consolidated @EnableAsync beside it. Both annotations are idempotent and global — every @Scheduled method in the context wakes, whichever config class carries the flag — and the suite proves the relocation: webhooks and messaging tests stayed green, and the scheduling integration test watches a job walk fail → backoff → succeed through the real 1s ticks in the real application context:"""),
                        code("schedule:consolidation", "java",
                                "guide/config/JobsDemoConfig.java — the consolidation: both time primitives registered once, deliberately",
                                "server/src/main/java/guide/config/JobsDemoConfig.java", """
                                @Configuration
                                @EnableAsync
                                @EnableScheduling
                                public class JobsDemoConfig {"""),
                        code("schedule:heartbeat-beat", "java",
                                "guide/data/jobs/JobsHeartbeat.java — the cadence made observable: bounded ring, monotonic total",
                                "server/src/main/java/guide/data/jobs/JobsHeartbeat.java", """
                                /** One beat: remember now, bounded. */
                                @Scheduled(fixedDelay = 1000)
                                public synchronized void beat() {
                                    ring.addLast(clock.instant());
                                    while (ring.size() > RING_SIZE) {
                                        ring.removeFirst();
                                    }
                                    totalTicks += 1;
                                }"""),
                        text("schedule:lifecycle", """
                                Lifecycle honesty: this worker ticker is ALWAYS ON, live-but-idle by design — one claim scan per second against a usually-empty in-memory store costs nothing, and a queue teaches by being alive (enqueue from the tour and the NEXT TICK does the work, not a manual nudge). Contrast the messaging topic's outbox relay, gated inert until real infrastructure activates — its every tick touches shared PostgreSQL and Redis. The rule both encode: a scheduler touching only its own memory may poll freely; one touching shared state is a CONSUMER you gate. All of them die with their context — no orphan scheduler threads survive a context close.""")),
                section("email-as-canonical-job", "Email as the canonical job",
                        "External, slow, flaky, non-critical — the one dependency that makes every lesson in this topic true at once.",
                        text("email:why", """
                                Every guide that teaches queues teaches email, and not from laziness — email is the PERFECT SHAPE. EXTERNAL: an SMTP relay you do not operate, whose outages you cannot fix. SLOW: hundreds of milliseconds to seconds per send, courtesy of TLS handshakes and greylisting. FLAKY: 4xx deferrals are the protocol WORKING AS DESIGNED — "try again later" is a first-class SMTP answer, making retry machinery a requirement. NON-CRITICAL: no checkout blocks on a welcome mail; when the relay is down, the queue holds the work. Four properties, one dependency — swap in SMS, push, or a render service and nothing in the architecture changes.

                                The port is accordingly small and honest — one method whose FAILURE SEMANTICS are the contract:"""),
                        code("email:port", "java",
                                "guide/domain/jobs/EmailSender.java — the port: throwing is the failure contract",
                                "server/src/main/java/guide/domain/jobs/EmailSender.java", """
                                /**
                                 * Send one message; THROW on transport failure — the attempt's error is
                                 * born here. Must tolerate re-sends: a retry after an ambiguous
                                 * failure may double-send, which is why welcome mails carry no
                                 * side effects a duplicate would break.
                                 */
                                void send(String to, String subject, String body);"""),
                        text("email:handler-intro", """
                                The handler reads its arguments from the payload as DATA, renders a one-line template, and hands off to the port. The demo's deterministic injection lives ahead of the port call on purpose — the failures are transport-shaped, the success path is the REAL path, and production is the same file minus the injection guard:"""),
                        code("email:handler", "java",
                                "guide/domain/jobs/WelcomeEmailJobHandler.java — the canonical handler: payload as data, template, port call",
                                "server/src/main/java/guide/domain/jobs/WelcomeEmailJobHandler.java", """
                                @Override
                                public void execute(JobRecord job) {
                                    int failedAlready = job.attempts().size();
                                    if (failedAlready < job.failFirstAttempts()) {
                                        throw new IllegalStateException("injected failure #" + (failedAlready + 1)
                                                + " of " + job.failFirstAttempts() + " — SMTP 421, try again later");
                                    }
                                    JsonNode payload = mapper.readTree(job.payload());
                                    String to = payload.hasNonNull("to") ? payload.path("to").asString()
                                            : "friend@example.com";
                                    String name = payload.hasNonNull("name") ? payload.path("name").asString() : "friend";
                                    emailSender.send(to, "Welcome to the guide, " + name,
                                            "Hi " + name + " — your account is ready. (sent by job " + job.id() + ")");
                                }"""),
                        text("email:real-one", """
                                The guide's adapter is an in-memory RECORDER — the demo must not need a real SMTP account to teach retries, so the sender records what landed (bounded, newest first) and never fails; the flakiness is the handler's injected 421s, and the recording PROVES the invariant that matters: one mail per succeeded job, zero per failed attempt, exactly the attempt history the record claims.

                                The REAL adapter is topic 43's work and needs a sentence here: Spring Mail (spring-boot-starter-mail) wires a JavaMailSender whose send(MimeMessage) maps transport trouble to a MailException — THROWING, exactly the contract this port already speaks, so the handler and the queue do not change by one line when the real relay lands. What changes is the OPERATIONAL checklist: timeouts on the mail connection (an un-timeboxed send inside a retry loop is the unbounded-wait bug wearing a stamp), SPF/DKIM so retries are deliverable at all, and rate awareness — a burst that trips the relay's throttling turns your retries into a self-inflicted outage. Templates stay trivial here; a real system renders from a library at SEND time with inputs IN THE PAYLOAD — fixing a typo does not strand pre-rendered bodies in the queue. And welcome mails carry no side effects a duplicate would break: this topic emails WELCOMES, never REFUNDS.""")),
                section("observability-two-numbers", "Observability: the two numbers",
                        "Queue depth and oldest-job age — the dashboard that catches every failure mode a queue has, two fields wide.",
                        text("obs:numbers", """
                                A queue in trouble has as many failure modes as it has states, and TWO NUMBERS catch them all. DEPTH — records still owing the world an attempt — is the throughput number: producer outrunning consumer, workers down, a dependency so slow completion cannot keep up. OLDEST-JOB AGE — how long the most senior unfinished record has waited — is the latency/stall number. Read them TOGETHER and every condition has a signature: shallow and fresh is healthy; deep and fresh is capacity (deep-but-moving, often fine); SHALLOW AND STALE is a scheduler not firing or workers wedged — the quiet killer, because depth alone looks innocent; DEEP AND STALE is the outage you are already having. Four quadrants, two numbers, the 3am dashboard in a glance.

                                The numbers are COMPUTED, not stored — derived from the snapshot at read time, the age from the oldest non-terminal record's queuedAt against the clock. Derived numbers cannot drift from the records they describe — no counter to forget incrementing in some error path — the most valuable property a dashboard field can have:"""),
                        code("obs:overview", "java",
                                "guide/domain/jobs/ReadQueueOverview.java — depth and oldest pending age, derived at read time",
                                "server/src/main/java/guide/domain/jobs/ReadQueueOverview.java", """
                                JobRecord oldestPending = snapshot.stream()
                                        .filter(job -> !job.state().terminal())
                                        .min(Comparator.comparing(JobRecord::queuedAt))
                                        .orElse(null);
                                int depth = (int) snapshot.stream().filter(job -> !job.state().terminal()).count();
                                return new Overview(counts, depth,
                                        oldestPending == null ? null : oldestPending.id(),
                                        oldestPending == null ? null
                                                : java.time.Duration.between(oldestPending.queuedAt(), clock.instant()).toMillis(),
                                        queue.deadLetters().size());"""),
                        text("obs:render-intro", """
                                The console renders them as first-class citizens — the two chips every other number orbits (quoted from the demo component):"""),
                        code("obs:two-numbers", "ts",
                                "client/src/ui/demos/jobs/console_demo.tsx — the two numbers, first-class",
                                "client/src/ui/demos/jobs/console_demo.tsx", """
                                <span data-testid="jobs-depth" className={CHIP}>
                                  depth {String(overview?.depth ?? 0)}
                                </span>
                                <span data-testid="jobs-oldest-age" className={CHIP}>
                                  {oldestAgeLabel(overview)}
                                </span>"""),
                        text("obs:poll-intro", """
                                and polls while work is pending, at the worker's own one-second beat, stopping when the queue goes idle — the polling predicate is the client's entire state machine, three clauses wide: work waiting, a traced job unfinished, an async ticket in flight:"""),
                        code("obs:poll", "ts",
                                "client/src/ui/demos/jobs/console_demo.tsx — poll at the worker's beat while anything is pending; stop when idle",
                                "client/src/ui/demos/jobs/console_demo.tsx", """
                                const hasPendingWork =
                                  nextOverview.depth > 0 ||
                                  (freshJob !== null && !TERMINAL_STATES.includes(freshJob.state)) ||
                                  nextTickets.some((ticket) => ticket.state !== 'done');
                                if (pollTimer.current !== null) {
                                  window.clearTimeout(pollTimer.current);
                                  pollTimer.current = null;
                                }
                                if (hasPendingWork) {
                                  pollTimer.current = window.setTimeout(() => refresh(focusJobId), POLL_INTERVAL_MS);
                                }"""),
                        text("obs:alerting", """
                                ALERTING thresholds, in prose because they are YOUR call: alert on depth sustained above what a healthy worker drains in a few tick batches (here, tens — the in-memory ceiling is 200, and eviction sheds history before it sheds work); alert on oldest-age past your freshness budget (a welcome mail waiting ten minutes has already failed its purpose even if it eventually succeeds); and page on the RATIO — depth and oldest-age rising together is the deep-and-stale quadrant, where the two numbers say what neither says alone. The dead-letter count rides along as the third, blunter signal — a verdict, not a dashboard: something exhausted its budget, and a human owes the booth a visit.

                                Name the pair once: depth is SUPPLY (work arriving faster than it completes), oldest-age is PROMISE DECAY (the queue's "soon" getting less true by the second). The observability topic (33) wires numbers into meters and alert rules; this topic established which two deserve the wiring.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole queue driven by curl: a retried welcome mail, one dead letter and its replay, the heartbeat's evidence — plain curl, every response read raw.",
                        text("terminal:intro", """
                                With the stack running (server on :8080), the tour walks every state machine in this topic. Each answer is JSON worth reading whole; the timestamps tell the backoff story. The deterministic injection (failAttempts) makes the tour reproducible — attempt counts and gaps below are not luck."""),
                        code("terminal:enqueue-retry", "bash",
                                "enqueue a welcome mail that fails twice — then poll the trace and watch it earn its success",
                                null, """
                                curl -s -X POST localhost:8080/api/demo/jobs/enqueue \\
                                  -H 'content-type: application/json' \\
                                  -d '{"type":"send-welcome-email","payload":{"to":"reader@example.com","name":"Reader"},"failAttempts":2}'
                                # → {"id":"job_9f3…","state":"queued","attemptsLeft":3,"nextAttemptAt":"2026-…T12:00:00Z", …}
                                JOB=job_9f3…
                                sleep 1 && curl -s localhost:8080/api/demo/jobs/jobs/$JOB
                                # → attempts[0]: {n:1, outcome:"failed", error:"injected failure #1 of 2 — SMTP 421…"},
                                #   state:"failed", nextAttemptAt: +1s — the backoff starts
                                sleep 2 && curl -s localhost:8080/api/demo/jobs/jobs/$JOB
                                # → attempts 1,2 failed; nextAttemptAt now +2s after the SECOND failure
                                sleep 3 && curl -s localhost:8080/api/demo/jobs/jobs/$JOB
                                # → state:"succeeded", attempts 1,2 failed + 3 succeeded, finishedAt set
                                curl -s localhost:8080/api/demo/jobs/emails
                                # → exactly one welcome to reader@example.com — one success, one mail
                                curl -s localhost:8080/api/demo/jobs | grep -o '"depth":[0-9]*'
                                # → "depth":0 — the two numbers, back at rest"""),
                        code("terminal:dead-replay", "bash",
                                "poison a job past its budget — inspect the confession, replay it fixed and re-poisoned",
                                null, """
                                curl -s -X POST localhost:8080/api/demo/jobs/enqueue \\
                                  -H 'content-type: application/json' \\
                                  -d '{"type":"send-welcome-email","payload":{"to":"ghost@example.com"},"failAttempts":9}' | grep -o '"id":"[^"]*"'
                                JOB=job_…   # from the enqueue answer
                                sleep 5 && curl -s localhost:8080/api/demo/jobs/dead
                                # → the booth: 3 attempts (1s, 2s gaps), last outcome "dead",
                                #   lastError "injected failure #3 of 9…", finishedAt set
                                curl -s -X POST localhost:8080/api/demo/jobs/dead/$JOB/replay \\
                                  -H 'content-type: application/json' -d '{"failAttempts":0}'
                                # → a FRESH job_… queued, replays:1 — the dead row stays as evidence
                                curl -s -X POST localhost:8080/api/demo/jobs/dead/$JOB/replay \\
                                  -H 'content-type: application/json' -d '{"failAttempts":9}'
                                # → replayed WITHOUT the fix: same fresh shape… and ~4s later it is
                                #   back in /dead — poison stays poison until someone repairs something"""),
                        code("terminal:heartbeat-async", "bash",
                                "the scheduling evidence and the @Async contrast — including saturation, made visible",
                                null, """
                                curl -s localhost:8080/api/demo/jobs/heartbeat && sleep 2 && curl -s localhost:8080/api/demo/jobs/heartbeat
                                # → totalTicks grew by ~2 — @EnableScheduling is alive in this process
                                curl -s -X POST localhost:8080/api/demo/jobs/async \\
                                  -H 'content-type: application/json' -d '{"delayMs":2000}'
                                # → returns INSTANTLY: {"id":"work_…","state":"pending",…}
                                curl -s localhost:8080/api/demo/jobs/async
                                # → the ticket: state done, threadName "jobs-demo-1" — the executor ran it
                                for i in $(seq 1 15); do
                                  curl -s -X POST localhost:8080/api/demo/jobs/async \\
                                    -H 'content-type: application/json' -d '{"delayMs":2000}' > /dev/null &
                                done; wait; curl -s localhost:8080/api/demo/jobs/async
                                # → 16 tickets against a pool of 4+8: some threadNames are YOUR shell's
                                #   curl workers — CallerRunsPolicy, backpressure you can SEE"""),
                        text("terminal:close", """
                                The tour ends where the topic began: fifteen parallel fires turn the async panel into a living lesson in the four knobs — pool threads busy, queue full, overflow RUNNING ON THE CALLERS, named in the output. Fire the same fifteen as QUEUE jobs and the overflow merely waits its turn — retried, visible, budgeted. That contrast — degrade-and-be-seen versus wait-and-be-remembered — is the whole topic in one experiment. The pieces live in domain/jobs and data/jobs; the heartbeat keeps proving the scheduler while you read this.""")));
    }
}
