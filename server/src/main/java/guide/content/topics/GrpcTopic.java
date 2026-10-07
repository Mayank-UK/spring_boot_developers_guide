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
 * Topic 15 — gRPC. Group apis, order 5. The contract-first RPC world: what
 * protobuf over HTTP/2 buys and costs against REST+JSON, our real
 * guide_demo.proto dissected (field numbers as wire identity), the four
 * interaction kinds as live specimens served from the real content registry
 * by spring-grpc, the client-side stubs and channels (blocking, async,
 * future), Status codes and deadlines that provably fire, and the codegen
 * pipeline that turns one .proto into server bases, client stubs, and
 * contracts for a dozen languages.
 */
public final class GrpcTopic {

    private GrpcTopic() {
    }

    public static Topic topic() {
        return new Topic("grpc", "gRPC",
                "Contracts on HTTP/2: one .proto file compiles into a typed server, "
                        + "typed clients, and a wire format ten-ish years of engineers "
                        + "have agreed not to renegotiate — unary calls, three kinds of "
                        + "streaming, Status codes, and deadlines that actually fire.",
                TopicGroupId.APIS, 5,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("what-grpc-is", "What gRPC is",
                        "A binary RPC protocol over HTTP/2 with the contract written first — versus REST's resource-shaped JSON over HTTP/1.1.",
                        text("is:definition", """
                                gRPC is REMOTE PROCEDURE CALL with the modern stack underneath: your function call becomes an HTTP/2 request whose body is a compact PROTOBUF-encoded message, and whose response is the same. The word that does the work is CONTRACT. A gRPC service is declared in a .proto file — messages with numbered fields, services with named methods — and that file is the single source of truth from which everything else is GENERATED: the server-side base class you extend, the client stubs you call, the serializers on both ends, and equivalent artifacts for Java, Go, Python, Rust, and a dozen other languages. You do not write a gRPC client for your gRPC service; you compile it.

                                Under the hood, HTTP/2 is what makes gRPC feel nothing like the request/response web you have met so far in this guide. HTTP/2 multiplexes many concurrent streams over one TCP connection (binary frames, not text lines), so a long-running server stream does not block the unary call behind it; it has first-class METADATA (headers/trailers) rather than the header soup of HTTP/1.1; and both sides can push data when ready instead of the client always asking. REST can absolutely run over HTTP/2 too — but HTTP/2's streaming nature maps so naturally onto rpc-with-streams that the two technologies effectively grew up together."""),
                        text("is:versus-rest", """
                                The honest comparison, because this guide's own content API IS REST+JSON and stays that way. REST's strengths: ubiquity (every browser, curl, and load balancer speaks it with zero setup), human readability (you can read a JSON body in a terminal), uniform verbs and status codes, and a giant ecosystem of gateways, proxies, and monitoring that understand it. gRPC's strengths: a STRICT, typed, evolving contract — add a field and old clients keep working, because the wire format is defined by field NUMBERS, not names; smaller payloads and faster serialization than JSON; code generation in every mainstream language from one file; and the four streaming interaction kinds, which plain REST simply does not have (you bolt on SSE or websockets — topic 28 — and now you have two half-protocols).

                                Where each wins, in practice. PUBLIC APIs are usually REST (or GraphQL — topic 13): your consumers are strangers with a curl, documentation is browsable, and browser fetch() speaks HTTP/1.1 JSON natively. INTERNAL service-to-service traffic is where gRPC dominates: both ends are yours, so codegen is pure profit; the contract kills a whole class of "the JSON changed and nobody noticed" incidents; streaming between services (logs, metrics, chat fan-out) is native; and cross-language teams share one .proto instead of three hand-written clients. gRPC is also the native dialect of many platforms — Kubernetes-sidecars, Envoy/xDS, and most service meshes speak it natively."""),
                        text("is:proto-first", """
                                The workflow inversion is the cultural shock: PROTO-FIRST, not code-first. You design the contract in a small interface-definition language — messages and services, minutes to learn — and both server and client are derived from it. There is no "annotate my Java class and hope the JSON comes out shaped right"; there is a file that says exactly what crosses the wire, reviewed as a contract, versioned as a contract, breaking-change-audited as a contract. When the api-design topic warns that a REST API is a promise you cannot take back, gRPC answers with machinery: the same promise, but written in a language whose compiler enforces it.

                                What you give up: readability of the wire (binary frames — you debug with tooling, not eyeballs), browser support without a translation proxy (grpc-web), and some ecosystem friction (load balancers must understand HTTP/2; curl cannot just call it — grpcurl can). This topic builds the whole stack for real: the contract file below, the generated Java, a spring-grpc server serving the REAL content registry, and a client console that calls it through all four interaction kinds — with deadlines that miss on purpose.""")),
                section("the-idiom", "The idiom: our .proto, dissected",
                        "Package, services, messages — and field numbers, the wire identity you must never recycle.",
                        text("idiom:package", """
                                Here is the entire contract this topic runs on — a real file in this repo, compiled by the build into everything that follows. Read it top to bottom and it already teaches the idiom: a SYNTAX version, a PACKAGE (the namespace that keeps this service's names from colliding with anyone else's on a shared classpath or wire), JAVA OPTIONS (which package the generated code lands in, and that we want one class per message rather than one giant outer class), then the SERVICE — methods only, no state — and the MESSAGES that flow through them:"""),
                        code("idiom:proto", "proto",
                                "server/src/main/proto/guide_demo.proto — the whole contract: syntax, package, java options, service, messages",
                                "server/src/main/proto/guide_demo.proto", """
                                syntax = "proto3";

                                package guide.demo;

                                option java_package = "guide.demo.grpc";
                                option java_multiple_files = true;
                                """),
                        text("idiom:numbers", """
                                The messages are where protobuf's one genuinely novel idea lives: EVERY FIELD CARRIES A NUMBER, and that number — not the field name — is the field's identity on the wire. The encoded message is a sequence of key-value pairs where the key is the field number plus a wire type tag; names exist only in the .proto and in generated code. Two consequences. First, the wire is TINY and schema-tolerant: a `string topic_id = 1` serializes as tag 1, length, bytes — no name, and an OLD reader that receives a NEW message simply skips field numbers it does not know. Second, and this is the rule burned into every protobuf style guide: NUMBERS ARE FOREVER. Never reuse a retired field's number for a different meaning — an old client would silently decode new bytes as the old field, corrupting data with no error anywhere. Deprecate with reserved:"""),
                        code("idiom:messages", "proto",
                                "guide_demo.proto — messages: numbered fields; the wire identity of a field is its number, not its name",
                                "server/src/main/proto/guide_demo.proto", """
                                message TopicInfoRequest {
                                  // Field numbers are the wire identity of a field — see the-idiom section.
                                  string topic_id = 1;

                                  // Artificial server-side work, so the deadline demo has something to miss.
                                  int32 delay_ms = 2;
                                }

                                message TopicInfoResponse {
                                  string topic_id = 1;
                                  string title = 2;
                                  string group = 3;
                                  int32 section_count = 4;
                                }
                                """),
                        text("idiom:evolution", """
                                That tolerance is the EVOLUTION story: adding a field (new number) is backward- AND forward-compatible — old servers ignore unknown fields, new servers read absent fields as defaults. Changing a field's TYPE or NUMBER is the breaking change; so is reusing a number (hence `reserved 7;` in real contracts, which forbids future use). proto3's other defaults worth knowing now: no `required` keyword (all fields optional — presence is per-type; scalars default to 0/"" and you cannot tell "absent" from "zero" without wrapping them in `optional`, which reintroduces explicit presence); repeated fields are lists; enums must start their numbering at zero, conventionally a VALUE_NAME_UNSPECIFIED sentinel. VERSIONING the service itself is naming discipline: keep the package stable, add rpcs (safe — old clients never call them), and only break (new package path like guide.demo.v2) when a signature must change shape.

                                Note what the request message sneaks in: `delay_ms = 2`, an artificial-work knob. The server sleeps before answering when it is set — it exists purely so the deadline demo later in this topic can order 1200ms of work under a 300ms deadline and watch the call die on time. A contract with a demonstration hook in it: very much this guide's style.""")),
                section("four-kinds-of-streaming", "The four kinds of streaming",
                        "Unary, server-streaming, client-streaming, bidirectional — one service block, four semantics, and backpressure for free.",
                        text("kinds:service", """
                                gRPC methods come in exactly four interaction shapes, declared with a single keyword: `stream`. Our service block is one specimen of each, and the console demo later exercises all four for real. The declarations:"""),
                        code("kinds:block", "proto",
                                "guide_demo.proto — the service: one rpc per interaction kind, `stream` is the only syntax difference",
                                "server/src/main/proto/guide_demo.proto", """
                                service GuideDemo {
                                  // UNARY: one request, one response — the RPC that behaves like a function.
                                  rpc GetTopicInfo(TopicInfoRequest) returns (TopicInfoResponse);

                                  // SERVER STREAMING: one request, a stream of responses, closed by the server.
                                  rpc StreamSectionTitles(TopicInfoRequest) returns (stream SectionTitle);

                                  // CLIENT STREAMING: a stream of requests from the client, one summary back.
                                  rpc AckSections(stream SectionTitle) returns (AckSummary);

                                  // BIDIRECTIONAL STREAMING: both sides stream; turns arrive when they happen.
                                  rpc EchoChat(stream ChatMessage) returns (stream ChatMessage);
                                }
                                """),
                        text("kinds:semantics", """
                                UNARY is the shape you already understand: one request, one response, a function call across the network — 95% of real-world rpcs, including our GetTopicInfo (topicId in, title/group/section-count out). SERVER STREAMING is one request answered by a SEQUENCE the server closes when done: our StreamSectionTitles sends one SectionTitle per section of the topic, in order, then ends — think result pages, logs tails, progress ticks; contrast the REST world's pagination loops or SSE. CLIENT STREAMING inverts it: the client sends a stream and receives ONE answer after it half-closes — our AckSections receives section titles and returns a count summary; the classic production case is upload/batch (many chunks in, one verdict out). BIDIRECTIONAL STREAMING gives both sides independent streams over one call — messages interleave in either order — our EchoChat echoes each message stamped with a server-side sequence; real bidi is chat protocols, muxed telemetry, twin subscriptions.

                                The quiet superpower in all three streaming kinds: BACKPRESSURE. gRPC's streams are flow-controlled per-stream by HTTP/2 WINDOW updates — the receiver grants credit, the sender waits when credit runs out. A slow consumer genuinely slows the producer instead of being drowned (or buffering unboundedly). You will meet the same law from the other side in topic 41 (reactive backpressure); here it arrives as a transport property, not an application discipline. The one discipline streaming DOES demand: decide who closes. The client signals end-of-requests (halfClose/onCompleted), the server closes the whole call — and error paths must close too, or streams leak until the deadline (a per-call deadline, which is why you always set one) reaps them."""),
                        text("kinds:which-when", """
                                Choosing a kind is interface design, same as choosing a verb in REST. Start unary — every method that CAN be unary SHOULD be; streaming is coupling to time, and callers pay for it. Reach for server-streaming when the answer is naturally a sequence or arrives over time (a topic's sections, a table scan, a tail). Client-streaming when the input is naturally a sequence or produced over time (batches, uploads, sensor bursts) and the output is a single verdict. Bidi only when BOTH ends genuinely produce over time AND interleave — full-duplex chat, synchronized replication. If you cannot name the interleaving, you want two unary methods or one server-stream, not bidi; most "we need websockets" designs are actually one server-stream plus one unary write path.""")),
                section("serving-from-spring", "Serving from Spring",
                        "One generated base class to extend, one annotation, one port — spring-grpc mounts a Netty gRPC server beside your MVC app.",
                        text("serve:generated-base", """
                                Everything on the server side hangs off the class protoc emitted: GuideDemoGrpc.GuideDemoImplBase — an abstract class with one method per rpc, each in the observer-callback idiom (you do not RETURN a response; you PUSH it to a StreamObserver and then signal completion — the same shape on both streaming and unary calls, which is why one pattern serves all four kinds). Our implementation extends it and answers from the REAL ContentRegistry — the same registry the REST content API serves; the unary answer and the section stream are the guide's own data, not fixtures. The @GrpcService annotation (from spring-grpc, meta-annotated @Service) makes the class a bean the gRPC server discovers:"""),
                        code("serve:unary-override", "java",
                                "guide/presentation/grpc/GuideDemoGrpcService.java — unary: push the answer, signal completion, or error the call",
                                "server/src/main/java/guide/presentation/grpc/GuideDemoGrpcService.java", """
                                @GrpcService
                                public class GuideDemoGrpcService extends GuideDemoGrpc.GuideDemoImplBase {

                                    private final ContentRegistry registry;

                                    public GuideDemoGrpcService(ContentRegistry registry) {
                                        this.registry = registry;
                                    }

                                    /** UNARY: one request, one answer — the registry lookup as a function call. */
                                    @Override
                                    public void getTopicInfo(TopicInfoRequest request,
                                            StreamObserver<TopicInfoResponse> responseObserver) {
                                        artificialWork(request.getDelayMs());
                                        registry.getTopicById(request.getTopicId()).ifPresentOrElse(topic -> {
                                            responseObserver.onNext(TopicInfoResponse.newBuilder()
                                                    .setTopicId(topic.id())
                                                    // …
                                                    .setSectionCount(topic.sections().size())
                                                    .build());
                                            responseObserver.onCompleted();
                                        }, () -> responseObserver.onError(Status.NOT_FOUND
                                                .withDescription("no topic with id \\"" + request.getTopicId()
                                                        + "\\" in the content registry")
                                                .asRuntimeException()));
                                    }
                                }"""),
                        text("serve:observer-idiom", """
                                Read the idiom carefully, because it recurs four times in that file. onNext(value) delivers a message (the one and only response here, the next element of a stream there). onCompleted() closes the call successfully — forgetting it hangs the client until its deadline, the classic first-week gRPC bug. onError(thrown) closes it as failed — and Status.NOT_FOUND.withDescription(...).asRuntimeException() is how a rich gRPC error is built (the next section dissects Status). The streaming server methods return the REQUEST-side observer instead — AckSections returns an observer whose onNext counts titles as they arrive and whose onCompleted (the client finished sending) finally emits the summary:"""),
                        code("serve:ack-observer", "java",
                                "guide/presentation/grpc/GuideDemoGrpcService.java — client-streaming: return the request observer, summarize on half-close",
                                "server/src/main/java/guide/presentation/grpc/GuideDemoGrpcService.java", """
                                    @Override
                                    public StreamObserver<SectionTitle> ackSections(
                                            StreamObserver<AckSummary> responseObserver) {
                                        AtomicInteger received = new AtomicInteger();
                                        return new StreamObserver<>() {
                                            @Override
                                            public void onNext(SectionTitle value) {
                                                received.incrementAndGet();
                                            }
                                            // …
                                            @Override
                                            public void onCompleted() {
                                                int count = received.get();
                                                responseObserver.onNext(AckSummary.newBuilder()
                                                        .setReceived(count)
                                                        .setAcknowledged(count)
                                                        .setNote("client streamed " + count + " section title(s); server acked all")
                                                        .build());
                                                responseObserver.onCompleted();
                                            }
                                        };
                                    }"""),
                        text("serve:binding", """
                                HOW THE SERVER BINDS — the part Spring does for you. The spring-grpc server starter (org.springframework.grpc) auto-configures a Netty gRPC server as a second lifecycle inside your Boot app: it finds every @GrpcService bean, binds each to the server via its generated bindService(), and starts listening on a configured port — alongside, not instead of, your MVC server on 8080. Two starters, one BOM, and two properties are the whole integration:"""),
                        code("serve:yml", "yaml",
                                "server/src/main/resources/application.yml — the Netty gRPC server on 9090 and the named client channel back to it",
                                "server/src/main/resources/application.yml", """
                                spring:
                                  grpc:
                                    server:
                                      port: 9090
                                    client:
                                      channels:
                                        guide:
                                          address: static://localhost:9090
                                """),
                        text("serve:manual-alternative", """
                                Worth naming the road not taken, because you will meet it in older codebases: BEFORE spring-grpc (and outside it), the manual wiring is grpc-java directly — NettyServerBuilder.forPort(9090).addService(new GuideDemoGrpcService(registry)).build().start() in a @Bean lifecycle, plus your own graceful-shutdown hook, plus interceptor plumbing. Projects like net.devd's grpc-spring-boot-starter packaged exactly that. This repo uses the official spring-grpc path (server + client starters, @GrpcService, GrpcChannelFactory), and the generated ImplBase/stub layer you write against is IDENTICAL either way — spring-grpc is lifecycle and configuration, not a different API. That also means the skills below transfer: extend the generated base, obey the observer contract, and any binding strategy works.""")),
                section("calling-grpc-stubs-and-channels", "Calling: stubs and channels",
                        "The client half: one channel, three stub flavors, and deadlines attached per call — the console demo, live.",
                        text("call:stubs", """
                                The codegen emits THREE client flavors off one Channel (the virtual connection to the server — one per server, shared, thread-safe, expensive; never one per request). The BLOCKING stub looks like a local method call — `TopicInfoResponse resp = stub.getTopicInfo(request)` — ideal for servlet/synchronous code, and its server-streaming methods return an Iterator you drain. The ASYNC stub speaks StreamObserver both ways: callbacks on gRPC executor threads, non-blocking, the natural fit for client-streaming and bidi. The FUTURE stub returns a ListenableFuture for unary fan-out. All three are thin immutable wrappers over the Channel: every per-call option (deadline, credentials, headers) DERIVES a new stub via with... methods rather than mutating shared state:"""),
                        code("call:config-beans", "java",
                                "guide/config/GrpcDemoConfig.java — the auto-configured channel factory; stubs are beans like any other dependency",
                                "server/src/main/java/guide/config/GrpcDemoConfig.java", """
                                @Bean
                                GuideDemoGrpc.GuideDemoBlockingStub guideDemoBlockingStub(GrpcChannelFactory channels) {
                                    return GuideDemoGrpc.newBlockingStub(channels.createChannel("guide"));
                                }

                                @Bean
                                GuideDemoGrpc.GuideDemoStub guideDemoAsyncStub(GrpcChannelFactory channels) {
                                    return GuideDemoGrpc.newStub(channels.createChannel("guide"));
                                }"""),
                        text("call:channel-lifecycle", """
                                GrpcChannelFactory is spring-grpc's client half: createChannel("guide") resolves the named channel's address (static://localhost:9090 in the yaml above) and hands back a channel the factory OWNS — shut down with the context, HTTP/2-connected lazily on first call, reconnected as needed. Address schemes are nameservers in disguise: static:// is a fixed endpoint, dns:/// goes through resolution (and pairs with client-side load balancing), in-process: bypasses the network entirely (the testing sweet — spring-grpc can run the server in-process so tests never bind sockets). A ManagedChannel is a heavy, stateful resource — creating one per request is the beginner failure; the bean here is shared by every demo call this app makes.

                                Deadlines are the option that matters most, and the rule is per CALL: derive the stub, set it, use it, throw it away:"""),
                        code("call:unary", "java",
                                "guide/domain/grpc/InvokeGuideDemo.java — unary through the blocking stub, exactly like a local call",
                                "server/src/main/java/guide/domain/grpc/InvokeGuideDemo.java", """
                                /** UNARY via the blocking stub — withDeadlineAfter derives the per-call stub. */
                                private TopicInfoView unary(GrpcInvocation invocation) {
                                    TopicInfoRequest request = requestOf(invocation);
                                    GuideDemoGrpc.GuideDemoBlockingStub call = withDeadline(blocking, invocation);
                                    TopicInfoResponse response = call.getTopicInfo(request);
                                    return new TopicInfoView(response.getTopicId(), response.getTitle(),
                                            response.getGroup(), response.getSectionCount());
                                }"""),
                        code("call:deadline", "java",
                                "guide/domain/grpc/InvokeGuideDemo.java — the per-call deadline: withDeadlineAfter derives, never mutates",
                                "server/src/main/java/guide/domain/grpc/InvokeGuideDemo.java", """
                                /** Deadlines are per CALL: withDeadlineAfter derives a new stub, never mutates. */
                                private static <S extends io.grpc.stub.AbstractStub<S>> S withDeadline(S stub, GrpcInvocation invocation) {
                                    if (invocation.deadlineMs() == null) {
                                        return stub;
                                    }
                                    return stub.withDeadlineAfter(invocation.deadlineMs(), TimeUnit.MILLISECONDS);
                                }"""),
                        text("call:invoke-path", """
                                The console's invoke endpoint is a thin HTTP face over that machinery — pick a kind, pick a topic, optionally order artificial delay and arm a deadline, and get back a verdict carrying the gRPC Status code (the deadline path returns DEADLINE_EXCEEDED, not an HTTP error — the demo's product is the rpc outcome, so the envelope stays OK and the verdict tells the truth):"""),
                        code("call:controller", "java",
                                "guide/presentation/demo/GrpcDemoController.java — the console endpoint: parse, hand to the use case, return the verdict",
                                "server/src/main/java/guide/presentation/demo/GrpcDemoController.java", """
                                @PostMapping(value = "/api/demo/grpc/invoke",
                                        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
                                InvokeGuideDemo.GrpcCallReport invoke(@RequestBody GrpcInvokeRequest request) {
                                    return unwrap(invokeGuideDemo.execute(new InvokeGuideDemo.GrpcInvocation(
                                            parseKind(request.method()), request.topicId(),
                                            request.delayMs() == null ? 0 : request.delayMs(),
                                            request.deadlineMs())));
                                }"""),
                        text("call:introspection", """
                                The sibling endpoint serves the CONTRACT itself by reading the descriptor the codegen produced — which rpcs exist and which of the four kinds each is. This is real introspection, the same discovery grpcurl and the reflection service do at runtime, three lines of it:"""),
                        code("call:descriptor", "java",
                                "guide/domain/grpc/DescribeGuideDemoService.java — the generated descriptor already knows every method and its kind",
                                "server/src/main/java/guide/domain/grpc/DescribeGuideDemoService.java", """
                                private static String kindOf(MethodDescriptor<?, ?> method) {
                                    return switch (method.getType()) {
                                        case UNARY -> "unary";
                                        case SERVER_STREAMING -> "server-streaming";
                                        case CLIENT_STREAMING -> "client-streaming";
                                        case BIDI_STREAMING -> "bidi-streaming";
                                    };
                                }"""),
                        demo("call:demo", "api:grpc-console", "The gRPC console, live",
                                "Pick an interaction kind and a topic, order artificial delay, arm a deadline, invoke. The console calls the REAL in-app gRPC server through all four kinds — unary answers and section streams come from the real content registry, client-streaming pipes the fetched titles into AckSections, bidi chats them back with server sequence numbers — and renders the round-trip time plus the Status verdict (OK, NOT_FOUND for unknown topics, DEADLINE_EXCEEDED when your 300ms deadline meets your 1200ms of ordered delay).")),
                section("errors-and-deadlines", "Errors and deadlines",
                        "Status codes, trailers, and the deadline that propagates — plus the retry policy survey and the demo's proof.",
                        text("err:status", """
                                gRPC errors are NOT HTTP errors wearing hats. A failed rpc is a Status — a code from a fixed, small, transport-agnostic enum — plus a message and optional machine-readable details, delivered in TRAILERS (metadata sent after the body; for errors, often only trailers at all). The codes you will actually live with: OK; INVALID_ARGUMENT (bad input — the 400 of rpc); NOT_FOUND (this guide's service returns it for an unknown topicId); UNAUTHENTICATED / PERMISSION_DENIED (401/403); RESOURCE_EXHAUSTED (429, quota); FAILED_PRECONDITION (the state is wrong — cancel a non-pending payment); UNAVAILABLE (the one worth retrying — server down, restarting); DEADLINE_EXCEEDED (your call outlived its deadline — or a hop downstream did); INTERNAL (your bug). The HTTP mapping exists (INVALID_ARGUMENT→400, UNAVAILABLE→503, DEADLINE_EXCEEDED→504, and so on) for gateways that must translate; in grpc-native code you match on the CODE, never on a string.

                                Building one server-side is the two-line idiom you saw in the service: Status.NOT_FOUND.withDescription(...).asRuntimeException() passed to onError — the status travels the trailers to the client, where it resurfaces as a StatusRuntimeException on the blocking stub or onError on the async observer. Our demo maps every failure into the verdict — one catch shape, the code and description surfaced as data:"""),
                        code("err:mapping", "java",
                                "guide/domain/grpc/InvokeGuideDemo.java — every failure arrives as one shape; the verdict carries code + description as data",
                                "server/src/main/java/guide/domain/grpc/InvokeGuideDemo.java", """
                                } catch (StatusRuntimeException grpcFailure) {
                                    Status status = grpcFailure.getStatus();
                                    return Result.success(new GrpcCallReport(invocation.method(),
                                            roundTripMs(startedAt), List.of(),
                                            status.getCode().name(), status.getDescription()));
                                }"""),
                        text("err:deadlines", """
                                DEADLINES deserve their own paragraph because they are gRPC's most load-bearing reliability feature. A DEADLINE is a point in time, set by the CALLING client, after which the answer is worthless; withDeadlineAfter(300, MILLISECONDS) arms it. When it fires the client abandons the call (DEADLINE_EXCEEDED) AND CANCELS it — the server's StreamObserver gets onError/cancellation and should stop work (our artificialWork sleep is deliberately dumb; a real handler checks isCancelled or responds to interrupt). The property that makes deadlines architectural: they PROPAGATE. The deadline travels the request; if service A calls B calls C, the remaining budget shrinks hop by hop — no hop can spend more than it was handed, and a 300ms SLA stays 300ms end to end. The demo's proof is deterministic: order delayMs=1200 of server work under deadlineMs=300, and the verdict comes back DEADLINE_EXCEEDED at ~300ms round-trip — not at 1200ms. The call did not wait politely for the server to finish; it kept its promise. Timeout-vs-deadline vocabulary: a TIMEOUT is a client-side stopwatch that gives up but tells nobody; a deadline is a first-class citizen the whole call chain honors.

                                RETRIES, surveyed honestly: retrying rpcs is safe exactly when they are idempotent, and grpc-java's built-in retry (a service-config policy naming retryable status codes — UNAVAILABLE classically, with exponential backoff and a cap) is DISABLED BY DEFAULT and configured via the name resolver's service config, not application code. Deadlines and retries interact lethally — retries must fit inside the ORIGINAL deadline (they share its remaining budget), or you multiply load precisely when the system is dying. The reliability topic adds the rest of the ladder: circuit breakers, hedging, bulkheads. The one-sentence policy this guide endorses: EVERY rpc gets an explicit, considered deadline; UNAVAILABLE gets a bounded, jittered retry only when the operation is idempotent; everything else fails fast into the caller's error handling.""")),
                section("codegen", "Codegen: the .proto is the program",
                        "The Maven pipeline that turns one file into the contract — and the hygiene rules generated code lives by.",
                        text("gen:pipeline", """
                                The build step that makes all of the above exist: protoc (the protobuf compiler) reads the .proto and emits Java message classes; the grpc-java protoc plugin reads the same file and emits the service layer — GuideDemoGrpc with ImplBase, the three stubs, and the METHOD descriptors; both outputs land under target/generated-sources and compile like any other source. This repo uses the ascopes protobuf-maven-plugin — actively maintained, current-JDK-native, protoc downloaded from Maven Central like any dependency (the classic org.xolstice plugin still works but has been effectively dormant for years). The real pom section, verbatim:"""),
                        code("gen:plugin", "xml",
                                "server/pom.xml — the codegen: protoc from Central over src/main/proto, then the grpc-java binary plugin",
                                "server/pom.xml", """
                                <plugin>
                                    <groupId>io.github.ascopes</groupId>
                                    <artifactId>protobuf-maven-plugin</artifactId>
                                    <version>5.1.11</version>
                                    <configuration>
                                        <sourceDirectories>
                                            <sourceDirectory>${project.basedir}/src/main/proto</sourceDirectory>
                                        </sourceDirectories>
                                        <protoc kind="binary-maven">
                                            <version>${protobuf-java.version}</version>
                                        </protoc>
                                        <plugins>
                                            <plugin kind="binary-maven">
                                                <groupId>io.grpc</groupId>
                                                <artifactId>protoc-gen-grpc-java</artifactId>
                                                <version>${grpc.version}</version>
                                            </plugin>
                                        </plugins>
                                    </configuration>
                                    <executions>
                                        <execution>
                                            <goals>
                                                <goal>generate</goal>
                                            </goals>
                                        </execution>
                                    </executions>
                                </plugin>"""),
                        text("gen:versions", """
                                Two version invariants make that config safe. PROTOC must match protobuf-java at runtime (both 4.33.4 here — a mismatch is silent wire corruption), and the protoc-gen-grpc-java binary must match grpc-java at runtime (both 1.77.1 — a mismatch is generated code calling APIs that moved). Rather than trusting transitive luck, the pom imports the spring-grpc BOM — one import aligns the spring-grpc starters with the grpc and protobuf versions they were tested against, and the four grpc artifacts declare no versions at all:"""),
                        code("gen:bom", "xml",
                                "server/pom.xml — the BOM import and the versionless grpc artifacts it aligns",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>org.springframework.grpc</groupId>
                                    <artifactId>spring-grpc-dependencies</artifactId>
                                    <version>${spring-grpc.version}</version>
                                    <type>pom</type>
                                    <scope>import</scope>
                                </dependency>"""),
                        text("gen:hygiene", """
                                GENERATED-CODE HYGIENE, the rules that keep the pipeline trustworthy. NEVER hand-edit generated sources — they live under target/, are regenerated on every build, and any edit vanishes (target/ is already ignored by this repo's .gitignore; if you ever feel tempted to commit generated code, regenerate instead). Treat regenerating as cheap and the .proto as the ONLY editable artifact: change the contract file, rebuild, and the compiler routes every consumer of a changed signature to fix itself. And remember what the two-stage pipeline is really for: the same guide_demo.proto can emit a TypeScript client, a Go server, Python stubs — swap the plugin list and the CONTRACT becomes polyglot without a line of shared code. That is the codegen section's one-sentence payoff: the .proto is not documentation of the system; it IS the system, and everything else this topic showed you — server base, stubs, descriptors — is a derived artifact you can regenerate at will.""")),
                section("terminal-companion", "The contract from a terminal",
                        "curl the console's endpoints — all four kinds, a NOT_FOUND, and a deadline that provably fires.",
                        text("term:tour", """
                                The gRPC wire itself is binary frames — curl cannot call GuideDemo directly the way it calls a REST route. But this app's console puts the rpcs behind ordinary JSON endpoints, so the terminal tour still drives everything real: introspect the service descriptor, invoke each interaction kind, ask for a topic that does not exist, and finally order 1200ms of work under a 300ms deadline and watch DEADLINE_EXCEEDED come back in ~300ms. Start the server (docker compose up, or run GuideApplication — the gRPC server mounts on 9090 inside the process), then take the tour. (When you want to speak native gRPC from a terminal: grpcurl -plaintext -d '{"topic_id":"grpc"}' localhost:9090 guide.demo.GuideDemo/GetTopicInfo — it works because the server starter registers gRPC's reflection service.)"""),
                        code("term:service-and-unary", "bash", "the contract as the descriptor sees it, then the unary kind against the real registry", null, """
                                # Which rpcs exist, and which interaction kind each is:
                                curl -s localhost:8080/api/demo/grpc/service | jq
                                # → {"serviceName":"guide.demo.GuideDemo","protoPackage":"guide.demo.GuideDemo",
                                #     "methods":[{"name":"GetTopicInfo","kind":"unary",…},
                                #                {"name":"StreamSectionTitles","kind":"server-streaming",…},
                                #                {"name":"AckSections","kind":"client-streaming",…},
                                #                {"name":"EchoChat","kind":"bidi-streaming",…}]}

                                # UNARY — one request, one answer from the real content registry:
                                curl -s -X POST localhost:8080/api/demo/grpc/invoke \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"method":"unary","topicId":"grpc"}' | jq
                                # → {"methodKind":"UNARY","roundTripMs":6,
                                #     "results":[{"topicId":"grpc","title":"gRPC",
                                #                 "group":"apis","sectionCount":8}],
                                #     "statusCode":"OK","statusMessage":null}"""),
                        code("term:streams", "bash", "the three streaming kinds — sections flow out, titles flow in, echoes come back stamped", null, """
                                # SERVER STREAMING — the topic's eight sections, one message each:
                                curl -s -X POST localhost:8080/api/demo/grpc/invoke \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"method":"server-stream","topicId":"grpc"}' | jq '.results | length'
                                # → 8

                                # CLIENT STREAMING — fetch the titles, stream them into AckSections:
                                curl -s -X POST localhost:8080/api/demo/grpc/invoke \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"method":"client-stream","topicId":"grpc"}' | jq '.results[0]'
                                # → {"received":8,"acknowledged":8,
                                #     "note":"client streamed 8 section title(s); server acked all"}

                                # BIDI — every title echoed with a server-side sequence number:
                                curl -s -X POST localhost:8080/api/demo/grpc/invoke \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"method":"bidi","topicId":"grpc"}' | jq '.results[0]'
                                # → {"sender":"server","body":"echo: What gRPC is","sequence":1}"""),
                        code("term:not-found-and-deadline", "bash", "the error paths: an unknown topic is NOT_FOUND; a short deadline beats long work", null, """
                                # Unknown topic — the gRPC Status rides the verdict (HTTP stays 200):
                                curl -s -X POST localhost:8080/api/demo/grpc/invoke \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"method":"unary","topicId":"nope"}' | jq '{code:.statusCode,msg:.statusMessage}'
                                # → {"code":"NOT_FOUND","msg":"no topic with id \\"nope\\" in the content registry"}

                                # The deadline proof: order 1200ms of server work, arm a 300ms deadline —
                                # the call gives up at ~300ms, never waits for the work to finish:
                                curl -s -X POST localhost:8080/api/demo/grpc/invoke \\
                                  -H 'Content-Type: application/json' \\
                                  -d '{"method":"unary","topicId":"grpc","delayMs":1200,"deadlineMs":300}' \\
                                  | jq '{code:.statusCode,roundTripMs}'
                                # → {"code":"DEADLINE_EXCEEDED","roundTripMs":301}""")));
    }
}
