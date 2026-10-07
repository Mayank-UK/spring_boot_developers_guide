package guide.config;

import guide.demo.grpc.GuideDemoGrpc;
import guide.domain.grpc.DescribeGuideDemoService;
import guide.domain.grpc.InvokeGuideDemo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/**
 * Demo wiring for topic 15 (grpc): the client-side channel and the stubs the
 * invoke use case calls through. The {@link GrpcChannelFactory} is the one
 * the spring-grpc client starter auto-configures — createChannel("guide")
 * resolves the named channel's address from spring.grpc.client.channels.guide
 * (static://localhost:9090 in application.yml) and returns a channel the
 * factory manages for shutdown. Stubs are thin immutable wrappers over the
 * channel; the per-call deadline is derived later via withDeadlineAfter.
 */
@Configuration
public class GrpcDemoConfig {

    @Bean
    GuideDemoGrpc.GuideDemoBlockingStub guideDemoBlockingStub(GrpcChannelFactory channels) {
        return GuideDemoGrpc.newBlockingStub(channels.createChannel("guide"));
    }

    @Bean
    GuideDemoGrpc.GuideDemoStub guideDemoAsyncStub(GrpcChannelFactory channels) {
        return GuideDemoGrpc.newStub(channels.createChannel("guide"));
    }

    @Bean
    InvokeGuideDemo invokeGuideDemo(GuideDemoGrpc.GuideDemoBlockingStub blocking,
            GuideDemoGrpc.GuideDemoStub async) {
        return new InvokeGuideDemo(blocking, async);
    }

    @Bean
    DescribeGuideDemoService describeGuideDemoService() {
        return new DescribeGuideDemoService();
    }
}
