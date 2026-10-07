package guide.presentation.demo;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.ReactiveDemoConfig;
import guide.domain.reactive.CompareExecutionModels;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MockMvc over the reactive:compare demo surfaces with the topic's own
 * wiring. Deterministic facts pinned: both comparison models process every
 * item and produce the SAME checksum (identical work), the blocking half
 * shows one named virtual thread per item (mvc-item-*) while the pipeline
 * shows only boundedElastic-* threads (never one per item); the drop
 * strategy sheds exactly the surplus and a late request recovers NOTHING,
 * the buffer strategy queues it and the same late demand drains exactly
 * its count; the ndjson stream is a REAL Flux bridged through MVC's async
 * dispatch (element-per-line); the Mono endpoint answers one JSON object;
 * the WebClient fetch runs the fake stubbed exchange (no socket in the
 * slice); and bad input is the typed 400 envelope, never a 500. Wall-time
 * fields are read but never asserted — counts and identities only.
 */
@WebMvcTest(ReactiveDemoController.class)
@Import(ReactiveDemoConfig.class)
class ReactiveDemoControllerTest {

    /** The stream endpoint's media type (no Spring constant: ndjson is a string media type). */
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void bothModelsProcessEveryItemAndAgreeOnTheChecksum() throws Exception {
        mockMvc.perform(get("/api/demo/reactive/compare").param("items", "24").param("concurrency", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").value(24))
                .andExpect(jsonPath("$.concurrency").value(8))
                .andExpect(jsonPath("$.workChecksum").value(CompareExecutionModels.checksum(24)))
                .andExpect(jsonPath("$.blockingPerItem.itemsProcessed").value(24))
                .andExpect(jsonPath("$.reactorPipeline.itemsProcessed").value(24))
                .andExpect(jsonPath("$.reading[0]")
                        .value(Matchers.containsString("identical work, identical answer")));
    }

    @Test
    void theThreadShapesTellTheTwoModelsApart() throws Exception {
        mockMvc.perform(get("/api/demo/reactive/compare").param("items", "16").param("concurrency", "4"))
                .andExpect(status().isOk())
                // one named virtual thread per item — the topic 38 posture
                .andExpect(jsonPath("$.blockingPerItem.distinctThreads").value(16))
                .andExpect(jsonPath("$.blockingPerItem.sampleThreads[0]").value("mvc-item-1"))
                .andExpect(jsonPath("$.blockingPerItem.sampleThreads[1]")
                        .value(matchesPattern("mvc-item-\\d+")))
                // a small boundedElastic pool — never one thread per item
                // (JDK 21+ Reactor names them loomBoundedElastic-N)
                .andExpect(jsonPath("$.reactorPipeline.distinctThreads")
                        .value(Matchers.lessThanOrEqualTo(16)))
                .andExpect(jsonPath("$.reactorPipeline.sampleThreads[0]")
                        .value(matchesPattern("(loom)?[Bb]oundedElastic-\\d+")))
                .andExpect(jsonPath("$.reactorPipeline.shape")
                        .value(Matchers.containsString("flatMap concurrency 4")));
    }

    @Test
    void dropShedsTheSurplusAndALateRequestRecoversNothing() throws Exception {
        mockMvc.perform(get("/api/demo/reactive/backpressure")
                        .param("strategy", "drop").param("items", "24").param("initial", "4").param("late", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("drop"))
                .andExpect(jsonPath("$.emitted").value(24))
                .andExpect(jsonPath("$.deliveredAfterInitial").value(4))
                .andExpect(jsonPath("$.dropped").value(20))
                .andExpect(jsonPath("$.deliveredTotal").value(4))
                .andExpect(jsonPath("$.stillBuffered").value(0))
                .andExpect(jsonPath("$.reading[1]")
                        .value(Matchers.containsString("dropped values never return")));
    }

    @Test
    void bufferQueuesTheSurplusAndTheLateDemandDrainsIt() throws Exception {
        mockMvc.perform(get("/api/demo/reactive/backpressure")
                        .param("strategy", "buffer").param("items", "24").param("initial", "4").param("late", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("buffer"))
                .andExpect(jsonPath("$.deliveredAfterInitial").value(4))
                .andExpect(jsonPath("$.dropped").value(0))
                .andExpect(jsonPath("$.deliveredTotal").value(8))
                .andExpect(jsonPath("$.stillBuffered").value(16))
                .andExpect(jsonPath("$.reading[2]")
                        .value(Matchers.containsString("An unbounded buffer")));
    }

    @Test
    void theStreamEndpointIsARealFluxBridgedThroughMvcAsyncDispatch() throws Exception {
        MvcResult started = mockMvc.perform(get("/api/demo/reactive/stream")
                        .param("items", "3").param("delayMs", "0"))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(5000);
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentType(NDJSON))
                .andExpect(content().string(Matchers.containsString("{\"seq\":1,\"payload\":\"item-1\"}")))
                .andExpect(content().string(Matchers.containsString("{\"seq\":2,\"payload\":\"item-2\"}")))
                .andExpect(content().string(Matchers.containsString("{\"seq\":3,\"payload\":\"item-3\"}")));
    }

    @Test
    void theMonoEndpointWaitsForOneValueOverAFluxPipeline() throws Exception {
        MvcResult started = mockMvc.perform(get("/api/demo/reactive/sum").param("items", "48"))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(5000);
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").value(48))
                .andExpect(jsonPath("$.checksum").value(CompareExecutionModels.checksum(48)));
    }

    @Test
    void theWebClientFetchRunsTheStubbedExchangeWithoutASocket() throws Exception {
        mockMvc.perform(get("/api/demo/reactive/fetch").param("items", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("fake"))
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.fetched").value(3))
                .andExpect(jsonPath("$.reading[0]")
                        .value(Matchers.containsString("stubbed exchange function")))
                .andExpect(jsonPath("$.reading[2]")
                        .value(Matchers.containsString("collectList().block()")));
    }

    @Test
    void outOfBoundsAndUnknownInputsAreTheTypedValidationEnvelope() throws Exception {
        mockMvc.perform(get("/api/demo/reactive/compare").param("items", "9999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value(Matchers.containsString("items must be between")));
        mockMvc.perform(get("/api/demo/reactive/compare").param("items", "8").param("concurrency", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(Matchers.containsString("concurrency must be between")));
        mockMvc.perform(get("/api/demo/reactive/backpressure").param("strategy", "banana"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(Matchers.containsString("strategy must be one of")));
        mockMvc.perform(get("/api/demo/reactive/fetch").param("delayMs", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(Matchers.containsString("delayMs must be between")));
    }
}
