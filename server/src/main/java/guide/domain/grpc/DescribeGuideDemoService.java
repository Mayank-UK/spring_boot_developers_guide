package guide.domain.grpc;

import guide.demo.grpc.GuideDemoGrpc;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import java.util.List;

/**
 * Use case: describe the guide-demo service by READING the descriptor the
 * codegen produced — not a hand-maintained list. {@link GuideDemoGrpc}'s
 * static descriptor carries every method's name, full wire name, and
 * interaction kind, which is exactly what a gRPC client (or grpcurl via the
 * reflection service) discovers at runtime.
 */
public class DescribeGuideDemoService {

    /** One rpc as the descriptor sees it. */
    public record RpcView(String name, String fullMethodName, String kind) {
    }

    /** The service summary the demo console serves. */
    public record ServiceView(String serviceName, String protoPackage, List<RpcView> methods) {
    }

    public ServiceView execute() {
        ServiceDescriptor descriptor = GuideDemoGrpc.getServiceDescriptor();
        List<RpcView> methods = descriptor.getMethods().stream()
                .map(method -> new RpcView(
                        simpleName(method),
                        method.getFullMethodName(),
                        kindOf(method)))
                .toList();
        return new ServiceView(GuideDemoGrpc.SERVICE_NAME, descriptor.getName(), methods);
    }

    private static String simpleName(MethodDescriptor<?, ?> method) {
        String full = method.getFullMethodName();
        int slash = full.lastIndexOf('/');
        return slash >= 0 ? full.substring(slash + 1) : full;
    }

    private static String kindOf(MethodDescriptor<?, ?> method) {
        return switch (method.getType()) {
            case UNARY -> "unary";
            case SERVER_STREAMING -> "server-streaming";
            case CLIENT_STREAMING -> "client-streaming";
            case BIDI_STREAMING -> "bidi-streaming";
            case UNKNOWN -> "unknown";
        };
    }
}
