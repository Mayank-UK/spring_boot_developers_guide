package guide.domain.grpc;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.demo.grpc.AckSummary;
import guide.demo.grpc.ChatMessage;
import guide.demo.grpc.GuideDemoGrpc;
import guide.demo.grpc.SectionTitle;
import guide.demo.grpc.TopicInfoRequest;
import guide.demo.grpc.TopicInfoResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Use case: execute one guide-demo rpc of the caller's chosen kind against the
 * running gRPC server, and report what happened — results, round-trip
 * milliseconds, and the gRPC {@link Status} code (OK on success,
 * DEADLINE_EXCEEDED when the caller's deadline beat the server's artificial
 * delay, NOT_FOUND for an unknown topic).
 *
 * The two streaming CLIENT kinds (client-stream, bidi) still need real
 * messages to send, and this app's real data is topic sections — so both
 * first fetch the topic's section titles via the server-streaming rpc, then
 * stream those titles onward (into AckSections, or through EchoChat). The
 * kinds compose, which is rather the point of streams.
 */
public class InvokeGuideDemo {

    /** Which of the four interaction kinds to exercise. */
    public enum MethodKind {UNARY, SERVER_STREAM, CLIENT_STREAM, BIDI}

    /** The demo request as it arrives from the wire. */
    public record GrpcInvocation(MethodKind method, String topicId, int delayMs, Integer deadlineMs) {
    }

    /** Plain view records — proto messages never leak past this use case. */
    public record TopicInfoView(String topicId, String title, String group, int sectionCount) {
    }

    public record SectionTitleView(String sectionId, String title) {
    }

    public record AckSummaryView(int received, int acknowledged, String note) {
    }

    public record ChatEchoView(String sender, String body, int sequence) {
    }

    /** The one answer the demo console renders: method, timing, results, status. */
    public record GrpcCallReport(MethodKind methodKind, long roundTripMs, List<Object> results,
            String statusCode, String statusMessage) {
    }

    /** Internal ceiling for a streamed call to settle — a stuck stream must not hang the demo. */
    private static final long SETTLE_TIMEOUT_MS = 10_000;

    private static final Set<MethodKind> ALL_KINDS = Set.of(MethodKind.values());

    private final GuideDemoGrpc.GuideDemoBlockingStub blocking;
    private final GuideDemoGrpc.GuideDemoStub async;

    public InvokeGuideDemo(GuideDemoGrpc.GuideDemoBlockingStub blocking, GuideDemoGrpc.GuideDemoStub async) {
        this.blocking = blocking;
        this.async = async;
    }

    public Result<GrpcCallReport> execute(GrpcInvocation invocation) {
        if (invocation == null || invocation.method() == null || invocation.topicId() == null) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "method (one of " + ALL_KINDS + ") and topicId are required",
                    java.util.Map.of("parameter", "method/topicId")));
        }
        if (invocation.delayMs() < 0 || (invocation.deadlineMs() != null && invocation.deadlineMs() < 0)) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "delayMs and deadlineMs must be zero or positive",
                    java.util.Map.of("parameter", "delayMs/deadlineMs")));
        }

        long startedAt = System.nanoTime();
        try {
            List<Object> results = switch (invocation.method()) {
                case UNARY -> List.of(unary(invocation));
                case SERVER_STREAM -> serverStream(invocation);
                case CLIENT_STREAM -> List.of(clientStream(invocation));
                case BIDI -> bidi(invocation);
            };
            return Result.success(new GrpcCallReport(invocation.method(),
                    roundTripMs(startedAt), results, Status.Code.OK.name(), null));
        } catch (StatusRuntimeException grpcFailure) {
            Status status = grpcFailure.getStatus();
            return Result.success(new GrpcCallReport(invocation.method(),
                    roundTripMs(startedAt), List.of(),
                    status.getCode().name(), status.getDescription()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failed(Failure.of(FailureKind.INTERNAL,
                    "interrupted while streaming", java.util.Map.of()));
        }
    }

    /** UNARY via the blocking stub — withDeadlineAfter derives the per-call stub. */
    private TopicInfoView unary(GrpcInvocation invocation) {
        TopicInfoRequest request = requestOf(invocation);
        GuideDemoGrpc.GuideDemoBlockingStub call = withDeadline(blocking, invocation);
        TopicInfoResponse response = call.getTopicInfo(request);
        return new TopicInfoView(response.getTopicId(), response.getTitle(),
                response.getGroup(), response.getSectionCount());
    }

    /** SERVER STREAMING via the blocking stub's Iterator — drain it, view each. */
    private List<Object> serverStream(GrpcInvocation invocation) {
        List<Object> views = new ArrayList<>();
        GuideDemoGrpc.GuideDemoBlockingStub call = withDeadline(blocking, invocation);
        call.streamSectionTitles(requestOf(invocation))
                .forEachRemaining(title -> views.add(viewOf(title)));
        return List.copyOf(views);
    }

    /**
     * CLIENT STREAMING via the async stub: fetch this topic's real titles from
     * the server-streaming rpc, then stream them into AckSections and await
     * the single summary that comes back.
     */
    private AckSummaryView clientStream(GrpcInvocation invocation) throws InterruptedException {
        List<SectionTitle> titles = fetchTitles(invocation);
        AwaitedCall<AckSummary> awaited = new AwaitedCall<>();
        StreamObserver<AckSummary> responseObserver = awaited.responseObserver();
        StreamObserver<SectionTitle> requestObserver = withDeadline(async, invocation)
                .ackSections(responseObserver);
        titles.forEach(requestObserver::onNext);
        requestObserver.onCompleted();
        AckSummary summary = awaited.await();
        return new AckSummaryView(summary.getReceived(), summary.getAcknowledged(), summary.getNote());
    }

    /** BIDI via the async stub: chat the titles, collect the server's echoes. */
    private List<Object> bidi(GrpcInvocation invocation) throws InterruptedException {
        List<SectionTitle> titles = fetchTitles(invocation);
        AwaitedCall<ChatMessage> awaited = new AwaitedCall<>();
        StreamObserver<ChatMessage> requestObserver = withDeadline(async, invocation)
                .echoChat(awaited.responseObserver());
        titles.forEach(title -> requestObserver.onNext(ChatMessage.newBuilder()
                .setSender("client")
                .setBody(title.getTitle())
                .build()));
        requestObserver.onCompleted();
        return awaited.awaitAll().stream()
                .map(message -> new ChatEchoView(message.getSender(), message.getBody(),
                        message.getSequence()))
                .map(Object.class::cast)
                .toList();
    }

    /** The shared request: which topic, and how much artificial work to order. */
    private static TopicInfoRequest requestOf(GrpcInvocation invocation) {
        return TopicInfoRequest.newBuilder()
                .setTopicId(invocation.topicId())
                .setDelayMs(invocation.delayMs())
                .build();
    }

    private List<SectionTitle> fetchTitles(GrpcInvocation invocation) {
        List<SectionTitle> titles = new ArrayList<>();
        withDeadline(blocking, invocation).streamSectionTitles(requestOf(invocation))
                .forEachRemaining(titles::add);
        return List.copyOf(titles);
    }

    /** Deadlines are per CALL: withDeadlineAfter derives a new stub, never mutates. */
    private static <S extends io.grpc.stub.AbstractStub<S>> S withDeadline(S stub, GrpcInvocation invocation) {
        if (invocation.deadlineMs() == null) {
            return stub;
        }
        return stub.withDeadlineAfter(invocation.deadlineMs(), TimeUnit.MILLISECONDS);
    }

    private static SectionTitleView viewOf(SectionTitle title) {
        return new SectionTitleView(title.getSectionId(), title.getTitle());
    }

    private static long roundTripMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    /**
     * The async-to-blocking bridge: responses land on gRPC executor threads,
     * the servlet thread awaits the latch. Errors surface as
     * StatusRuntimeException so one catch maps every failure shape.
     */
    private static final class AwaitedCall<T> {
        private final CountDownLatch done = new CountDownLatch(1);
        private final List<T> values = new ArrayList<>();
        private final AtomicReference<StatusRuntimeException> failure = new AtomicReference<>();

        StreamObserver<T> responseObserver() {
            return new StreamObserver<>() {
                @Override
                public void onNext(T value) {
                    values.add(value);
                }

                @Override
                public void onError(Throwable t) {
                    failure.set((StatusRuntimeException) Status.fromThrowable(t).asRuntimeException());
                    done.countDown();
                }

                @Override
                public void onCompleted() {
                    done.countDown();
                }
            };
        }

        T await() throws InterruptedException {
            awaitSettled();
            throwIfFailed();
            if (values.size() != 1) {
                throw Status.INTERNAL.withDescription(
                        "expected exactly one response, got " + values.size()).asRuntimeException();
            }
            return values.getFirst();
        }

        List<T> awaitAll() throws InterruptedException {
            awaitSettled();
            throwIfFailed();
            return List.copyOf(values);
        }

        private void awaitSettled() throws InterruptedException {
            if (!done.await(SETTLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw Status.DEADLINE_EXCEEDED.withDescription(
                        "stream did not settle within " + SETTLE_TIMEOUT_MS + "ms").asRuntimeException();
            }
        }

        private void throwIfFailed() {
            StatusRuntimeException thrown = failure.get();
            if (thrown != null) {
                throw thrown;
            }
        }
    }
}
