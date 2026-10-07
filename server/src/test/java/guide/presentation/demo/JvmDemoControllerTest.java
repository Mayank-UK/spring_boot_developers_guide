package guide.presentation.demo;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.jvm.CompareThreadModels;
import guide.domain.jvm.RunStreamingDemo;
import guide.domain.jvm.RunThreadsDemo;
import guide.domain.jvm.StreamingExhibit;
import guide.domain.jvm.ThreadsExhibit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the jvm demo endpoints: happy paths for all three endpoints
 * plus the 400 envelope for every invalid parameter shape — non-numeric,
 * out-of-range, unknown mode, unknown charset, below-minimum chunks — the
 * full Result → HTTP mapping through the real advice.
 */
@WebMvcTest(JvmDemoController.class)
class JvmDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RunThreadsDemo runThreadsDemo;

    @MockitoBean
    private CompareThreadModels compareThreadModels;

    @MockitoBean
    private RunStreamingDemo runStreamingDemo;

    private static ThreadsExhibit.RunReport run(String mode, int tasks, int distinct) {
        return new ThreadsExhibit.RunReport(mode, tasks, 42L, distinct,
                List.of("Thread[pool-1-thread-1,5,main]"), false, false, null);
    }

    @Test
    void threadsServesOnePlatformRun() throws Exception {
        when(runThreadsDemo.execute("24", "platform"))
                .thenReturn(Result.success(run("platform", 24, 4)));

        mockMvc.perform(get("/api/demo/jvm/threads")
                        .param("tasks", "24").param("mode", "platform"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("platform"))
                .andExpect(jsonPath("$.tasks").value(24))
                .andExpect(jsonPath("$.wallTimeMillis").value(42))
                .andExpect(jsonPath("$.distinctThreadNames").value(4))
                .andExpect(jsonPath("$.sampleThreadNames[0]").value("Thread[pool-1-thread-1,5,main]"))
                .andExpect(jsonPath("$.carrierObserved").value(false));
    }

    @Test
    void threadsServesAVirtualRunWithCarrierEvidence() throws Exception {
        ThreadsExhibit.RunReport report = new ThreadsExhibit.RunReport("virtual", 40, 12L, 40,
                List.of("VirtualThread[#77]/runnable@ForkJoinPool-1-worker-2"), true, true,
                "VirtualThread[#77]/runnable@ForkJoinPool-1-worker-2");
        when(runThreadsDemo.execute("40", "virtual")).thenReturn(Result.success(report));

        mockMvc.perform(get("/api/demo/jvm/threads")
                        .param("tasks", "40").param("mode", "virtual"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("virtual"))
                .andExpect(jsonPath("$.distinctThreadNames").value(40))
                .andExpect(jsonPath("$.virtualThreadsObserved").value(true))
                .andExpect(jsonPath("$.carrierObserved").value(true))
                .andExpect(jsonPath("$.carrierSample").value(
                        "VirtualThread[#77]/runnable@ForkJoinPool-1-worker-2"));
    }

    @Test
    void compareServesBothModelsSideBySide() throws Exception {
        ThreadsExhibit.ComparisonReport comparison = new ThreadsExhibit.ComparisonReport(24,
                run("platform", 24, 4),
                run("virtual", 24, 24),
                "24 blocking tasks: the pool of 4 ran them in ~6 waves");
        when(compareThreadModels.execute("24")).thenReturn(Result.success(comparison));

        mockMvc.perform(get("/api/demo/jvm/threads/compare").param("tasks", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks").value(24))
                .andExpect(jsonPath("$.platform.mode").value("platform"))
                .andExpect(jsonPath("$.platform.distinctThreadNames").value(4))
                .andExpect(jsonPath("$.virtual.mode").value("virtual"))
                .andExpect(jsonPath("$.virtual.distinctThreadNames").value(24))
                .andExpect(jsonPath("$.summary").value("24 blocking tasks: the pool of 4 ran them in ~6 waves"));
    }

    @Test
    void unknownModeYieldsThe400EnvelopeNamingValidValues() throws Exception {
        when(runThreadsDemo.execute("8", "event-loop")).thenReturn(Result.failed(
                Failure.of(FailureKind.VALIDATION,
                        "mode must be one of: platform, virtual",
                        Map.of("parameter", "mode", "validValues", RunThreadsDemo.MODES))));

        mockMvc.perform(get("/api/demo/jvm/threads")
                        .param("tasks", "8").param("mode", "event-loop"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value("mode must be one of: platform, virtual"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.parameter").value("mode"))
                .andExpect(jsonPath("$.error.validValues[0]").value("platform"))
                .andExpect(jsonPath("$.error.validValues[1]").value("virtual"));
    }

    @Test
    void missingModeFlowsThroughTheSameValidationPath() throws Exception {
        when(runThreadsDemo.execute("8", null)).thenReturn(Result.failed(
                Failure.of(FailureKind.VALIDATION, "mode must be one of: platform, virtual",
                        Map.of("parameter", "mode", "validValues", RunThreadsDemo.MODES))));

        mockMvc.perform(get("/api/demo/jvm/threads").param("tasks", "8"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.parameter").value("mode"));
    }

    @Test
    void nonNumericTasksYieldThe400Envelope() throws Exception {
        when(runThreadsDemo.execute("abc", "virtual")).thenReturn(Result.failed(
                Failure.of(FailureKind.VALIDATION,
                        "tasks must be an integer between 1 and 100",
                        Map.of("parameter", "tasks", "min", 1, "max", ThreadsExhibit.MAX_TASKS))));

        mockMvc.perform(get("/api/demo/jvm/threads")
                        .param("tasks", "abc").param("mode", "virtual"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value("tasks must be an integer between 1 and 100"))
                .andExpect(jsonPath("$.error.parameter").value("tasks"))
                .andExpect(jsonPath("$.error.max").value(100));
    }

    @Test
    void outOfRangeTasksYieldThe400Envelope() throws Exception {
        when(compareThreadModels.execute("101")).thenReturn(Result.failed(
                Failure.of(FailureKind.VALIDATION,
                        "tasks must be an integer between 1 and 100",
                        Map.of("parameter", "tasks", "min", 1, "max", ThreadsExhibit.MAX_TASKS))));

        mockMvc.perform(get("/api/demo/jvm/threads/compare").param("tasks", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.parameter").value("tasks"));
    }

    @Test
    void streamingServesTheFourConsumerReport() throws Exception {
        StreamingExhibit.StreamingReport report = new StreamingExhibit.StreamingReport(16, "utf-8", 8,
                144, 112, new StreamingExhibit.ByteScan(144, 18),
                new StreamingExhibit.ReaderScan(112, 16, 0),
                new StreamingExhibit.NaiveScan("c00000\uFFFD\uFFFDc00001\u2192", 4),
                new StreamingExhibit.ChannelScan(144, 18), true, true);
        when(runStreamingDemo.execute("16", "utf-8")).thenReturn(Result.success(report));

        mockMvc.perform(get("/api/demo/jvm/streaming")
                        .param("chunks", "16").param("charset", "utf-8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunks").value(16))
                .andExpect(jsonPath("$.charset").value("utf-8"))
                .andExpect(jsonPath("$.payloadBytes").value(144))
                .andExpect(jsonPath("$.streamScan.totalBytes").value(144))
                .andExpect(jsonPath("$.readerScan.totalChars").value(112))
                .andExpect(jsonPath("$.readerScan.replacementChars").value(0))
                .andExpect(jsonPath("$.naiveScan.replacementChars").value(4))
                .andExpect(jsonPath("$.channelScan.totalBytes").value(144))
                .andExpect(jsonPath("$.readerClean").value(true))
                .andExpect(jsonPath("$.naiveCorrupted").value(true));
    }

    @Test
    void unknownCharsetYieldsThe400EnvelopeNamingValidValues() throws Exception {
        when(runStreamingDemo.execute(null, "latin-1")).thenReturn(Result.failed(
                Failure.of(FailureKind.VALIDATION,
                        "charset must be one of: utf-8, utf-16",
                        Map.of("parameter", "charset", "validValues",
                                StreamingExhibit.SUPPORTED_CHARSETS))));

        mockMvc.perform(get("/api/demo/jvm/streaming").param("charset", "latin-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value("charset must be one of: utf-8, utf-16"))
                .andExpect(jsonPath("$.error.parameter").value("charset"))
                .andExpect(jsonPath("$.error.validValues[1]").value("utf-16"));
    }

    @Test
    void belowMinimumChunksYieldThe400Envelope() throws Exception {
        when(runStreamingDemo.execute("2", null)).thenReturn(Result.failed(
                Failure.of(FailureKind.VALIDATION,
                        "chunks must be an integer between 4 and 64",
                        Map.of("parameter", "chunks", "min", StreamingExhibit.MIN_CHUNKS,
                                "max", StreamingExhibit.MAX_CHUNKS))));

        mockMvc.perform(get("/api/demo/jvm/streaming").param("chunks", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.parameter").value("chunks"))
                .andExpect(jsonPath("$.error.min").value(4));
    }
}
