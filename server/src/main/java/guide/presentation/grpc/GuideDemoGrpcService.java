package guide.presentation.grpc;

import guide.content.ContentRegistry;
import guide.demo.grpc.AckSummary;
import guide.demo.grpc.ChatMessage;
import guide.demo.grpc.GuideDemoGrpc;
import guide.demo.grpc.SectionTitle;
import guide.demo.grpc.TopicInfoRequest;
import guide.demo.grpc.TopicInfoResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.grpc.server.service.GrpcService;

/**
 * Topic 15 (grpc) — the server half of the guide.demo contract. Extends the
 * generated {@link GuideDemoGrpc.GuideDemoImplBase}: one override per rpc,
 * each answering from the REAL {@link ContentRegistry} (topic info and
 * section titles are the same data the REST content API serves — not stubs).
 *
 * The {@code delay_ms} field on TopicInfoRequest is an artificial-work hook:
 * the server sleeps BEFORE doing anything, so a client deadline shorter than
 * the delay provably misses (DEADLINE_EXCEEDED) — that is the deadline demo.
 */
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
                    .setTitle(topic.title())
                    .setGroup(topic.group().wireName())
                    .setSectionCount(topic.sections().size())
                    .build());
            responseObserver.onCompleted();
        }, () -> responseObserver.onError(Status.NOT_FOUND
                .withDescription("no topic with id \"" + request.getTopicId()
                        + "\" in the content registry")
                .asRuntimeException()));
    }

    /** SERVER STREAMING: the registry's sections, one onNext each, server closes. */
    @Override
    public void streamSectionTitles(TopicInfoRequest request,
            StreamObserver<SectionTitle> responseObserver) {
        artificialWork(request.getDelayMs());
        registry.getTopicById(request.getTopicId()).ifPresentOrElse(topic -> {
            topic.sections().forEach(section -> responseObserver.onNext(
                    SectionTitle.newBuilder()
                            .setSectionId(section.id())
                            .setTitle(section.title())
                            .build()));
            responseObserver.onCompleted();
        }, () -> responseObserver.onError(Status.NOT_FOUND
                .withDescription("no topic with id \"" + request.getTopicId()
                        + "\" in the content registry")
                .asRuntimeException()));
    }

    /**
     * CLIENT STREAMING: return the request-side observer that counts sections
     * as they arrive; the summary goes back once the client half-closes.
     */
    @Override
    public StreamObserver<SectionTitle> ackSections(
            StreamObserver<AckSummary> responseObserver) {
        AtomicInteger received = new AtomicInteger();
        return new StreamObserver<>() {
            @Override
            public void onNext(SectionTitle value) {
                received.incrementAndGet();
            }

            @Override
            public void onError(Throwable t) {
                // Client went away mid-stream: nothing to summarize, just log.
                Status status = Status.fromThrowable(t);
                System.getLogger(GuideDemoGrpcService.class.getName())
                        .log(System.Logger.Level.WARNING,
                                "AckSections aborted by client: {0} {1}",
                                status.getCode(), status.getDescription());
            }

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
    }

    /** BIDIRECTIONAL STREAMING: every message echoed, stamped server-side. */
    @Override
    public StreamObserver<ChatMessage> echoChat(
            StreamObserver<ChatMessage> responseObserver) {
        AtomicInteger sequence = new AtomicInteger();
        return new StreamObserver<>() {
            @Override
            public void onNext(ChatMessage value) {
                responseObserver.onNext(ChatMessage.newBuilder(value)
                        .setSender("server")
                        .setBody("echo: " + value.getBody())
                        .setSequence(sequence.incrementAndGet())
                        .build());
            }

            @Override
            public void onError(Throwable t) {
                System.getLogger(GuideDemoGrpcService.class.getName())
                        .log(System.Logger.Level.WARNING,
                                "EchoChat aborted by client: {0}",
                                Status.fromThrowable(t).getCode());
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }

    /** The deadline demo's target: sleep first, so short deadlines miss. */
    private static void artificialWork(int delayMs) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
