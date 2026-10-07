package guide.presentation.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.data.jobs.AsyncWorkService;
import guide.data.jobs.InMemoryJobQueue;
import guide.data.jobs.JobsHeartbeat;
import guide.data.jobs.RecordingEmailSender;
import guide.domain.jobs.AsyncWorkBoard;
import guide.domain.jobs.CleanupPiiJobHandler;
import guide.domain.jobs.EnqueueJob;
import guide.domain.jobs.GenerateReportJobHandler;
import guide.domain.jobs.GetJob;
import guide.domain.jobs.ListDeadLetters;
import guide.domain.jobs.ReadQueueOverview;
import guide.domain.jobs.ReplayDeadLetter;
import guide.domain.jobs.WelcomeEmailJobHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * MockMvc over the jobs console with the REAL use cases and worker wiring
 * but NO scheduler: enqueued jobs sit queued deterministically (in
 * production the worker ticker claims them — the integration test drives
 * that path), exactly like the webhooks slice proves firing performs no
 * transport. The @Async specimen IS live here (@EnableAsync on the test
 * wiring, production executor knobs) so "returns immediately, completes on
 * the executor" is asserted for real. DirtiesContext per method: the
 * console's queue and board are per-test state.
 */
@WebMvcTest(JobsDemoController.class)
@Import(JobsDemoControllerTest.FakeWiring.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class JobsDemoControllerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-06T12:00:00Z");

    /**
     * The deterministic wiring: real domain + real executor, fixed clock,
     * zero jitter, no scheduling. @TestConfiguration (not plain
     * @Configuration): a nested @Configuration would hijack the slice
     * bootstrap — every mapping 404s as a static resource.
     */
    @TestConfiguration
    @EnableAsync
    static class FakeWiring {

        @Bean
        Clock jobsClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper jobsMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        InMemoryJobQueue jobQueue() {
            return new InMemoryJobQueue();
        }

        @Bean
        RecordingEmailSender emailSender(Clock jobsClock) {
            return new RecordingEmailSender(jobsClock);
        }

        @Bean
        WelcomeEmailJobHandler welcomeEmailJobHandler(RecordingEmailSender emailSender,
                ObjectMapper jobsMapper) {
            return new WelcomeEmailJobHandler(emailSender, jobsMapper);
        }

        @Bean
        GenerateReportJobHandler generateReportJobHandler() {
            return new GenerateReportJobHandler();
        }

        @Bean
        CleanupPiiJobHandler cleanupPiiJobHandler() {
            return new CleanupPiiJobHandler();
        }

        @Bean
        JobsHeartbeat jobsHeartbeat(Clock jobsClock) {
            return new JobsHeartbeat(jobsClock);
        }

        @Bean
        AsyncWorkBoard asyncWorkBoard() {
            return new AsyncWorkBoard();
        }

        @Bean
        ThreadPoolTaskExecutor jobsDemoExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(4);
            executor.setQueueCapacity(8);
            executor.setThreadNamePrefix("jobs-demo-");
            executor.setRejectedExecutionHandler(
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
            return executor;
        }

        @Bean
        AsyncWorkService asyncWorkService(AsyncWorkBoard asyncWorkBoard, Clock jobsClock) {
            return new AsyncWorkService(asyncWorkBoard, jobsClock);
        }

        @Bean
        EnqueueJob enqueueJob(InMemoryJobQueue jobQueue, Clock jobsClock, ObjectMapper jobsMapper) {
            return new EnqueueJob(jobQueue, jobsClock, jobsMapper);
        }

        @Bean
        GetJob getJob(InMemoryJobQueue jobQueue) {
            return new GetJob(jobQueue);
        }

        @Bean
        ReadQueueOverview readQueueOverview(InMemoryJobQueue jobQueue, Clock jobsClock) {
            return new ReadQueueOverview(jobQueue, jobsClock);
        }

        @Bean
        ListDeadLetters listJobDeadLetters(InMemoryJobQueue jobQueue) {
            return new ListDeadLetters(jobQueue);
        }

        @Bean
        ReplayDeadLetter replayDeadLetter(InMemoryJobQueue jobQueue, Clock jobsClock) {
            return new ReplayDeadLetter(jobQueue, jobsClock);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private String enqueue(String type, String payload, Integer failAttempts) throws Exception {
        String body = "{\"type\":\"" + type + "\",\"payload\":" + payload + ","
                + "\"failAttempts\":" + failAttempts + "}";
        MvcResult result = mockMvc.perform(post("/api/demo/jobs/enqueue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void enqueueQueuesTheJobDeterministicallyWithoutAScheduler() throws Exception {
        mockMvc.perform(post("/api/demo/jobs/enqueue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"send-welcome-email\","
                                + "\"payload\":{\"to\":\"reader@example.com\",\"name\":\"Reader\"},"
                                + "\"failAttempts\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("queued"))
                .andExpect(jsonPath("$.attemptsLeft").value(3))
                .andExpect(jsonPath("$.attempts.length()").value(0))
                .andExpect(jsonPath("$.nextAttemptAt").value("2026-10-06T12:00:00Z"))
                .andExpect(jsonPath("$.lastError").doesNotExist());

        mockMvc.perform(get("/api/demo/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countsByState.queued").value(1))
                .andExpect(jsonPath("$.depth").value(1))
                .andExpect(jsonPath("$.oldestPendingAgeMs").value(0))
                .andExpect(jsonPath("$.oldestPendingId").isNotEmpty());
    }

    @Test
    void enqueueValidationListsTheVocabularyAndRejectsBadPayloads() throws Exception {
        mockMvc.perform(post("/api/demo/jobs/enqueue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"render-video\",\"payload\":{},\"failAttempts\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers.containsString(
                        "send-welcome-email")));
        mockMvc.perform(post("/api/demo/jobs/enqueue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"generate-report\",\"payload\":\"not json\","
                                + "\"failAttempts\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message",
                        org.hamcrest.Matchers.containsString("JSON object")));
        mockMvc.perform(post("/api/demo/jobs/enqueue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"generate-report\",\"payload\":{},\"failAttempts\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jobTraceIsANotFoundEnvelopeForUnknownIds() throws Exception {
        mockMvc.perform(get("/api/demo/jobs/jobs/job_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void deadLettersStartEmptyAndReplayRefusesTheLiving() throws Exception {
        mockMvc.perform(get("/api/demo/jobs/dead"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadLetters.length()").value(0));
        String queuedId = enqueue("cleanup-pii", "{}", 0);
        mockMvc.perform(post("/api/demo/jobs/dead/" + queuedId + "/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
    }

    @Test
    void heartbeatIsQuietWithoutSchedulingEvidenceOfSliceDeterminism() throws Exception {
        mockMvc.perform(get("/api/demo/jobs/heartbeat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticks.length()").value(0))
                .andExpect(jsonPath("$.totalTicks").value(0));
    }

    @Test
    void emailsStartEmpty() throws Exception {
        mockMvc.perform(get("/api/demo/jobs/emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void asyncReturnsImmediatelyThenCompletesOnAnExecutorThread() throws Exception {
        long before = System.nanoTime();
        MvcResult result = mockMvc.perform(post("/api/demo/jobs/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delayMs\":300}"))
                .andExpect(status().isOk())
                .andReturn();
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;
        String body = result.getResponse().getContentAsString();
        assertThat(elapsedMs).as("the proxy submits, the request returns").isLessThan(250L);
        assertThat(body).contains("\"state\":\"pending\"");
        String ticketId = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        // bounded window: the executor finishes while we watch the board
        String threadName = null;
        for (int poll = 0; poll < 60 && threadName == null; poll++) {
            MvcResult tickets = mockMvc.perform(get("/api/demo/jobs/async")).andReturn();
            List<?> rows = com.jayway.jsonpath.JsonPath.read(tickets.getResponse()
                    .getContentAsString(), "$[*]");
            for (Object row : rows) {
                if (((java.util.Map<?, ?>) row).get("id").equals(ticketId)
                        && "done".equals(((java.util.Map<?, ?>) row).get("state"))) {
                    threadName = String.valueOf(((java.util.Map<?, ?>) row).get("threadName"));
                }
            }
            if (threadName == null) {
                Thread.sleep(50);
            }
        }
        assertThat(threadName).as("the ticket completes within the bounded window").isNotNull();
        assertThat(threadName).as("the work ran on the pool, not the request thread")
                .startsWith("jobs-demo-");
    }

    @Test
    void asyncValidatesTheDelayRange() throws Exception {
        mockMvc.perform(post("/api/demo/jobs/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delayMs\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/demo/jobs/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delayMs\":20000}"))
                .andExpect(status().isBadRequest());
    }
}
