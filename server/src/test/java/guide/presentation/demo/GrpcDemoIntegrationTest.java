package guide.presentation.demo;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Topic 15's demo against the REAL application context — the full gRPC round
 * trip: HTTP → controller → use case → generated stub → channel → the Netty
 * server spring-grpc mounted on 19090 (not the app's 9090, so a concurrently
 * running dev server never collides) → GuideDemoGrpcService → the REAL
 * ContentRegistry. Every interaction kind is exercised through the same
 * endpoint the browser console uses, and the deadline case proves
 * DEADLINE_EXCEEDED arrives at ~deadlineMs while the server is still
 * sleeping, not after the artificial delay finishes.
 */
@SpringBootTest(properties = {
        "spring.grpc.server.port=19090",
        "spring.grpc.client.channels.guide.address=static://localhost:19090"})
@AutoConfigureMockMvc
class GrpcDemoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String INVOKE = "/api/demo/grpc/invoke";
    private static final String SERVICE = "/api/demo/grpc/service";

    @Test
    void serviceEndpointReflectsTheGeneratedDescriptor() throws Exception {
        mockMvc.perform(get(SERVICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceName").value("guide.demo.GuideDemo"))
                .andExpect(jsonPath("$.protoPackage").value("guide.demo.GuideDemo"))
                .andExpect(jsonPath("$.methods.length()").value(4))
                .andExpect(jsonPath("$.methods[?(@.name == 'GetTopicInfo')].kind")
                        .value(org.hamcrest.Matchers.hasItem("unary")))
                .andExpect(jsonPath("$.methods[?(@.name == 'StreamSectionTitles')].kind")
                        .value(org.hamcrest.Matchers.hasItem("server-streaming")))
                .andExpect(jsonPath("$.methods[?(@.name == 'AckSections')].kind")
                        .value(org.hamcrest.Matchers.hasItem("client-streaming")))
                .andExpect(jsonPath("$.methods[?(@.name == 'EchoChat')].kind")
                        .value(org.hamcrest.Matchers.hasItem("bidi-streaming")));
    }

    @Test
    void unaryAnswersFromTheRealContentRegistry() throws Exception {
        mockMvc.perform(post(INVOKE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"unary\",\"topicId\":\"grpc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.methodKind").value("UNARY"))
                .andExpect(jsonPath("$.statusCode").value("OK"))
                .andExpect(jsonPath("$.results[0].topicId").value("grpc"))
                .andExpect(jsonPath("$.results[0].title").value("gRPC"))
                .andExpect(jsonPath("$.results[0].group").value("apis"))
                .andExpect(jsonPath("$.results[0].sectionCount").value(8))
                .andExpect(jsonPath("$.roundTripMs", greaterThanOrEqualTo(0)));
    }

    @Test
    void unaryDelayIsVisibleInTheRoundTrip() throws Exception {
        mockMvc.perform(post(INVOKE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"unary\",\"topicId\":\"grpc\",\"delayMs\":200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value("OK"))
                .andExpect(jsonPath("$.roundTripMs", greaterThanOrEqualTo(190)));
    }

    @Test
    void unknownTopicIsTheGrpcNotFoundStatusInsideAnOkVerdict() throws Exception {
        mockMvc.perform(post(INVOKE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"unary\",\"topicId\":\"does-not-exist\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.statusMessage")
                        .value(org.hamcrest.Matchers.containsString("does-not-exist")))
                .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    void serverStreamReturnsEveryRealSectionTitleInOrder() throws Exception {
        mockMvc.perform(post(INVOKE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"server-stream\",\"topicId\":\"grpc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.methodKind").value("SERVER_STREAM"))
                .andExpect(jsonPath("$.statusCode").value("OK"))
                .andExpect(jsonPath("$.results.length()").value(8))
                .andExpect(jsonPath("$.results[0].sectionId").value("what-grpc-is"))
                .andExpect(jsonPath("$.results[7].sectionId").value("terminal-companion"));
    }

    @Test
    void clientStreamPipesTitlesIntoTheAckSummary() throws Exception {
        mockMvc.perform(post(INVOKE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"client-stream\",\"topicId\":\"grpc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.methodKind").value("CLIENT_STREAM"))
                .andExpect(jsonPath("$.statusCode").value("OK"))
                .andExpect(jsonPath("$.results[0].received").value(8))
                .andExpect(jsonPath("$.results[0].acknowledged").value(8))
                .andExpect(jsonPath("$.results[0].note")
                        .value(org.hamcrest.Matchers.containsString("8 section")));
    }

    @Test
    void bidiEchoesEveryMessageWithServerSequenceNumbers() throws Exception {
        mockMvc.perform(post(INVOKE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"bidi\",\"topicId\":\"grpc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.methodKind").value("BIDI"))
                .andExpect(jsonPath("$.statusCode").value("OK"))
                .andExpect(jsonPath("$.results.length()").value(8))
                .andExpect(jsonPath("$.results[0].sender").value("server"))
                .andExpect(jsonPath("$.results[0].sequence").value(1))
                .andExpect(jsonPath("$.results[0].body")
                        .value(org.hamcrest.Matchers.startsWith("echo: ")))
                .andExpect(jsonPath("$.results[7].sequence").value(8));
    }

    @Test
    void deadlineBeatsTheArtificialDelayWithDeadlineExceeded() throws Exception {
        // Server sleeps 1200ms; the call carries a 300ms deadline — the client
        // must give up at ~300ms, never after the server finishes. The upper
        // bound is sanity headroom, not a perf SLA: under the JaCoCo agent in
        // `mvn verify` every grpc call is instrumented (observed ~6.2s round
        // trip), so instrumented verify runs and CI runners need the room.
        mockMvc.perform(post(INVOKE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"unary\",\"topicId\":\"grpc\","
                                + "\"delayMs\":1200,\"deadlineMs\":300}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.methodKind").value("UNARY"))
                .andExpect(jsonPath("$.statusCode").value("DEADLINE_EXCEEDED"))
                .andExpect(jsonPath("$.statusMessage")
                        .value(org.hamcrest.Matchers.containsString("deadline")))
                .andExpect(jsonPath("$.results.length()").value(0))
                .andExpect(jsonPath("$.roundTripMs", greaterThanOrEqualTo(280)))
                .andExpect(jsonPath("$.roundTripMs", lessThan(15000)));
    }

    @Test
    void anUnknownMethodKindIsA400ValidationEnvelope() throws Exception {
        mockMvc.perform(post(INVOKE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"carrier-pigeon\",\"topicId\":\"grpc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }
}
