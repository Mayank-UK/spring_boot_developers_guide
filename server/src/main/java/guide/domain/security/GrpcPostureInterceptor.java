package guide.domain.security;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * Topic 25's DELIBERATE gRPC posture, replacing topic 23's blanket
 * autoconfiguration exclusion with a decision stated in code. The demo gRPC
 * surface (topic 15's teaching island) authenticates NOTHING — and that
 * verdict is now enforced as an INTERCEPTOR, registered globally by
 * SecurityDemoConfig via spring-grpc's @GlobalServerInterceptor, so every
 * service call crosses it.
 *
 * Two rules, both honest:
 *
 * 1. Every response carries {@code x-sdg-grpc-posture: permit-all(teaching-island)}
 *    in its headers — the posture is STAMPED on the wire, auditable by any
 *    client, not a fact buried in a config file.
 * 2. A call that PRESENTS an Authorization header is refused UNAUTHENTICATED.
 *    Presenting credentials to a surface that has no mechanism to validate
 *    them must fail loudly, never be silently ignored — "accepted then
 *    discarded" is how callers come to believe they are authenticated when
 *    they are not.
 *
 * The authenticated option (sketched in the topic's prose, verified against
 * the shipped spring-grpc API): delete the remaining yaml exclusion and the
 * autoconfigured GrpcSecurity builder becomes live — customize it with
 * authorizeRequests(... anyRequest().authenticated()) + oauth2ResourceServer
 * (jwt), and the client channel carries spring-grpc's
 * BearerTokenAuthenticationInterceptor. The machinery is on the classpath
 * already; only the decision was missing.
 */
public final class GrpcPostureInterceptor implements ServerInterceptor {

    /** The on-the-wire posture stamp (readable by any gRPC client's metadata dump). */
    static final Metadata.Key<String> POSTURE = Metadata.Key.of(
            "x-sdg-grpc-posture", Metadata.ASCII_STRING_MARSHALLER);

    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of(
            "Authorization", Metadata.ASCII_STRING_MARSHALLER);

    /** The stamped verdict — one constant, the whole posture. */
    public static final String POSTURE_VERDICT = "permit-all(teaching-island)";

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (headers.containsKey(AUTHORIZATION)) {
            call.close(Status.UNAUTHENTICATED.withDescription(
                    "this surface authenticates nothing by design; credentials it cannot validate "
                            + "are refused, never silently ignored"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void sendHeaders(Metadata responseHeaders) {
                responseHeaders.put(POSTURE, POSTURE_VERDICT);
                super.sendHeaders(responseHeaders);
            }
        }, headers);
    }
}
