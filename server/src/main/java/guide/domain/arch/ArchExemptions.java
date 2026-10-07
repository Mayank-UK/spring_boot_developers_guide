package guide.domain.arch;

import java.util.List;

/**
 * The guide's DEPENDENCY-RULE EXEMPTION LEDGER — every place the source tree
 * deliberately bends the architecture rules of topic 32, with the reason.
 * This is the single source of truth for three consumers:
 *
 * 1. guide.arch.DependencyRuleTest scans src/main/java and asserts the real
 *    tree bends NOWHERE ELSE — reality must equal this ledger exactly, in
 *    both directions (every bend ledgered; no stale ledger entry).
 * 2. GET /api/demo/arch/map serves this ledger beside the layer counts, so
 *    the map endpoint can never drift from what the test enforces.
 * 3. The topic's page quotes it: an exemption that cannot state its reason
 *    is not an exemption, it is an unreviewed violation.
 *
 * The ledger is short because the rule held: across ~500 source files the
 * domain imports no data or presentation type and no web/persistence
 * machinery, and only six presentation files reach into data/ — each a
 * teaching exhibit with its reason recorded here.
 */
public final class ArchExemptions {

    private ArchExemptions() {
    }

    /**
     * One domain file plus the import prefix it is allowed to pull from
     * outside the JDK and guide.core/guide.domain. Files are paths under
     * src/main/java; prefixes are dotted Java package prefixes.
     */
    public record DomainExemption(String file, String importPrefix, String reason) {
    }

    /**
     * One presentation file allowed to import from guide.data directly,
     * plus the prefix it may import — the invariant's documented
     * "teaching-exhibit controllers" carve-out.
     */
    public record TeachingExhibit(String file, String importPrefix, String reason) {
    }

    /**
     * The domain's outside-import ledger. Shape: the domain may freely use
     * the JDK and guide.core (the shared kernel it sits inside of); every
     * OTHER import must appear here. Three families cover all of it:
     * serialization annotations on records (topic 10's annotated-record
     * exception, generalized), Bean Validation (the validation vocabulary
     * IS the domain contract there), and library-as-port / teaching
     * specimens where the imported type is the subject being taught.
     */
    public static List<DomainExemption> domainLedger() {
        return List.of(
                // -- serialization annotations on domain types (topic 10's
                //    annotated-record exception: @JsonValue/@JsonInclude are
                //    inert metadata; they change JSON rendering, never behavior)
                new DomainExemption("guide/domain/posts/PostStatus.java",
                        "com.fasterxml.jackson.annotation.",
                        "enum serialized by name — @JsonValue is rendering metadata, not behavior"),
                new DomainExemption("guide/domain/sqldemo/LabQueryName.java",
                        "com.fasterxml.jackson.annotation.",
                        "enum serialized by name — same annotated-record exception"),
                new DomainExemption("guide/domain/sqldemo/RaceStrategy.java",
                        "com.fasterxml.jackson.annotation.",
                        "enum serialized by name — same annotated-record exception"),
                new DomainExemption("guide/domain/content/TopicList.java",
                        "com.fasterxml.jackson.annotation.",
                        "wire record opts fields out via @JsonInclude — rendering metadata"),
                new DomainExemption("guide/domain/content/TopicDetail.java",
                        "com.fasterxml.jackson.annotation.",
                        "wire record opts fields out via @JsonInclude — rendering metadata"),
                new DomainExemption("guide/domain/validation/ValidationArena.java",
                        "com.fasterxml.jackson.annotation.",
                        "annotated-record exception — @JsonInclude on a wire record"),
                // -- the content domain area wraps the registry's records
                new DomainExemption("guide/domain/content/ContentRepository.java",
                        "guide.content.",
                        "the port speaks the content registry's record vocabulary — this area wraps that registry"),
                new DomainExemption("guide/domain/content/TopicList.java",
                        "guide.content.",
                        "summaries are mapped from the registry's records"),
                new DomainExemption("guide/domain/content/TopicDetail.java",
                        "guide.content.",
                        "detail is mapped from the registry's records"),
                new DomainExemption("guide/domain/java/RuntimeTypesExhibit.java",
                        "guide.content.",
                        "teaching exhibit: topic 01 reflects over a real TextBlock record"),
                // -- Bean Validation: the vocabulary IS the contract (topic 10)
                new DomainExemption("guide/domain/validation/ArenaSubmission.java",
                        "jakarta.validation.constraints.",
                        "topic 10's annotated record — constraints are the domain contract here"),
                new DomainExemption("guide/domain/validation/StrongPassword.java",
                        "jakarta.validation.",
                        "custom constraint — a Bean Validation extension point by definition"),
                new DomainExemption("guide/domain/validation/DateRange.java",
                        "jakarta.validation.",
                        "class-level constraint — same extension point"),
                new DomainExemption("guide/domain/validation/ValidationArena.java",
                        "jakarta.validation.",
                        "the arena runs the Validator and reports ConstraintViolations"),
                // -- security specimens: framework interfaces as ports
                new DomainExemption("guide/domain/auth/AuthenticateCredentials.java",
                        "org.springframework.security.crypto.password.",
                        "Spring's PasswordEncoder used AS the port — an infra-agnostic interface"),
                new DomainExemption("guide/domain/auth/PasswordHashLab.java",
                        "org.springframework.security.crypto.",
                        "teaching specimen: the bcrypt cost lab IS about the encoder"),
                new DomainExemption("guide/domain/auth/JwtMinter.java",
                        "com.nimbusds.",
                        "JWT signing — the operation is the library; wrapped behind this class"),
                new DomainExemption("guide/domain/authz/AuthzPermissions.java",
                        "org.springframework.security.core.",
                        "Authentication/UserDetails — Spring Security's own vocabulary, treated as the port"),
                new DomainExemption("guide/domain/authz/SelfInvocationSpecimen.java",
                        "org.springframework.security.access.prepost.",
                        "teaching specimen: @PreAuthorize is what the trap demo exhibits"),
                new DomainExemption("guide/domain/caching/AnnotatedTopicStats.java",
                        "org.springframework.cache.annotation.",
                        "teaching specimen: @Cacheable/@CacheEvict semantics ARE the lesson"),
                // -- scheduled / graphql machinery kept behind one class each
                new DomainExemption("guide/domain/realtime/TickerPump.java",
                        "org.springframework.scheduling.annotation.",
                        "@Scheduled pump — the framework's time trigger, quarantined to one class"),
                new DomainExemption("guide/domain/graphql/ExecuteGraphqlQuery.java",
                        "org.springframework.graphql.",
                        "the GraphQL engine's execution interface is the port this use case drives"),
                // -- gRPC: generated stub vocabulary and server interceptors
                new DomainExemption("guide/domain/grpc/InvokeGuideDemo.java",
                        "guide.demo.grpc.",
                        "generated protobuf stubs — the area's calling vocabulary"),
                new DomainExemption("guide/domain/grpc/InvokeGuideDemo.java",
                        "io.grpc.",
                        "Status/StreamObserver — the RPC runtime's typed surface"),
                new DomainExemption("guide/domain/grpc/DescribeGuideDemoService.java",
                        "guide.demo.grpc.",
                        "generated service descriptor — same vocabulary"),
                new DomainExemption("guide/domain/grpc/DescribeGuideDemoService.java",
                        "io.grpc.",
                        "MethodDescriptor/ServiceDescriptor — the runtime's reflection surface"),
                new DomainExemption("guide/domain/security/GrpcPostureInterceptor.java",
                        "io.grpc.",
                        "a gRPC ServerInterceptor by definition — the deliberate posture of topic 25"),
                // -- libraries whose operation is the use case's subject
                new DomainExemption("guide/domain/security/RateLimitService.java",
                        "io.github.bucket4j.",
                        "the token-bucket library IS the rate-limiting lesson (topic 25)"),
                new DomainExemption("guide/domain/files/CreateThumbnail.java",
                        "net.coobird.thumbnailator.",
                        "image resizing — the library is the capability (topic 26)"),
                // -- reactor: the pipelines ARE the lesson (topic 41)
                new DomainExemption("guide/domain/reactive/CompareExecutionModels.java",
                        "reactor.core.",
                        "Reactor's Flux/Mono are the subject being taught — the side-by-side engine's pipeline half (topic 41)"),
                new DomainExemption("guide/domain/reactive/DemonstrateBackpressure.java",
                        "reactor.core.",
                        "backpressure operators ARE the lesson — the drop/buffer choreography (topic 41)"),
                new DomainExemption("guide/domain/reactive/DemonstrateBackpressure.java",
                        "org.reactivestreams.",
                        "the reactive-streams SPI (Subscription.request) — backpressure IS the demand protocol this exhibit teaches"),
                // -- logging and JSON: infra-agnostic APIs
                new DomainExemption("guide/domain/errors/UncaughtFailureLogger.java",
                        "org.slf4j.",
                        "the SLF4J API — logging vocabulary, no implementation imported"),
                new DomainExemption("guide/domain/obs/EmitDemoLog.java",
                        "org.slf4j.",
                        "topic 33's logging lesson — the SLF4J API is the subject being taught"),
                new DomainExemption("guide/domain/obs/RunTracedHop.java",
                        "org.slf4j.",
                        "topic 33's traced hop logs through the SLF4J API"),
                new DomainExemption("guide/domain/obs/ObsMetrics.java",
                        "io.micrometer.core.instrument.",
                        "topic 33's metrics lesson — Micrometer's types are the subject being taught"),
                new DomainExemption("guide/domain/jobs/EnqueueJob.java",
                        "tools.jackson.databind.",
                        "JSON payload shaping — the databind API is treated as infra-agnostic"),
                new DomainExemption("guide/domain/jobs/WelcomeEmailJobHandler.java",
                        "tools.jackson.databind.",
                        "reads the job payload's JsonNode tree"),
                new DomainExemption("guide/domain/webhooks/FireWebhookEvent.java",
                        "tools.jackson.databind.",
                        "builds the webhook's JSON body"),
                new DomainExemption("guide/domain/webhooks/ReceiveWebhookDelivery.java",
                        "tools.jackson.databind.",
                        "parses the delivered body for the HMAC check"));
    }

    /**
     * The presentation files allowed to import guide.data directly — the
     * invariant's documented carve-out for teaching exhibits. Six, each
     * reading demo-lifecycle state or recorded evidence that production
     * code would never need through a port.
     */
    public static List<TeachingExhibit> teachingExhibits() {
        return List.of(
                new TeachingExhibit("guide/presentation/demo/OutboxDemoController.java",
                        "guide.data.messaging.",
                        "demo console drives MsgDemoActivation — a substrate lifecycle handle, not a repository"),
                new TeachingExhibit("guide/presentation/demo/StreamDemoController.java",
                        "guide.data.messaging.",
                        "same activation handle for the stream console"),
                new TeachingExhibit("guide/presentation/demo/MongoDemoController.java",
                        "guide.data.mongo.",
                        "reads the scenario router's AREA constant to display fake/live mode"),
                new TeachingExhibit("guide/presentation/demo/CachingDemoController.java",
                        "guide.data.caching.",
                        "reads the scenario router's AREA constant to display backend mode"),
                new TeachingExhibit("guide/presentation/demo/JobsDemoController.java",
                        "guide.data.jobs.",
                        "the documented teaching-exception: console reads the recorded-mail view straight from the fake"),
                new TeachingExhibit("guide/presentation/graphql/PostsGraphqlController.java",
                        "guide.data.comments.",
                        "reads the fake repository's batchCallCount — the N+1 proof counter exists only on the fake"),
                new TeachingExhibit("guide/presentation/demo/ObsDemoController.java",
                        "guide.data.obs.",
                        "topic 33's console reads the recorded Prometheus excerpt straight from the collector"));
    }
}
