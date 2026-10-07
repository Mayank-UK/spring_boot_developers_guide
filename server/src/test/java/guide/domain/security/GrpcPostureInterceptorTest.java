package guide.domain.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The deliberate gRPC posture, unit-pinned with hand-rolled fakes (the
 * recording call captures closes; the wrapping listener captures stamped
 * headers): anonymous calls pass AND carry the permit-all stamp on the
 * wire; presented credentials are REFUSED UNAUTHENTICATED — never silently
 * ignored. The end-to-end proof that normal demo calls still succeed is
 * GrpcDemoIntegrationTest, which runs the real server with this interceptor
 * registered globally.
 */
class GrpcPostureInterceptorTest {

    /** Records closes; delegates nothing (nothing else is called on the refuse path). */
    private static final class RecordingCall extends ServerCall<Void, Void> {
        final List<Status> closedWith = new ArrayList<>();
        final List<Metadata> sentHeaders = new ArrayList<>();

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void sendHeaders(Metadata headers) {
            sentHeaders.add(headers);
        }

        @Override
        public void sendMessage(Void message) {
        }

        @Override
        public void close(Status status, Metadata trailers) {
            closedWith.add(status);
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public io.grpc.MethodDescriptor<Void, Void> getMethodDescriptor() {
            return null;
        }
    }

    private static final class RecordingHandler implements ServerCallHandler<Void, Void> {
        boolean started;
        ServerCall<Void, Void> receivedCall;

        @Override
        public ServerCall.Listener<Void> startCall(ServerCall<Void, Void> call, Metadata headers) {
            started = true;
            receivedCall = call;
            return new ServerCall.Listener<>() {
            };
        }
    }

    private final GrpcPostureInterceptor interceptor = new GrpcPostureInterceptor();

    @Test
    void anonymousCallsPassAndStampThePostureOnTheWire() {
        RecordingCall call = new RecordingCall();
        RecordingHandler handler = new RecordingHandler();

        ServerCall.Listener<Void> listener = interceptor.interceptCall(call, new Metadata(), handler);

        assertTrue(handler.started, "the call proceeds to the service");
        assertTrue(call.closedWith.isEmpty(), "nothing is closed on the happy path");
        assertTrue(listener != null);
        assertTrue(handler.receivedCall != call,
                "the service receives the WRAPPING call — the stamping seam");

        // the framework asks the wrapped call to send headers — the stamp lands there
        handler.receivedCall.sendHeaders(new Metadata());
        assertEquals(1, call.sentHeaders.size());
        List<String> stamped = new ArrayList<>();
        call.sentHeaders.getFirst().getAll(
                Metadata.Key.of("x-sdg-grpc-posture", Metadata.ASCII_STRING_MARSHALLER))
                .forEach(stamped::add);
        assertEquals(List.of(GrpcPostureInterceptor.POSTURE_VERDICT), stamped);
    }

    @Test
    void presentedCredentialsAreRefusedUnauthenticatedNeverIgnored() {
        RecordingCall call = new RecordingCall();
        RecordingHandler handler = new RecordingHandler();

        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer eyJhbGciOiJIUzI1NiJ9.cred.fake");

        interceptor.interceptCall(call, headers, handler);

        assertFalse(handler.started, "a credential-bearing call never reaches the service");
        assertEquals(1, call.closedWith.size());
        assertEquals(Status.Code.UNAUTHENTICATED, call.closedWith.getFirst().getCode());
        assertTrue(call.closedWith.getFirst().getDescription().contains("never silently ignored"));
    }
}
