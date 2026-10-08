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
 * Topic 14 — Webhooks. Group apis, order 4. The reversed API: the service
 * that used to answer your requests now makes requests to you. Built around
 * a real in-app simulator (domain/webhooks + data/webhooks): signed events,
 * a delivery engine with exponential backoff and a dead-letter queue, and a
 * loopback receiver so sender and consumer run in one process — every claim
 * in the prose is code the reader can fire at.
 */
public final class WebhooksTopic {

    private WebhooksTopic() {
    }

    public static Topic topic() {
        return new Topic("webhooks", "Webhooks",
                "The reversed API: they call you. Signing with HMAC-SHA256, "
                        + "delivering with retries, exponential backoff and a dead-letter queue, "
                        + "receiving with fast 2xx — and a loopback simulator where both sides "
                        + "of every event are observable from one process.",
                TopicGroupId.APIS, 4,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("webhooks-are-reversed-apis", "Webhooks are reversed APIs",
                        "You call them; then one day they call you — and every obligation you ever took for granted lands on the other side of the wire.",
                        text("reversed:flip", """
                                Every API in this guide so far has the same posture: YOUR code makes a request, THEIR code answers. A webhook inverts that. Something happens inside a provider — an invoice is paid, a repository is pushed — and the provider POSTs to an endpoint YOU control. That is all a webhook is: an API where the provider is the client and you are the server. The reversal sounds cosmetic; it is not.

                                Start with AUTHENTICATION. When you call Stripe, an API key proves who you are on every request. When Stripe calls you, that mechanism is useless: the request arrives CLAIMING to be from Stripe, and a URL anyone can discover is a URL anyone can POST fiction into — fake "invoice.paid" events that credit accounts. The answer, this topic's middle third, is a SHARED SECRET: the provider signs each request, you verify the signature before believing a byte.

                                Then AVAILABILITY. When their server is down, your client sees an error. When YOUR receiver is down, their retries hammer your door; when it is merely slow, their timeout policy decides your fate. As the webhook SENDER (this topic builds one), you inherit the mirror: deliver despite the consumer's outages — retries, backoff, a dead-letter queue for what never lands. As the RECEIVER you absorb the sender's persistence: duplicates at minimum, because at-least-once is the only guarantee a retrying sender can honestly give. And trust direction flips all the way down: inbound requests are hostile until proven authentic — the validation topic's premise, now with cryptography behind it, because there is no session, no login, nothing but bytes and a shared secret on an open port."""),
                        text("reversed:why-not-poll", """
                                Why webhooks at all — isn't polling enough? Poll a status endpoint every minute and you learn about events within a minute, having paid for 1,440 requests a day of which almost all say "nothing yet". Webhooks push the cost to where the information is: one request per event, the moment it exists. Latency drops from minutes to milliseconds, rate limits stop bleeding, and the trade is this entire topic — you now run a server that must be available, authenticated, and idempotent, where a polling client was a cron job that fails loudly.

                                The honest framing for a Spring Boot developer: a webhook receiver is a controller with the safety obligations of an internet-facing endpoint and none of the framework's defaults helping you. Spring will happily bind the body of an unauthenticated POST into your DTO; routing and JSON binding end where the webhook contract begins. This topic builds BOTH chairs — the sender that signs, retries, and dead-letters, and the receiver that verifies and acknowledges — as one runnable simulator, then teaches each discipline from its code.""")),
                section("designing-a-webhook", "Designing a webhook",
                        "Names in past tense, an envelope around every payload, at-least-once by decree — the contract you write before the first event ships.",
                        text("design:naming", """
                                An event name should read as a FACT, not a command: invoice.paid, not invoice.payment and never createInvoice. PAST TENSE is the load-bearing convention. "invoice.payment" names a thing and tempts the consumer to infer state; "invoice.paid" states something that HAPPENED — and history cannot be undone or re-requested. The same logic bans future tense ("invoice.will-be-charged") and imperatives ("charge-invoice"): if the provider might not do the thing, it is a command, and commands belong in request/response APIs where the caller learns the outcome.

                                Around the name, a small GRAMMAR keeps a catalog of hundreds of events navigable: lower-case, dot-separated domain.event — resources first, actions second (invoice.paid, invoice.voided, customer.subscription.updated). Version in the envelope or a header, never by renaming events under consumers' feet. This simulator enforces the grammar at the one place nothing can bypass — the subscription record's constructor — so subscribe and fire inherit the rule from one line of domain code:"""),
                        code("design:event-type", "java",
                                "guide/domain/webhooks/WebhookSubscription.java — the naming contract, enforced where nothing can skip it",
                                "server/src/main/java/guide/domain/webhooks/WebhookSubscription.java", """
                                /** lower-case dot.notation, segments may contain digits and hyphens: invoice.paid, order.fulfilled-early */
                                private static final Pattern EVENT_TYPE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*(\\\\.[a-z0-9]+(-[a-z0-9]+)*)*");

                                public static boolean isValidEventType(String eventType) {
                                    return eventType != null && EVENT_TYPE.matcher(eventType).matches();
                                }"""),
                        text("design:envelope", """
                                Never POST a bare domain object. Every event rides in an ENVELOPE — four fields a consumer needs before safely processing anything:

                                - id — the unique event identifier, and THE dedupe key: at-least-once means the same event WILL arrive twice (retry after a lost 2xx, a redeploy, a network blip), and consumer idempotency is "have I seen this id?". Without it they dedupe on payload hashes, which two legitimate identical events defeat.
                                - type — the event name, routable WITHOUT parsing the body.
                                - occurredAt — when the FACT happened, not when the request was sent. A delivery that retried for an hour must not look like an event that happened an hour late; analytics and SLAs read this field, not the transport's timestamps.
                                - data — the payload, additive-only as the schema evolves: new fields are fine; renamed or removed fields are breaking changes to consumers you cannot coordinate.

                                The sender builds the envelope once, serializes once, and signs those exact bytes — stored, signed, and sent are the same STRING. A signature over re-serialized bytes signs nothing, which is why fire hands the wire bytes to the engine, not a domain object that might re-serialize differently:"""),
                        code("design:envelope-build", "java",
                                "guide/domain/webhooks/FireWebhookEvent.java — the envelope is composed and serialized once; those bytes are what gets signed and sent",
                                "server/src/main/java/guide/domain/webhooks/FireWebhookEvent.java", """
                                String eventId = "evt_" + UUID.randomUUID();
                                ObjectNode envelope = mapper.createObjectNode()
                                        .put("id", eventId)
                                        .put("type", eventType)
                                        .put("occurredAt", clock.instant().toString());
                                envelope.set("data", request.payload() == null ? mapper.createObjectNode() : request.payload());
                                String wireBytes = mapper.writeValueAsString(envelope);"""),
                        text("design:at-least-once", """
                                State the delivery guarantee out loud, because consumers build against it: webhooks are AT-LEAST-ONCE. Exactly-once would need distributed consensus between the sender's "did the POST succeed?" and the consumer's "did we process it?", and nobody runs two-phase commit over HTTP. The sender retries until acknowledged; a 2xx it never received gets resent; duplicates arrive, and DEDUPLICATION IS THE CONSUMER'S JOB, keyed on the envelope id — a credit applied twice is a refund bug, an email sent twice is spam, an upsert keyed on the event id is invisible.

                                At-most-once is what you drift into by NOT retrying: fire, hope, forget — events silently lost whenever a consumer deploys at the wrong moment. At-least-once plus consumer idempotency is the only combination that loses nothing and tolerates duplicates, which is why Stripe, GitHub, and AWS SNS document exactly that. The simulator's receipts log flags the second arrival of an event id as a DUPLICATE rather than rejecting it — you will watch it happen in the demo.""")),
                section("signing-and-verifying", "Signing and verifying",
                        "HMAC-SHA256 with a timestamp, compared in constant time — the shared-secret proof that an inbound request is really from your provider.",
                        text("signing:scheme", """
                                The scheme this simulator implements is the industry's de facto standard (Stripe's, GitHub's, Slack's shape): HMAC-SHA256 over a message built from a TIMESTAMP and the raw body, keyed by a SHARED SECRET configured once on both sides. The sender puts the timestamp and the digest in one header — X-SDG-Signature: t=1760068800,v1=5257a… — and the receiver recomputes the digest from its copy of the secret, the t it was handed, and the exact bytes it received. Equal digests prove the sender holds the secret AND the body was not altered in flight, because both are inputs to the HMAC. No certificates, no PKI, no token service — one symmetric key, four lines of JDK crypto:"""),
                        code("signing:produce", "java",
                                "guide/domain/webhooks/SignatureExhibit.java — producing the header: HMAC over timestamp + body",
                                "server/src/main/java/guide/domain/webhooks/SignatureExhibit.java", """
                                public static String signatureHeader(String secret, String timestamp, String body) {
                                    byte[] mac = hmacSha256(secret, timestamp + "." + body);
                                    return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac);
                                }"""),
                        code("signing:mac", "java",
                                "guide/domain/webhooks/SignatureExhibit.java — the whole cryptographic engine, from the JDK's javax.crypto",
                                "server/src/main/java/guide/domain/webhooks/SignatureExhibit.java", """
                                static byte[] hmacSha256(String secret, String message) {
                                    try {
                                        Mac mac = Mac.getInstance("HmacSHA256");
                                        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                                        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
                                    } catch (GeneralSecurityException unavailable) {
                                        throw new IllegalStateException("HmacSHA256 is unavailable in this JVM", unavailable);
                                    }
                                }"""),
                        text("signing:timestamp", """
                                WHY the timestamp is INSIDE the signed message instead of a separate header: it makes signatures perishable. Suppose an attacker records a legitimate signed request (a compromised log, a proxy dump) and replays it next week. Without freshness in the proof, the recorded HMAC is as valid as the day it was made, and the attacker re-triggers the event forever. With t bound into the signed material, the receiver checks |now − t| against a tolerance window (here five minutes, the industry norm) and rejects anything outside it — the replay's own signature betrays its age. The window is two-sided because it is a clock-skew allowance, not a security dial: signed moments from the future are as suspect as moments from the past.

                                A replay INSIDE the window still verifies — true of every timestamped scheme — and that is the second reason the envelope's id exists: the dedupe store defends at short range, the timestamp window at long range. Two defenses, two ranges, one id."""),
                        text("signing:constant-time", """
                                The comparison itself is where implementations quietly become vulnerable. The naive verify ends in expected.equals(presented) — and String equals returns at the first differing character. An attacker who can submit candidate signatures and MEASURE how long the 401 takes learns, byte by byte, how many leading characters were right; a few hundred timed requests per position reconstructs a valid signature without ever knowing the secret. That is the timing attack, and the fix is a comparison whose time does not depend on WHERE bytes differ: MessageDigest.isEqual walks the full length for equal-length inputs, XORing every position into one verdict. The exhibit's verify ends there — and note what its own test refuses to promise: you cannot TEST timing safety, you can only USE the primitive that has it:"""),
                        code("signing:verify", "java",
                                "guide/domain/webhooks/SignatureExhibit.java — the consumer's last three moves: window check, recompute, constant-time compare",
                                "server/src/main/java/guide/domain/webhooks/SignatureExhibit.java", """
                                Instant signedAt = Instant.ofEpochSecond(seconds);
                                if (signedAt.isBefore(now.minus(tolerance)) || signedAt.isAfter(now.plus(tolerance))) {
                                    return new SignatureVerdict(false, "stale",
                                            "timestamp outside the ±" + tolerance.toSeconds() + "s replay window — possible replay");
                                }
                                // …
                                byte[] expected = hmacSha256(secret, timestamp + "." + body);
                                // constant-time compare — never String.equals on a digest (timing attacks)
                                boolean matches = MessageDigest.isEqual(expected, presentedBytes);"""),
                        text("signing:rotation", """
                                Secrets rotate — staff leave, laptops are stolen, auditors ask when — and zero-downtime rotation is why the header carries a VERSION TAG (v1=) instead of a bare digest. During cutover the sender can emit BOTH signatures for one request — t=…,v1=<old>,v2=<new> — and the receiver accepts any known version. Configure the new secret everywhere, keep accepting v1 through the longest in-flight retry, then retire the old version. The simulator keeps a single v1 but names the field for the version it is, because a scheme that cannot version its signatures is a scheme that never rotates.

                                The rest is hygiene, and it is password hygiene: signing secrets are long (the subscription record refuses under 16 characters), random, shown ONCE at creation, masked everywhere else (this simulator's listing carries only whsec_…t2), and revocable by replacing the subscription. A webhook secret is a password that signs requests; treat it like one.""")),
                section("delivering-retries-backoff-dead-lettering", "Delivering: retries, backoff, dead-lettering",
                        "The sender's engine: fail, wait 1s, fail, wait 2s, fail, wait 4s — and when the math runs out, park the event where a human can find it.",
                        text("deliver:engine", """
                                Delivery is now YOUR reliability problem. The consumer's endpoint will 500 during their deploys, time out under load, and refuse connections mid-restart — and none of that may lose an event. The engine in domain/webhooks is the whole policy in one class: the attempt budget (MAX_ATTEMPTS = 5), the backoff curve, and the state machine (pending → delivering → delivered, or → dead-lettered). Its one network call hides behind a one-method PORT, which is why the entire descent into the dead-letter queue is unit-tested with a fake port and a fake clock — no sockets, no sleeps. The engine never reads the wall clock to decide what is due; time arrives as a PARAMETER:"""),
                        code("deliver:tick-seam", "java",
                                "guide/domain/webhooks/DeliveryEngine.java — the scheduler seam: time is a parameter, so tests and the ticker drive the same method",
                                "server/src/main/java/guide/domain/webhooks/DeliveryEngine.java", """
                                public List<WebhookDelivery> attemptNextDue(Instant now) {
                                    List<WebhookDelivery> touched = new ArrayList<>();
                                    for (WebhookDelivery due : store.dueDeliveries(now, TICK_BATCH)) {
                                        touched.add(attempt(due, now));
                                    }
                                    return List.copyOf(touched);
                                }"""),
                        text("deliver:backoff", """
                                The retry CADENCE is exponential backoff: after failure n, wait base · 2^(n−1), capped. This simulator uses 1s, 2s, 4s, then 8s and flat — the cap is as deliberate as the growth. Growth respects the CONSUMER: an endpoint down because it is overloaded does not need CPR at one-second intervals; doubling the gap gives recovery room to outrun your retries. The cap respects YOUR freshness SLA: unbounded growth (1s → … → 1 hour) leaves an event "in flight" a day later, arriving after the world it described moved on. A capped schedule plus a bounded budget promises the episode resolves in a known window — here 1+2+4+8 = 15 seconds across five attempts. The curve as pure math, straight from the engine and straight into its unit test:"""),
                        code("deliver:curve", "java",
                                "guide/domain/webhooks/DeliveryEngine.java — the backoff curve: growth, then a cap, both load-bearing",
                                "server/src/main/java/guide/domain/webhooks/DeliveryEngine.java", """
                                static Duration backoffAfter(int failedAttempts) {
                                    if (failedAttempts < 1) {
                                        throw new IllegalArgumentException("backoff starts after the first failure");
                                    }
                                    long exponent = Math.min(failedAttempts - 1, 3);
                                    Duration delay = BASE_BACKOFF.multipliedBy(1L << exponent);
                                    return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
                                }"""),
                        text("deliver:jitter", """
                                JITTER is the curve's industry peer: add a random fraction (this engine: 0–100% of the delay, injected as a DoubleSupplier so tests pin it to zero) to every computed wait. The reason is SYNCHRONIZATION. Ten thousand senders that all failed at t=0 with identical schedules will all retry at t=1s, fail together against a still-recovering consumer, all retry at t=3s — a thundering herd that stamps out the recovery the backoff was buying. Randomized offsets decorrelate the herd into a drizzle the endpoint can drink; AWS's "Exponential Backoff and Jitter" measurements made jitter part of the standard recipe. The engine applies it on top of the capped curve, and the deterministic test injects a fixed fraction to prove the arithmetic (1s base, 50% → exactly 1.5s)."""),
                        text("deliver:dlq", """
                                What happens when attempt five fails separates a toy from a system: the delivery is DEAD-LETTERED — terminal state, nothing retries it automatically, NOTHING DELETES IT EITHER. That parking lot is the dead-letter queue, and its product is FORENSICS + REPLAY: an operator reads the attempts (five 500s tells one story; 500, 503, timeout, connection-refused tells another), fixes the consumer, and REPLAYS the event as a fresh delivery — at which point every idempotency discipline above earns its keep, because the consumer may have half-processed attempt three. An engine without a DLQ either retries forever or drops silently; both fail the at-least-once contract. The budget check is one line at the decision point:"""),
                        code("deliver:budget", "java",
                                "guide/domain/webhooks/DeliveryEngine.java — the attempt budget decides the outcome; the DLQ is the else-branch nobody retries",
                                "server/src/main/java/guide/domain/webhooks/DeliveryEngine.java", """
                                int attemptNumber = current.attempts().size() + 1;
                                boolean lastChance = attemptNumber >= MAX_ATTEMPTS;
                                WebhookDelivery next;
                                if (outcome.delivered()) {
                                    // …
                                } else if (lastChance || subscription == null) {
                                    next = current.advanced(WebhookDelivery.DeliveryState.DEAD_LETTERED, null,
                                            new WebhookDelivery.Attempt(attemptNumber, now,
                                                    WebhookDelivery.Attempt.Outcome.DEAD_LETTERED,
                                                    outcome.httpStatus(), outcome.signatureHeader()));
                                } else {
                                    // …
                                }"""),
                        text("deliver:transport", """
                                The real network call is Spring's built-in RestClient with TIMEOUTS SET, because inside a retry loop an unbounded client is catastrophic: a consumer that accepts the connection then never answers would hold every attempt forever, and the engine would stall on sockets instead of scheduling. Connect timeout 750ms, read timeout 2s — deliberately shorter than the receiver's 5-second failure-injection delay, which is why respond=timeout in the demo produces attempts with httpStatus: null followed by the same backoff a 500 earns. Note also what rides along: the signature computed over the STORED bytes with a FRESH timestamp per attempt, so every retry carries a different signature — replaying attempt one's signature at attempt four's time dies against the consumer's window:"""),
                        code("deliver:adapter", "java",
                                "guide/data/webhooks/HttpDeliveryAdapter.java — the one network call: fresh signature per attempt, timeouts, status as data",
                                "server/src/main/java/guide/data/webhooks/HttpDeliveryAdapter.java", """
                                String timestamp = String.valueOf(clock.instant().getEpochSecond());
                                String signature = SignatureExhibit.signatureHeader(subscription.secret(), timestamp, body);
                                Integer status;
                                try {
                                    status = client.post()
                                            .uri(URI.create(subscription.url()))
                                            .header(SignatureExhibit.SIGNATURE_HEADER, signature)
                                            .header(SignatureExhibit.EVENT_HEADER, eventType)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .body(body)
                                            .exchange((request, response) -> response.getStatusCode().value());
                                } catch (RuntimeException transport) {
                                    // refused, reset, timed out — no HTTP response exists to report
                                    return new AttemptOutcome(false, null, signature);
                                }"""),
                        text("deliver:scheduler", """
                                What paces the whole machine is one @Scheduled heartbeat — a tick each second asking the engine what time has made runnable. fixedDelay rather than fixedRate is deliberate: fixedDelay spaces tick ENDS from tick STARTS, so a slow batch delays the next beat instead of stacking concurrent ticks on overloaded threads. The ticker carries no policy whatsoever — one line delegating to the same attemptNextDue(now) the tests drive, which is the entire proof it cannot diverge from tested behavior. Registration is @EnableScheduling on the demo's own config class (idempotent if a later topic's config adds it too):"""),
                        code("deliver:ticker", "java",
                                "guide/data/webhooks/WebhookDeliveryTicker.java — the heartbeat: no policy, one delegation",
                                "server/src/main/java/guide/data/webhooks/WebhookDeliveryTicker.java", """
                                /** One beat: attempt everything whose nextAttemptAt has arrived. */
                                @Scheduled(fixedDelay = 1000)
                                public void tick() {
                                    engine.attemptNextDue(clock.instant());
                                }"""),
                        demo("deliver:simulator-demo", "api:webhook-simulator",
                                "The webhook simulator",
                                "Subscribe a consumer, fire signed events at it, script the receiver's behavior per fire (200, 500, or timeout), and watch the engine work: attempt timeline, backoff countdowns, the dead-letter badge — with the receiver's receipts (signature verdicts, duplicates) beside the sender's view."),
                        text("deliver:demo-tour", """
                                The demo above is the whole topic in one console. Subscribe with the default URL and you point at the in-app receiver; fire invoice.paid with no respond and the next tick delivers it — one attempt, 200, a receipt saying verified. Fire with respond=500 and reload: attempt 1 fails, the delivery shows pending with nextAttemptAt one second out; the timeline fills as ticks land — 1s, 2s, 4s, 8s — until attempt 5 records dead-lettered and the row wears the DLQ badge. Fire with respond=timeout and attempts read httpStatus: null: the adapter's 2-second read timeout expired before the receiver's scripted 5-second delay ended — transport failure, same backoff. Watch the receipts view between fires: every attempt the receiver SAW is a receipt, duplicates flagged, each with its signature verdict. Both chairs of the reversed API, one process.""")),
                section("receiving-fast-2xx", "Receiving: fast 2xx, verify first",
                        "The consumer's contract: authenticate the bytes before believing them, acknowledge before processing them, dedupe before acting on them.",
                        text("receive:verify-first", """
                                A webhook receiver's first rule orders its own code: VERIFY THE SIGNATURE BEFORE INTERPRETING THE BODY. Not after binding the DTO, not after logging pretty JSON — before. Until the HMAC matches, the request is attacker-controlled bytes on a public URL, and every cycle spent parsing it is attack surface: parser differentials, deserialization gadgets, JSON bombs all live in the "before verification" window if you let it exist. The receiver controller binds the body as a raw String for exactly this reason — a signature over the exact received bytes cannot be checked against a re-serialized parse of them (field order, whitespace, number formatting all change the bytes). Parse shallowly AFTER; the envelope's type plus the event header route the event without business code ever seeing unverified structure:"""),
                        code("receive:order", "java",
                                "guide/domain/webhooks/ReceiveWebhookDelivery.java — the order is the contract: verify, then (and only then) read",
                                "server/src/main/java/guide/domain/webhooks/ReceiveWebhookDelivery.java", """
                                // 1. verify FIRST — over the exact received bytes, against every secret that
                                // could have signed (the demo receiver serves all loopback subscriptions; a real
                                // consumer has one configured secret, or one per known producer)
                                SignatureVerdict verdict = verifyAgainstLoopbackSecrets(signatureHeader, body, tolerance);
                                if (!verdict.valid()) {
                                    return recordAndAnswer(401, false, "(unverified)", eventHeaderOrFallback(eventHeader),
                                            verdict, false, "refused before parsing — signature verdict: " + verdict.verdict());
                                }"""),
                        text("receive:fast-2xx", """
                                The second rule is about the clock: RESPOND 2xx IN MILLISECONDS, THEN PROCESS. The sender's retry policy watches your response time — answer slowly and you teach it to time you out (this simulator: 2 seconds), mark the attempt failed, and redeliver: your "still working on it" becomes a DUPLICATE heading your way. The correct shape is receive → verify → acknowledge → enqueue for async processing — the background-jobs topic's territory (@Async or a queue). The webhook handler is an INBOX, not a worker. Before the 2xx, only the cheap safe trio happens: verify the signature, check the replay window, record the event id. Everything touching a database with business meaning happens after, keyed so a redelivery cannot double-apply.

                                Answer with STATUS CODES the sender's policy reads: 2xx means done; anything else means try again later. A 500 you answer deliberately is indistinguishable from a crash — exactly why respond=500 drives the sender's retry machinery so cleanly — and a 2xx answered "to be nice" during a processing failure tells the sender the event landed; it will never retry, and the event is lost to a queue you invented silently."""),
                        text("receive:dedupe-window", """
                                The third rule closes the loop the other two opened: DEDUPE ON THE EVENT ID. At-least-once plus fast-2xx guarantees duplicates — the sender retries anything unacknowledged, and "unacknowledged" includes "your 2xx got lost in the redeploy". The consumer keeps processed event ids (a set, a table, a cache with a TTL longer than the sender's longest retry episode) and treats a repeat as acknowledged-and-ignored. The replay WINDOW is the same discipline at another timescale: within ±5 minutes the signature alone cannot reject a resent request, so the id store is the only defense at short range; beyond it, the timestamp does the work. The receiver records both facts on every receipt — verdict and duplicate flag — so the receipts view reads like the audit log it is:"""),
                        code("receive:controller", "java",
                                "guide/presentation/demo/WebhookReceiverController.java — the consumer as a controller: raw bytes in, status out",
                                "server/src/main/java/guide/presentation/demo/WebhookReceiverController.java", """
                                @PostMapping(value = "/api/demo/webhooks/receiver",
                                        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
                                ResponseEntity<ReceiveWebhookDelivery.Outcome> receive(
                                        @RequestHeader(name = SignatureExhibit.SIGNATURE_HEADER, required = false) String signature,
                                        @RequestHeader(name = SignatureExhibit.EVENT_HEADER, required = false) String event,
                                        @RequestBody String body,
                                        @RequestParam(name = "respond", required = false) String respond,
                                        @RequestParam(name = "toleranceSeconds", required = false) Long toleranceSeconds) {
                                    Duration tolerance = clampTolerance(toleranceSeconds);
                                    ReceiveWebhookDelivery.Outcome outcome = receiveWebhookDelivery.receive(
                                            signature, event, body, respond, tolerance);
                                    return ResponseEntity.status(outcome.status()).body(outcome);
                                }"""),
                        text("receive:outsider", """
                                Notice one deliberate heresy in that controller: it answers with plain ResponseEntity status codes, not this app's usual error envelope — the receiver is playing ANOTHER SERVICE, and keeping the simulation honest means refusing the surrounding app's mother tongue. The 401 it answers for a bad signature is what YOUR consumer should answer too: authenticated refusal, no detail an attacker can mine. The two query parameters are the demo's teaching hooks — ?respond= scripts one request's answer, ?toleranceSeconds= shrinks the replay window so the terminal companion demonstrates a rejected replay in two seconds — demo-only surface, not consumer behavior to copy.""")),
                section("the-local-development-story", "The local development story",
                        "Webhooks need a reachable URL — which is exactly what localhost is not. The simulator's answer: both sides in one process, plus the tunnels for when you need the real thing.",
                        text("local:problem", """
                                The development problem is structural: a webhook is the provider CALLING YOU, and your dev machine is unreachable from the internet. The provider's console demands a public HTTPS URL; your Spring Boot app is on localhost:8080 behind a router that will never forward to it. Every solution is one of three shapes.

                                THE LOOPBACK (this simulator): put the receiver IN the same process as the sender. The default subscription URL targets the app's own /api/demo/webhooks/receiver via local.server.port, so the whole journey — signing, delivery, retry, backoff, dead-letter, receipt — runs over real HTTP on real localhost sockets with zero external moving parts. What you lose is real-provider semantics (their exact retry cadence and signature format) and internet reality (TLS, proxies); what you gain is determinism — scriptable receiver behavior, queryable timelines, nothing that costs money or sleeps on a third party's schedule. For LEARNING the mechanics and for TESTING consumer logic (sign a fixture, POST it to MockMvc, assert — exactly how this topic's own tests run the receiver with a fixed clock), loopback is unbeatable."""),
                        text("local:tunnels", """
                                THE TUNNEL, when it must be the real provider: ngrok, cloudflared, localtunnel — a client on your machine opens an outbound connection to a public relay and hands you a public https://xyz.ngrok.app URL forwarding to localhost. You paste it into the provider's console and live events start arriving, TLS and all. The costs: another moving part (free tunnel URLs expire — every restart means re-registering the webhook), events hitting a dead relay count as failures against the provider's retry policy (check whether yours DISABLES webhooks after enough consecutive failures — Stripe does), and a public URL pointed at a dev process is a security surface — verify signatures in dev too.

                                THE ECHO BOX, when you want to run nothing: webhook.site, requestbin — a hosted page that shows whatever arrives at a throwaway URL. Perfect for INSPECTING what a provider actually sends (field names, header casing, retry cadence against a URL that always 500s), useless for testing YOUR code, because your code is not in the loop. The professional workflow chains all three: echo box to learn the wire format, tunnel to develop against live events, loopback/MockMvc for the repeatable CI suite where no tunnel exists."""),
                        text("local:client-console", """
                                The guide's console is the loopback story's front half — subscriptions list, fire form with the scripted receiver behavior, the live deliveries timeline with backoff countdowns and DLQ badge, receipts beside it. Its wiring is deliberately boring: plain fetch against the demo endpoints, polled while anything is pending — the teaching lives server-side, the client is a window onto it (the polling predicate from the demo component):"""),
                        code("local:poll", "ts",
                                "client/src/ui/demos/api/webhook_simulator_demo.tsx — poll while work is in flight, stop when the queue drains",
                                "client/src/ui/demos/api/webhook_simulator_demo.tsx", """
                                const hasPending = deliveries.some(
                                  (delivery) => delivery.state === 'pending' || delivery.state === 'delivering',
                                );
                                if (hasPending) {
                                  pollTimer = setTimeout(refresh, POLL_INTERVAL_MS);
                                }"""),
                        text("local:curl-retry", """
                                One curl flag recurs below and deserves its own paragraph: --retry. The tour polls the timeline with --retry 3 --retry-delay 1 — the CLIENT playing the resilience discipline the engine plays server-side. The symmetry is the lesson: at-least-once is a WHOLE-PATH property — the sender retries the consumer, your tooling retries the read paths it depends on, and nothing loses data to a blip. A plain REST endpoint makes a failed request YOUR problem to notice; the reversed API reverses that too.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole simulator driven by curl: subscribe, fire a happy event, then walk one all the way to the dead-letter queue — and replay a captured signature against a tight window.",
                        text("terminal:intro", """
                                With the stack running (server on :8080), the tour below is the topic's proof of work — each response is JSON worth reading whole, and the attempt timeline tells the backoff story in timestamps. Replace SUB_ID with the id from the subscribe response (read it straight from the raw JSON)."""),
                        code("terminal:subscribe", "bash",
                                "subscribe — the default URL points at the in-app receiver; the secret is shown this once",
                                null, """
                                curl -s -X POST localhost:8080/api/demo/webhooks/subscriptions \\
                                  -H 'content-type: application/json' \\
                                  -d '{"secret":"whsec_terminal_tour_secret_01","eventTypes":["invoice.paid"]}'
                                # → {"id":"sub_9f3…","urlDefaulted":true,"secret":"whsec_terminal_tour_secret_01", …}
                                #   note the secret appears HERE and never again — listings show whsec_…01"""),
                        code("terminal:fire-happy", "bash",
                                "fire a happy-path event — the next tick (≤1s) delivers it",
                                null, """
                                SUB_ID=sub_9f3…   # from the subscribe response
                                curl -s -X POST localhost:8080/api/demo/webhooks/subscriptions/$SUB_ID/fire \\
                                  -H 'content-type: application/json' \\
                                  -d '{"eventType":"invoice.paid","payload":{"amount":4200,"currency":"usd"}}'
                                sleep 2
                                curl -s localhost:8080/api/demo/webhooks/subscriptions/$SUB_ID/deliveries
                                # → state "delivered", one attempt: {n:1, outcome:"delivered", httpStatus:200}
                                curl -s localhost:8080/api/demo/webhooks/receipts
                                # → the receiver's side: signature "verified", duplicate false"""),
                        code("terminal:fire-dlq", "bash",
                                "force every attempt to fail — watch exponential backoff walk it to the DLQ",
                                null, """
                                curl -s -X POST localhost:8080/api/demo/webhooks/subscriptions/$SUB_ID/fire \\
                                  -H 'content-type: application/json' \\
                                  -d '{"eventType":"invoice.paid","payload":{},"respond":"500"}'
                                # → the fire receipt: its "id" — poll the timeline; attempts land ~1s, 2s, 4s, 8s after each failure:
                                for i in 1 2 3 4 5; do
                                  sleep 4
                                  curl -s --retry 3 --retry-delay 1 \\
                                    localhost:8080/api/demo/webhooks/subscriptions/$SUB_ID/deliveries
                                done
                                # → attempts 1..4 "failed" (httpStatus 500), then attempt 5 "dead-lettered"
                                #   state "dead-lettered", nextAttemptAt gone — the DLQ row, ~15s after fire
                                # timeout flavor: re-fire with "respond":"timeout" — attempts read
                                #   httpStatus:null (read timeout beat the receiver's 5s scripted delay)"""),
                        code("terminal:replay", "bash",
                                "capture a signature, wait out a tight window, replay it — refused as stale",
                                null, """
                                # grab the freshest attempt's signature + the exact envelope bytes it signed
                                BODY=$(curl -s localhost:8080/api/demo/webhooks/subscriptions/$SUB_ID/deliveries \\
                                  | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["deliveries"][0]["payload"]))')
                                SIG=$(curl -s localhost:8080/api/demo/webhooks/subscriptions/$SUB_ID/deliveries \\
                                  | python3 -c 'import json,sys; print(json.load(sys.stdin)["deliveries"][0]["attempts"][-1]["signatureHeader"])')
                                curl -s -X POST 'localhost:8080/api/demo/webhooks/receiver?toleranceSeconds=1' \\
                                  -H 'content-type: application/json' \\
                                  -H "X-SDG-Signature: $SIG" -H 'X-SDG-Event: invoice.paid' \\
                                  -d "$BODY"
                                # → 200: still inside the window — this IS why consumers dedupe by id
                                sleep 2
                                curl -s -X POST 'localhost:8080/api/demo/webhooks/receiver?toleranceSeconds=1' \\
                                  -H 'content-type: application/json' \\
                                  -H "X-SDG-Signature: $SIG" -H 'X-SDG-Event: invoice.paid' \\
                                  -d "$BODY"
                                # → 401 {"signature":"stale", … "replay window"} — the timestamp did its job"""),
                        text("terminal:close", """
                                The tour ends where the topic began: a two-second-old signature verifies (and the receipts log flags the DUPLICATE — dedupe is the consumer's job even on the happy path), then the same bytes are refused as stale once the window closes — freshness and idempotency, two defenses at two ranges, both visible in one receipt log. That is the reversed API in miniature: everything you took for granted as a caller is now a design YOU ship. The pieces live in domain/webhooks (engine, signing, use cases) and data/webhooks (transport, store, ticker); the receipts endpoint is the audit log you did not know you needed until the retries started.""")));
    }
}
