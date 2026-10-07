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
 * Topic 43 — Third-party integrations. Group platform, order 14. The
 * capstone that turns the platform arc outward: every integration is a
 * dependency you CHOSE, and the topic's whole argument is that the
 * choosing matters less than the CONTAINMENT — ports in the domain,
 * adapters outside, fakes for every demo and test, sandbox postures the
 * demo can flip, keys as verdicts (never values), quotas as per-
 * integration state, and blast radius that is bounded by construction.
 * The live demo (thirdparty:hub) runs mail (Spring Mail — a REAL
 * JavaMailSender-backed adapter is constructed but never called), a
 * Stripe-shaped payments fake with the provider's documented test
 * vectors, and an S3-shaped object store — each reporting posture, last
 * outcome, quota state and key verdict, with a dial that exhausts one
 * integration while its siblings keep answering. Zero network at
 * runtime, in tests, everywhere — the constraint that shaped every
 * adapter, and the discipline the topic argues you should keep until
 * the moment you deliberately go live.
 */
public final class ThirdPartyTopic {

    private ThirdPartyTopic() {
    }

    public static Topic topic() {
        return new Topic("third-party", "Third-Party Integrations",
                "Integrating vs building: every third-party service is a dependency you chose — "
                        + "so contain it. Mail behind a port with a real JavaMailSender adapter that is "
                        + "constructed but never called, payments Stripe-shaped with the provider's own "
                        + "test vectors, storage S3-shaped with validated keys and bounded state — and the "
                        + "operational frame that holds it all: keys as verdicts (never values), quotas as "
                        + "per-integration state, blast radius bounded by construction. The hub demo runs "
                        + "all three offline, deterministically, with a dial that proves containment live.",
                TopicGroupId.PLATFORM, 14,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("integrating-vs-building", "Integrating vs building",
                        "Build vs buy is rarely about capability — it is about what you accept depending on. The third-party decision is a containment decision, and the rest of the topic is the machinery of containment.",
                        text("ivb:decision", """
                                EVERY BACKEND OF ANY AGE is a composite of other people's services: a mail relay, a payments processor, an object store, a SMS gateway, a maps API, a search vendor. The INTEGRATING VS BUILDING question is not "could we build this?" — with enough time you could build anything — it is "what does this dependency cost us in the dimensions we care about?" LATENCY: every call is a network round trip inside your request path unless you work to keep it out. FLAKINESS: providers have incidents, greylist, throttle, and change behavior under you, on their schedule. SECRETS: a provider means credentials whose leak radius you must engineer against. QUOTAS: providers meter you, and the meter is a failure mode your users feel. And BLAST RADIUS: when the dependency degrades, how much of YOUR product degrades with it? A checkout that fails because the receipt-mail relay is down has answered that question badly.
                                
                                The guide's position, built from every layer before this topic: INTEGRATE OFTEN, but contain ALWAYS. You should not write your own SMTP stack, your own PCI-compliant payment rails, or your own distributed object store — those are commodities where the provider's whole business is being better at it than you. But the CONTAINMENT is yours to lose, and it is cheap when done at the right place: the port. Every integration in this topic — mail, payments, storage — runs behind a small interface in the domain, with the provider machinery quarantined in the data layer, wired by the config. That is the same dependency rule topic 32 enforced mechanically (presentation → domain ← data), applied to the outside world: the domain says WHAT ("send this mail", "charge this token"), an adapter says HOW (SMTP, Stripe's REST API, S3's PUT), and nothing above the port can tell which adapter answered.
                                
                                One vocabulary decision up front, because the demo depends on it: POSTURE. Every adapter in this topic answers in one of three postures —
                                """),
                        code("ivb:posture", "java",
                                "guide/domain/thirdparty/Posture.java — the three postures: in-process fake, provider test semantics, and live (which this guide constructs but never calls)",
                                "server/src/main/java/guide/domain/thirdparty/Posture.java", """
                                public enum Posture {

                                    FAKE("fake"),
                                    SANDBOX("sandbox"),
                                    LIVE("live");"""),
                        text("ivb:contract", """
                                FAKE is an adapter the repo owns: in-process, deterministic, no network, no credential — what every demo and every test in this guide runs. SANDBOX is the provider's TEST WORLD: real-shaped semantics (documented test tokens, relay greylisting, provider size ceilings) with the same zero-network guarantee — the posture you graduate to before live, and the one that makes CI meaningful: the code paths a checkout exercises against the sandbox are the same ones production runs. LIVE is the real provider, real key, real consequences — and the one rule about live that this topic will repeat: LIVE IS A KEY-AND-WIRING DECISION MADE AT DEPLOY TIME, never a runtime dial. That is why the shared port contract below returns whether a posture change happened: fakes flip, real adapters refuse.
                                
                                The contract every integration port carries, beside its operations:
                                """),
                        code("ivb:ports", "java",
                                "guide/domain/thirdparty/IntegrationPort.java — posture reporting, the scenario dial, and reset: the scenario-control surface all three ports share",
                                "server/src/main/java/guide/domain/thirdparty/IntegrationPort.java", """
                                Posture posture();

                                boolean adoptPosture(Posture next);

                                void reset();"""),
                        text("ivb:scenario", """
                                This is the app's standing fake/live invariant, now in its third form. The ScenarioStore gave the DATA areas a per-area runtime switch (topic 18's mongo, topic 41's reactive fetch); the reactive topic's WebClient port took a stub transport and a live one and chose at request time; and here the switch becomes an explicit posture on the port itself. The store's mechanism is worth re-reading because it is the same idea:
                                """),
                        code("ivb:store", "java",
                                "guide/core/scenario/ScenarioStore.java — the per-area override the whole app already uses: one area flips without touching the global mode",
                                "server/src/main/java/guide/core/scenario/ScenarioStore.java", """
                                public ScenarioDataMode dataModeFor(String area) {
                                    Objects.requireNonNull(area, "area");
                                    ScenarioDataMode override = areaModes.get(area);
                                    return override != null ? override : state.dataMode();
                                }"""),
                        demo("ivb:demo", "thirdparty:hub", "The integration hub, offline",
                                "One hub, three integrations (Spring Mail, Stripe-shaped payments, S3-shaped storage), each card answering the on-call questions: POSTURE (fake or sandbox — flip it), the LAST operation's outcome (sent / queued / declined / stored / rate-limited), QUOTA state (calls used of the limit), and KEY handling (a verdict — present or not — never a value). Run each integration through its fake; then turn the quota dial down and watch ONE integration go rate-limited while its siblings keep answering: blast radius, contained live."),
                        text("ivb:hub-reading", """
                                What to watch in the hub, because the rest of the topic unpacks it panel by panel. THE POSTURE CHIPS flip fake ↔ sandbox per integration — mail starts deferring first contacts when sandboxed (a real relay behavior, modeled deterministically), payments start answering testMode=true, storage starts enforcing a provider ceiling. THE OUTCOME LINE is the last operation's provider answer — including DECLINES and RATE-LIMITS, which are answers, not errors. THE QUOTA LINE is per-integration state: dial mail to 1, send twice, and the second answer is rate_limited with the provider call never made. THE KEY LINE is a verdict about an env var — and the demo cannot show you a key VALUE because no code path produces one. Everything you see is deterministic, offline, and quotable in a terminal tour at the end.""")),
                section("spring-mail", "Spring Mail: the integration every app has",
                        "One versionless starter, one port, two adapters — a fake the demo runs and a real JavaMailSender-backed adapter that is wired to prove the drop-in and never called. Plus the boundary guard every mail integration needs: header-injection rejection.",
                        text("sm:what", """
                                MAIL IS THE CANONICAL first integration: external (an SMTP relay you do not operate), slow (hundreds of milliseconds to seconds), flaky (greylisting, 4xx deferrals, provider incidents), and non-critical (no checkout blocks on a welcome mail) — topic 29 called it the perfect shape for a background job, and this topic adds the other half of that story: what the SEND looks like when you integrate it properly. Spring Mail is one starter, versionless through the Boot 4.1.1 BOM like every first-party starter, and it brings three things: spring-context-support's JavaMailSender interface and JavaMailSenderImpl (the adapter over jakarta.mail), the Angus Mail transport, and Boot's MailSenderAutoconfiguration — which stays DORMANT unless spring.mail.host is set, a property this repo deliberately never sets. The starter's job here is the SUBJECT of the section, not a live relay connection: nothing in this app opens a mail socket.
                                """),
                        code("sm:pom", "xml",
                                "server/pom.xml — Spring Mail as the section's subject: versionless through the Boot 4.1.1 BOM; JavaMailSenderImpl opens its socket per send, never at construction",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-mail</artifactId>
                                </dependency>"""),
                        text("sm:port", """
                                The port decision is where integrating becomes architecture. The domain does not want "JavaMailSender" — it wants "send this message and tell me what happened", which is a smaller, stabler contract that happens to be implementable by a fake, by a test double, and by the real adapter alike. Notice also what the port does NOT carry: no MimeMessage, no Session, no transport vocabulary — those belong to the adapter, and their absence is what keeps the domain honest when you swap providers (transactional-email APIs like Postmark or SES are drop-ins behind this same port; only the adapter and its config change).
                                """),
                        code("sm:mail-port", "java",
                                "guide/domain/thirdparty/MailPort.java — the domain's mail contract: one send, one receipt; transport refusal THROWS, provider answers (queued, rate-limited) come back as values",
                                "server/src/main/java/guide/domain/thirdparty/MailPort.java", """
                                public non-sealed interface MailPort extends IntegrationPort {

                                    // … (javadoc: throws a typed UNAVAILABLE failure when the transport
                                    //  refuses; a returned receipt is a provider ANSWER — deferrals and
                                    //  quota rejections are outcomes, not exceptions)
                                    MailReceipt send(String to, String subject, String body);

                                    record MailReceipt(String outcome, String messageId, String detail) {
                                    }
                                }"""),
                        text("sm:real-adapter", """
                                The REAL adapter is the section's proof that port + adapter is not ceremony: it wraps JavaMailSender, builds a MIME message, sends, and maps transport trouble to the app's typed UNAVAILABLE failure — the one-envelope rule surviving an outbound integration. Nothing upstream knows which adapter answered; swap the fake for this in production and the hub, the tests, and the topic prose all still hold. It is wired into the demo context to prove the drop-in BOOTS — and the demo never calls it, because live is a deploy-time decision (the adapter's posture dial refuses to flip, on purpose):
                                """),
                        code("sm:javamail", "java",
                                "guide/data/thirdparty/JavamailMailAdapter.java — the real one: build the MIME message, send, map MailException to the typed envelope — never swallow",
                                "server/src/main/java/guide/data/thirdparty/JavamailMailAdapter.java", """
                                try {
                                    MimeMessage message = sender.createMimeMessage();
                                    MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
                                    helper.setFrom(from);
                                    helper.setTo(to);
                                    helper.setSubject(subject);
                                    helper.setText(body, false);
                                    sender.send(message);
                                    return new MailReceipt("sent", message.getMessageID(),
                                            "delivered to the configured SMTP relay (posture live)");
                                } catch (MessagingException | MailException failure) {
                                    throw AppException.of(Failure.of(FailureKind.UNAVAILABLE,
                                            "the mail send failed: " + failure.getMessage(),
                                            Map.of("adapter", "javamail", "to", to)));
                                }"""),
                        code("sm:config", "java",
                                "guide/config/ThirdPartyDemoConfig.java — the sender with TIMEOUTS (an integration without timeouts is an outage scheduled for your busiest hour) and the drop-in adapter bean",
                                "server/src/main/java/guide/config/ThirdPartyDemoConfig.java", """
                                @Bean
                                JavaMailSenderImpl thirdPartyMailSender(Environment environment) {
                                    JavaMailSenderImpl sender = new JavaMailSenderImpl();
                                    sender.setHost(environment.getProperty("GUIDE_THIRDPARTY_SMTP_HOST", "localhost"));
                                    sender.setPort(environment.getProperty("GUIDE_THIRDPARTY_SMTP_PORT", Integer.class, 3025));
                                    sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "2000");
                                    sender.getJavaMailProperties().put("mail.smtp.timeout", "5000");
                                    sender.getJavaMailProperties().put("mail.smtp.writetimeout", "5000");
                                    return sender;
                                }

                                // The real adapter behind the same port the fake serves — present, never called.
                                @Bean
                                JavamailMailAdapter javamailMailAdapter(JavaMailSenderImpl thirdPartyMailSender) {
                                    return new JavamailMailAdapter(thirdPartyMailSender, "the-guide@example.com");
                                }"""),
                        text("sm:guards", """
                                Two boundaries complete the section. FIRST, THE FAKE — the demo's default adapter — models the one relay behavior every mail integrator meets: GREYLISTING. In sandbox posture the first send to any NEW recipient is deferred with the classic SMTP 421 answer and the retry delivers; in fake posture everything succeeds immediately. Both are deterministic — a counter and a set, no clock, no socket — which is what makes the demo's curl tour quotable and the tests exact. SECOND, THE BOUNDARY GUARD: addresses and subjects are REJECTED before anything is recorded if they carry CR or LF, because unvalidated input into SMTP headers is header injection — the security topic's injection family wearing a mail costume. The fake enforces it at the boundary, and the real adapter inherits the rule by construction (MimeMessageHelper would reject malformed headers later and worse):
                                """),
                        code("sm:greylist", "java",
                                "guide/data/thirdparty/InMemoryMailAdapter.java — sandbox greylisting (421, retry delivers) and the CR/LF header-injection rejection, both deterministic and offline",
                                "server/src/main/java/guide/data/thirdparty/InMemoryMailAdapter.java", """
                                if (posture == Posture.SANDBOX && seenRecipients.add(normalized(to))) {
                                    return new MailReceipt("queued", messageId,
                                            "sandbox relay: SMTP 421 greylisting — first contact with " + to
                                                    + " deferred, the retry delivers (topic 29's retry story in one answer)");
                                }
                                record(to, subject);
                                return new MailReceipt("sent", messageId,
                                        (posture == Posture.SANDBOX ? "sandbox relay accepted" : "in-process fake accepted")
                                                + " mail to " + to);"""),
                        code("sm:injection", "java",
                                "guide/data/thirdparty/InMemoryMailAdapter.java — the boundary guard: CR/LF never reaches a header",
                                "server/src/main/java/guide/data/thirdparty/InMemoryMailAdapter.java", """
                                if (value.indexOf('\\r') >= 0 || value.indexOf('\\n') >= 0) {
                                    throw AppException.of(Failure.of(FailureKind.VALIDATION,
                                            field + " contains CR/LF — SMTP header injection is rejected at the boundary",
                                            Map.of("parameter", field)));
                                }"""),
                        text("sm:close", """
                                The unit test for the real adapter exercises the send path with a STUBBED JavaMailSender — it is an interface, so the stub answers createMimeMessage and send in memory and no socket ever opens; the test pins that a MailException from the transport surfaces as the typed UNAVAILABLE envelope. That is the discipline this guide will not break for any integration: the REAL adapter is compiled, wired, and tested against a contract double — it is only ever pointed at a real network by a deployment that chose to, with a key that deployment provided.""")),
                section("email-as-job", "Email as a job, recapitulated",
                        "Topic 29 built email-as-job on a port and a recording fake; this topic's MailPort is the same shape grown an outcome view. The recap is the point: the patterns compose.",
                        text("ej:why", """
                                Topic 29 taught the background-jobs half of email: a welcome mail is slow, external, flaky, and non-critical, so it becomes a JOB — enqueued, retried with backoff, dead-lettered when hopeless — and the handler's only dependency was a tiny port, EmailSender, with an in-memory recorder standing in for the relay. This topic OWNS the other half: the send itself, behind Spring Mail. Read together they are one lesson in two acts: the job engine owns WHEN and HOW OFTEN; the integration owns HOW and AGAINST WHAT. The port from topic 29, unchanged:
                                """),
                        code("ej:sender", "java",
                                "guide/domain/jobs/EmailSender.java — topic 29's port: void, throws on transport failure, safe under re-sends — the job engine's dependency",
                                "server/src/main/java/guide/domain/jobs/EmailSender.java", """
                                public interface EmailSender {

                                    // … (javadoc: THROW on transport failure — the attempt's error is born
                                    //  here; tolerate re-sends: a retry after an ambiguous failure may
                                    //  double-send, which is why welcome mails carry no side effects)
                                    void send(String to, String subject, String body);
                                }"""),
                        code("ej:handler", "java",
                                "guide/domain/jobs/WelcomeEmailJobHandler.java — the canonical job: deterministic SMTP-421-shaped failure injection ahead of the port call, then the real send",
                                "server/src/main/java/guide/domain/jobs/WelcomeEmailJobHandler.java", """
                                public void execute(JobRecord job) {
                                    int failedAlready = job.attempts().size();
                                    if (failedAlready < job.failFirstAttempts()) {
                                        throw new IllegalStateException("injected failure #" + (failedAlready + 1)
                                                + " of " + job.failFirstAttempts() + " — SMTP 421, try again later");
                                    }
                                    // … payload read, recipient + name resolved
                                    emailSender.send(to, "Welcome to the guide, " + name,
                                            "Hi " + name + " — your account is ready. (sent by job " + job.id() + ")");
                                }"""),
                        text("ej:retry", """
                                The handler's injected failures ARE the transport's failures, shaped exactly as the sandbox relay models them in this topic (421, try again later) — that is not a coincidence, it is the same 29th-topic insight from the other side: the RETRY semantics and the PROVIDER semantics must be designed together. A relay that greylists means your mail integration MUST be retried; a job queue that retries at-least-once means your mail MUST be idempotent to re-send (the handler's javadoc: a duplicate welcome mail is harmless; a billing mail would need a dedupe key — the messaging topic's consumer discipline). The recorder that made all of this testable without an SMTP account:
                                """),
                        code("ej:recorder", "java",
                                "guide/data/jobs/RecordingEmailSender.java — topic 29's fake: records what landed, bounded, never fails — and its javadoc points HERE, to the drop-in real adapter",
                                "server/src/main/java/guide/data/jobs/RecordingEmailSender.java", """
                                @Override
                                public synchronized void send(String to, String subject, String body) {
                                    sent.addFirst(new SentEmail(to, subject, body, clock.instant()));
                                    while (sent.size() > MAX_RECORDED) {
                                        sent.removeLast();
                                    }
                                }"""),
                        text("ej:close", """
                                So why did topic 43 grow a SECOND mail port (MailPort) instead of reusing EmailSender? Because the two consumers want different contracts, and the difference is the lesson. The JOB ENGINE wants fire-and-forget: void, throw on failure, the queue owns the outcome. The HUB wants the provider's ANSWER: outcome, message id, deferral detail — an operational view of the last call. In production both adapters wrap the same JavaMailSender; in tests both are fakes; and the seam between them is exactly where your domain's vocabulary lives. When you find yourself wanting one port to serve both shapes, split the port — a port per consumer-question is cheaper than a port that answers questions nobody asked.""")),
                section("stripe", "Payments: Stripe as the archetype",
                        "The highest-stakes integration, and the one whose failure taxonomy is most often botched: a decline is an ANSWER, not an exception. The fake implements the provider's documented test vectors; the real API shapes stay in prose.",
                        text("st:stakes", """
                                PAYMENTS concentrates every third-party hazard into one integration: money moves (a bug is a financial event, not a log line), the provider's API is the PRODUCT (Stripe's shapes — PaymentIntent, idempotency keys, test mode — are what the whole ecosystem learned from), and the failure taxonomy is subtle in a way that loses people money. The subtlety: A DECLINE IS AN ANSWER. When the provider says card_declined, it answered the question you asked (can this card be charged? no — here is why); your checkout should show a message, not page your on-call. When the provider is UNREACHABLE, THAT is an incident — and a different code path. Collapsing the two (declines thrown as exceptions, or outages swallowed as declines) is the classic payments bug, and the port's shape makes the split mechanical:
                                """),
                        code("st:port", "java",
                                "guide/domain/thirdparty/PaymentPort.java — declines are VALUES on receipts; only transport trouble throws — two failure families that must never share an exception",
                                "server/src/main/java/guide/domain/thirdparty/PaymentPort.java", """
                                public non-sealed interface PaymentPort extends IntegrationPort {

                                    ChargeReceipt charge(String token, long amountCents, String currency, String description);

                                    record ChargeReceipt(String outcome, String chargeId, String declineCode,
                                            boolean testMode, String detail) {
                                    }
                                }"""),
                        text("st:semantics", """
                                Around that port sit four production facts this guide asserts in prose because they are provider SHAPE, not repo code — and the rule from the topic's invariant applies: API shapes that are not this repo's files never ride in fake-sourced blocks. IDEMPOTENCY KEYS: every state-changing payment call carries a key the provider dedupes on, so a retried request cannot double-charge — the same at-least-once discipline the jobs and messaging topics built internally, provided by the provider here; your job is to generate the key ONCE per user intent and reuse it across retries. WEBHOOKS ARE THE SOURCE OF TRUTH: the synchronous charge answer tells you what the provider believes NOW; the webhook (topic 14 built the receiver, HMAC signature and all) tells you what became TRUE — asynchronous success, later failure, disputes — so your ledger subscribes, verifies, and reconciles. NEVER TOUCH CARD DATA: tokens and the provider's hosted elements keep your systems out of PCI scope; the moment a PAN crosses your code, you have bought an audit. And TEST MODE is a real environment with real shapes: the fake below implements the DOCUMENTED test vectors, so the paths your CI exercises are the paths production runs.
                                """),
                        code("st:fake", "java",
                                "guide/data/thirdparty/FakeStripeAdapter.java — the provider's documented test vectors as a deterministic switch: success, two declines, unknown-token rejection",
                                "server/src/main/java/guide/data/thirdparty/FakeStripeAdapter.java", """
                                return switch (token) {
                                    case TOKEN_SUCCEEDS -> new ChargeReceipt("succeeded", chargeId, null, testMode,
                                            "charged " + amountCents + " " + currency + " (" + description + ") — " + world);
                                    case TOKEN_DECLINED -> new ChargeReceipt("declined", chargeId, "card_declined",
                                            testMode, "the provider declined the card — " + world);
                                    // … tok_insufficientFunds declines the same way
                                    default -> new ChargeReceipt("declined", chargeId, "invalid_token", testMode,
                                            "unknown token '" + token + "' — a real provider rejects unknown tokens "
                                                    + "the same way; try " + TOKEN_SUCCEEDS + ", " + TOKEN_DECLINED
                                                    + " or " + TOKEN_INSUFFICIENT);
                                };"""),
                        text("st:close", """
                                In the hub demo, charge with tok_visa and the receipt says succeeded; switch the token to tok_chargeDeclined and the receipt comes back declined/card_declined on an HTTP 200 — because the operation RAN and the provider ANSWERED; flip the posture to sandbox and testMode flips true with the world note changing from "in-process fake" to "stripe test mode". Only a bad amount or a malformed currency reaches the typed 400 envelope, because only then did the CALLER err. A real adapter behind this port would speak PaymentIntents over HTTPS with an idempotency key and a key from the environment — and it would be tested exactly like the mail adapter: contract stubs, typed failures, zero sockets.""")),
                section("cloud-storage", "Cloud object storage",
                        "S3-shaped storage: flat keys, opaque etags, provider ceilings — and the quiet failure modes (traversal-shaped keys, unbounded stores) the fake models with the discipline the real one deserves.",
                        text("cs:shape", """
                                OBJECT STORAGE is the integration whose failure modes are the QUIETEST. Mail fails loudly (a bounce arrives), payments fail expensively (a customer complains), but a storage integration degrades silently: puts start throttling, lifecycle rules nobody remembers delete what nobody backed up, and a bucket outlives three generations of the app that created it. The S3 SHAPE — flat keyspace, PUT returns an opaque etag, GET by key, size ceilings per object and per request — became the lingua franca (S3, GCS's S3 compatibility, MinIO, R2), which makes it the right shape to teach: the port is small, and everything interesting is in the discipline around it. This guide's port is deliberately minimal — the hub exercises put; presigned URLs, multipart uploads, and lifecycle rules are provider vocabulary this domain has no question for, so they are absent (YAGNI is an architecture tool too):
                                """),
                        code("cs:port", "java",
                                "guide/domain/thirdparty/StoragePort.java — the smallest surface that answers the domain's question: put one keyed object, get the provider's etag back",
                                "server/src/main/java/guide/domain/thirdparty/StoragePort.java", """
                                public non-sealed interface StoragePort extends IntegrationPort {

                                    StoredReceipt put(String key, int sizeBytes);

                                    record StoredReceipt(String outcome, String key, String etag, int bytes, String detail) {
                                    }
                                }"""),
                        text("cs:discipline", """
                                The fake adapter enforces three disciplines the real provider deserves. KEY VALIDATION: keys are lowercase provider-shaped paths (guide/hello.txt), and segments named .. or . are rejected AT THE BOUNDARY — the path-traversal guard topic 26 taught for local files, applied to provider keys, because a key like ../../secrets must die in your code, not inside an SDK you cannot debug. DETERMINISTIC ETAGS: the provider answers a put with an opaque etag; the fake's etag is the first 16 hex characters of SHA-256 over key and size — opaque-looking, reproducible, and pinnable in tests (the terminal tour shows the real value). And a BOUNDED STORE: 64 objects, oldest evicted — the scaling topic's bounded-everything rule applied to demo state, because unbounded in-process state is just a slower OutOfMemoryError. The sandbox posture adds the last discipline, a provider ceiling:
                                """),
                        code("cs:ceiling", "java",
                                "guide/data/thirdparty/InMemoryObjectStore.java — the sandbox provider's EntityTooLarge as a FAILED RECEIPT (the provider's answer, not an exception), then the deterministic etag",
                                "server/src/main/java/guide/data/thirdparty/InMemoryObjectStore.java", """
                                if (posture == Posture.SANDBOX && sizeBytes > SANDBOX_MAX_BYTES) {
                                    return new StoredReceipt("failed", key, null, 0,
                                            "EntityTooLarge — the sandbox provider caps objects at " + SANDBOX_MAX_BYTES
                                                    + " bytes (your put was " + sizeBytes + ")");
                                }
                                String etag = digest(key, sizeBytes);"""),
                        code("cs:traversal", "java",
                                "guide/data/thirdparty/InMemoryObjectStore.java — traversal-shaped keys rejected at the boundary, typed 400, never reaching an SDK",
                                "server/src/main/java/guide/data/thirdparty/InMemoryObjectStore.java", """
                                for (String segment : key.split("/")) {
                                    if (segment.equals("..") || segment.equals(".")) {
                                        throw AppException.of(Failure.of(FailureKind.VALIDATION,
                                                "key segment '" + segment + "' is a path traversal — rejected at the boundary",
                                                Map.of("parameter", "key")));
                                    }
                                }"""),
                        text("cs:close", """
                                What production adds, in prose because it is provider behavior rather than repo code: STREAMING for large objects (the files topic's GuardedUploadStream hash-in-the-same-pass discipline maps directly onto a streamed put — never buffer what you can pipe), PRESIGNED URLS for browser-direct upload and download (the provider does the transfer; your API hands out a capability with an expiry — which makes the URL itself a secret with a blast radius, so log the fact, never the token), and STORAGE CLASSES + lifecycle rules as a cost decision (the ops topic's capacity planning, one layer down). The fake holds none of that — it holds exactly enough to teach the boundary: validated keys in, etags and provider answers out, everything bounded.""")),
                section("keys-quotas-blast-radius", "Keys, quotas, and blast radius",
                        "The operational trio that makes integrations survivable: keys as verdicts (never values), quotas as per-integration state with a dial, and blast radius bounded by construction — all visible in the hub.",
                        text("kq:keys", """
                                KEYS FIRST, because key handling is where integrating apps bleed. The rules this app enforces mechanically: credentials arrive from the ENVIRONMENT (twelve-factor, topic 36), never from code, never from a properties file that ships in the jar; DEMO keys are loudly fake BY CONSTRUCTION — this guide's fakes need no credential at all, so there is nothing to leak, and the loudest possible fake key is the one that does not exist; and operational surfaces report VERDICTS, never values. That last rule is topic 36's redaction discipline applied outward: the on-call question is "does production have the key set?" — a boolean — and no legitimate question is answered by the string. The environments topic built exactly this for configuration:
                                """),
                        code("kq:redaction", "java",
                                "guide/domain/environments/InspectEnvironment.java — topic 36's pattern: the secret key's value has NO code path into the answer; only a classification and a boolean travel",
                                "server/src/main/java/guide/domain/environments/InspectEnvironment.java", """
                                public static final String REDACTED = "<redacted>";
                                // …
                                new EnvInspection.PropertyLine("guide.env.demo-api-key", REDACTED, SECRET));"""),
                        text("kq:key-verdicts", """
                                This topic's keys record does the same with less machinery: the composition root reads the Spring Environment ONCE at boot and captures, per integration, whether the env var is present; the domain holds booleans; the API can therefore only ever say is-set or is-not-set. Note what the verdict text teaches even in its negative branch — the fake needs no credential, so an unset key in this app is not a misconfiguration, it is the SAFE state; in a deployment that wired a LIVE adapter, the same verdict flips to present and the value STILL never crosses the API, because the live adapter reads it at boot, and nothing that serves requests ever held it as a string:
                                """),
                        code("kq:keys-code", "java",
                                "guide/domain/thirdparty/ThirdPartyKeys.java — the verdict builder: present or not, with the value having no code path into any answer",
                                "server/src/main/java/guide/domain/thirdparty/ThirdPartyKeys.java", """
                                public String verdict() {
                                    return presentFromEnv
                                            ? envVar + " is set — present, and its value has NO code path into any answer"
                                            : envVar + " is not set — nothing to leak: the in-process fake needs no credential";
                                }"""),
                        code("kq:keys-config", "java",
                                "guide/config/ThirdPartyDemoConfig.java — the composition root reads the Environment once; booleans travel, strings do not",
                                "server/src/main/java/guide/config/ThirdPartyDemoConfig.java", """
                                @Bean
                                ThirdPartyKeys thirdPartyKeys(Environment environment) {
                                    return ThirdPartyKeys.of(
                                            new ThirdPartyKeys.KeyState("mail", MAIL_KEY_VAR,
                                                    environment.getProperty(MAIL_KEY_VAR) != null),
                                            // … payments + storage read the same way: PRESENT-OR-NOT, never the value
                                            new ThirdPartyKeys.KeyState("storage", STORAGE_KEY_VAR,
                                                    environment.getProperty(STORAGE_KEY_VAR) != null));
                                }"""),
                        text("kq:quotas", """
                                QUOTAS are the second discipline, and the hub makes them first-class state: every integration carries a call budget, a used count, and a quota state (healthy / exhausted), and the budget is DIALABLE at runtime — the demo's whole blast-radius exhibit in one endpoint. The gate runs BEFORE the provider call: when the budget is spent, the answer is rate_limited with the provider call never made, which is the honest shape of a provider 429 — the provider's meter filled up, and hammering it further is both futile and billable. Deterministic by construction: no clock, no token bucket, just a count against a limit you set — the security topic's Bucket4j is the production-grade shape of the same idea; here the lesson is the STATE, not the algorithm:
                                """),
                        code("kq:gate", "java",
                                "guide/domain/thirdparty/ThirdPartyHub.java — the quota gate ahead of every port call: rate_limited is an outcome, the provider is never invoked past the gate",
                                "server/src/main/java/guide/domain/thirdparty/ThirdPartyHub.java", """
                                public synchronized MailAnswer sendMail(String to, String subject, String body) {
                                    MailPort port = (MailPort) ports.get("mail");
                                    if (gateExhausted("mail")) {
                                        return new MailAnswer("rate_limited", null,
                                                "quota gate: " + callsUsed.get("mail") + "/" + quotaLimits.get("mail")
                                                        + " calls used — the provider call was never made",
                                                view("mail"));
                                    }
                                    // … the port call, then outcome recording
                                }"""),
                        code("kq:gate-inner", "java",
                                "guide/domain/thirdparty/ThirdPartyHub.java — the gate itself: per-integration state, nothing shared — that isolation IS the blast-radius lesson",
                                "server/src/main/java/guide/domain/thirdparty/ThirdPartyHub.java", """
                                private boolean gateExhausted(String id) {
                                    return callsUsed.get(id) >= quotaLimits.get(id);
                                }"""),
                        text("kq:blast", """
                                BLAST RADIUS is the trio's payoff and the topic's thesis. Degradation is inevitable — providers throttle, keys expire, sandboxes greylist — so the design question is how far the degradation TRAVELS. The hub's answer is structural: quota, counters, outcomes, and posture are PER-INTEGRATION maps, not globals; the gate consults one row; no integration's state is read on another's path. Dial mail's quota to 1, exhaust it, and payments answers succeeded on the very next request — not because the code checks "is mail degraded?" anywhere, but because there is no path from mail's state to payments' answer. That is containment by construction, and it composes with everything the platform arc built: the reliability topic's bulkheads are the same idea for THREADS and CONNECTIONS, the scaling topic's statelessness is the same idea for INSTANCES, and per-integration quota state is the same idea for PROVIDERS. The dial, as the controller serves it:
                                """),
                        code("kq:dial", "java",
                                "guide/presentation/demo/ThirdPartyDemoController.java — the quota dial: validate at the edge, one hub call — and the answer row proves the change",
                                "server/src/main/java/guide/presentation/demo/ThirdPartyDemoController.java", """
                                @PostMapping(value = "/api/demo/thirdparty/quota", produces = MediaType.APPLICATION_JSON_VALUE)
                                ThirdPartyHub.DialAnswer quota(
                                        @RequestParam(name = "integration") String integration,
                                        @RequestParam(name = "limit") int limit) {
                                    requireKnownIntegration(integration);
                                    requireInRange(limit, ThirdPartyHub.MIN_QUOTA, ThirdPartyHub.MAX_QUOTA, "limit");
                                    return hub.setQuota(integration, limit);
                                }"""),
                        code("kq:client", "ts",
                                "client/src/ui/demos/thirdparty/hub_demo.tsx — the client half of the dial: one POST, the answered row patches the card, the flash line carries the receipt",
                                "client/src/ui/demos/thirdparty/hub_demo.tsx", """
                                void safeRepositoryCall(async () =>
                                  parseDial(await requestJson(
                                    config,
                                    `/demo/thirdparty/quota?integration=${dialTarget}&limit=${dialLimit}`,
                                    { method: 'POST' },
                                  )))"""),
                        text("kq:close", """
                                One production note to keep the demo honest: per-integration quota state in MEMORY is a teaching shape — a real fleet keeps the meter where the calls land (the provider's own quota dashboard, a shared store like the scaling topic's ExternalCounterStore, or an API gateway) because instance-local counters undercount exactly when you have enough traffic to matter. What survives unchanged is the INVARIANT: the meter is per-integration, the gate is before the call, and the degradation of one provider is a fact about ONE row of your integration map — visible, bounded, and nobody else's problem.""")),
                section("terminal-companion", "Terminal companion",
                        "The topic curled: drive all three integrations from curl, exhaust one quota while its siblings keep answering, flip a posture, and read the key verdicts — every number deterministic, nothing leaving the process.",
                        text("tc:intro", """
                                Everything lives on one thin controller — parse and validate at the edge, one hub call per endpoint, provider answers on 200s, typed 400 envelopes for bad input. The mail trigger, representative of the shape:
                                """),
                        code("tc:controller", "java",
                                "guide/presentation/demo/ThirdPartyDemoController.java — the mail trigger: defaults on the parameters, one hub call, the receipt plus the updated row in the answer",
                                "server/src/main/java/guide/presentation/demo/ThirdPartyDemoController.java", """
                                @PostMapping(value = "/api/demo/thirdparty/mail", produces = MediaType.APPLICATION_JSON_VALUE)
                                ThirdPartyHub.MailAnswer mail(
                                        @RequestParam(name = "to", defaultValue = "friend@example.com") String to,
                                        @RequestParam(name = "subject", defaultValue = "Welcome to the guide") String subject) {
                                    return hub.sendMail(to, subject, "Hi — this mail never left the process (the fake is the lesson).");
                                }"""),
                        text("tc:tour", """
                                The tour, end to end — report, one send per integration, the greylisting story, the decline story, then the blast-radius exhibit and reset:
                                """),
                        code("tc:curl", "bash",
                                "the third-party tour: every outcome deterministic, no network at any step",
                                null, """
                                # the hub: three rows (posture, last outcome, quota, key verdict) + reading
                                curl -s localhost:8080/api/demo/thirdparty/hub | python3 -m json.tool | head -18
                                # "id": "mail", "posture": "fake", "lastOutcome": "idle",
                                # "callsUsed": 0, "quotaLimit": 8, "quotaState": "healthy",
                                # "keyVerdict": "GUIDE_THIRDPARTY_MAIL_KEY is not set — nothing to
                                #  leak: the in-process fake needs no credential"

                                # run each integration through its fake — provider answers on 200s
                                curl -s -X POST 'localhost:8080/api/demo/thirdparty/mail?to=friend@example.com' | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["outcome"], r["messageId"])'
                                # sent demo-mail-1
                                curl -s -X POST 'localhost:8080/api/demo/thirdparty/payments?token=tok_chargeDeclined&amountCents=1999' | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["outcome"], r["declineCode"], "testMode:", r["testMode"])'
                                # declined card_declined testMode: False
                                curl -s -X POST 'localhost:8080/api/demo/thirdparty/storage?key=guide/hello.txt&sizeBytes=2048' | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["outcome"], r["etag"])'
                                # stored 4261cbab88fc4db5

                                # sandbox posture: the relay starts greylisting first contacts (421, retry delivers)
                                curl -s -X POST 'localhost:8080/api/demo/thirdparty/posture?integration=mail&posture=sandbox' > /dev/null
                                curl -s -X POST 'localhost:8080/api/demo/thirdparty/mail?to=newfriend@example.com' | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["outcome"])'
                                # queued
                                curl -s -X POST 'localhost:8080/api/demo/thirdparty/mail?to=newfriend@example.com' | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["outcome"])'
                                # sent

                                # blast radius: dial mail's quota to 1 (already used), send again —
                                # the gate answers rate_limited with the provider never called…
                                curl -s -X POST 'localhost:8080/api/demo/thirdparty/quota?integration=mail&limit=1' > /dev/null
                                curl -s -X POST 'localhost:8080/api/demo/thirdparty/mail' | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["outcome"], "|", r["after"]["quotaState"])'
                                # rate_limited | exhausted
                                # …while the sibling keeps answering — containment, observed
                                curl -s -X POST 'localhost:8080/api/demo/thirdparty/payments?token=tok_visa' | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["outcome"], "|", r["after"]["quotaState"])'
                                # succeeded | healthy

                                # back to constructed state (quota, counters, postures, adapters)
                                curl -s -X POST localhost:8080/api/demo/thirdparty/reset > /dev/null

                                # and the input the edge rejects: typed 400 envelope, never a 500
                                # (a traversal-shaped segment — and a shape-bad key fails the same way)
                                curl -s -X POST 'localhost:8080/api/demo/thirdparty/storage?key=guide/../etc/passwd' | python3 -c 'import json,sys; print(json.load(sys.stdin)["error"]["code"])'
                                # validation_failed"""),
                        text("tc:close", """
                                The reset at the end is not just tour hygiene — it is the determinism contract: every endpoint's answer depends only on the calls you made since reset, which is what the MockMvc tests pin and what makes the tour's comments true for anyone who runs it. That contract — deterministic, offline, provider-shaped — is the topic's parting gift: keep the fakes this honest and going live becomes a deployment decision you make calmly, not a debugging session you survive.""")));
    }
}
