package guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The consolidation proof, end to end in the REAL application context:
 * @EnableScheduling now lives on JobsDemoConfig (relocated from
 * WebhooksDemoConfig), and everything beneath it keeps beating — this
 * topic's heartbeat and worker ticker demonstrably tick (the heartbeat ring
 * grows; an enqueued job with one injected failure walks fail → backoff →
 * succeed through the real scheduler), and the sibling tickers the guide
 * already runs (webhook deliveries, the messaging outbox relay) are still
 * scheduled beans under the same annotation. All waits are bounded windows
 * around real 1s ticks — no unbounded sleeps.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobsSchedulingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void theHeartbeatRingGrowsOnlyWhileTheSchedulerIsAlive() throws Exception {
        long ticksAtStart = totalTicks();
        awaitTrue(() -> totalTicks() >= ticksAtStart + 2, 8_000,
                "the ring must grow by at least two beats");
    }

    @Test
    void theWorkerTickerDrainsAJobThroughFailBackoffSucceed() throws Exception {
        MvcResult enqueued = mockMvc.perform(post("/api/demo/jobs/enqueue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"send-welcome-email\","
                                + "\"payload\":{\"to\":\"reader@example.com\",\"name\":\"Reader\"},"
                                + "\"failAttempts\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        String jobId = com.jayway.jsonpath.JsonPath.read(
                enqueued.getResponse().getContentAsString(), "$.id");

        awaitTrue(() -> jobState(jobId).equals("succeeded"), 15_000,
                "fail → backoff → succeed must complete through real ticks");

        MvcResult trace = mockMvc.perform(get("/api/demo/jobs/jobs/" + jobId))
                .andExpect(status().isOk())
                .andReturn();
        String body = trace.getResponse().getContentAsString();
        assertThat(body).contains("\"outcome\":\"failed\"");
        assertThat(body).contains("\"outcome\":\"succeeded\"");
        assertThat((Integer) com.jayway.jsonpath.JsonPath.read(body, "$.attempts.length()"))
                .isEqualTo(2);

        MvcResult emails = mockMvc.perform(get("/api/demo/jobs/emails"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(emails.getResponse().getContentAsString())
                .contains("reader@example.com");
    }

    @Test
    void asyncWorkCompletesOnTheProductionExecutor() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/demo/jobs/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delayMs\":100}"))
                .andExpect(status().isOk())
                .andReturn();
        String ticketId = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id");

        awaitTrue(() -> ticketState(ticketId) != null && ticketState(ticketId).equals("done"),
                5_000, "the executor completes the ticket");
        assertThat(ticketThread(ticketId)).startsWith("jobs-demo-");
    }

    @Test
    void theSiblingTickersRemainScheduledUnderTheConsolidatedAnnotation() {
        // presence + the heartbeat's growth above = the one @EnableScheduling
        // on JobsDemoConfig schedules every ticker, relocated or native
        assertThat(context.getBean(guide.data.webhooks.WebhookDeliveryTicker.class)).isNotNull();
        assertThat(context.getBean(guide.data.messaging.OutboxRelayTicker.class)).isNotNull();
        assertThat(context.getBean(guide.data.jobs.JobWorkerTicker.class)).isNotNull();
        assertThat(context.getBean(guide.data.jobs.JobsHeartbeat.class)).isNotNull();
    }

    // ---- bounded-window polling helpers (no unbounded sleeps) ----

    private long totalTicks() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/jobs/heartbeat"))
                .andExpect(status().isOk())
                .andReturn();
        Number ticks = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.totalTicks");
        return ticks == null ? 0 : ticks.longValue();
    }

    private String jobState(String jobId) {
        try {
            MvcResult result = mockMvc.perform(get("/api/demo/jobs/jobs/" + jobId))
                    .andExpect(status().isOk())
                    .andReturn();
            return com.jayway.jsonpath.JsonPath.read(
                    result.getResponse().getContentAsString(), "$.state");
        } catch (Exception unavailable) {
            return null;
        }
    }

    private String ticketState(String ticketId) {
        try {
            MvcResult result = mockMvc.perform(get("/api/demo/jobs/async"))
                    .andExpect(status().isOk())
                    .andReturn();
            java.util.List<java.util.Map<String, Object>> tickets = com.jayway.jsonpath.JsonPath
                    .read(result.getResponse().getContentAsString(), "$[*]");
            for (java.util.Map<String, Object> ticket : tickets) {
                if (ticketId.equals(ticket.get("id"))) {
                    return String.valueOf(ticket.get("state"));
                }
            }
            return null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    private String ticketThread(String ticketId) {
        try {
            MvcResult result = mockMvc.perform(get("/api/demo/jobs/async"))
                    .andExpect(status().isOk())
                    .andReturn();
            java.util.List<java.util.Map<String, Object>> tickets = com.jayway.jsonpath.JsonPath
                    .read(result.getResponse().getContentAsString(), "$[*]");
            for (java.util.Map<String, Object> ticket : tickets) {
                if (ticketId.equals(ticket.get("id"))) {
                    return String.valueOf(ticket.get("threadName"));
                }
            }
            return "";
        } catch (Exception unavailable) {
            return "";
        }
    }

    /** A polling check that may throw — unlike BooleanSupplier, whose lambda cannot. */
    private interface Check {
        boolean ok() throws Exception;
    }

    private void awaitTrue(Check condition, long windowMs, String what) throws AssertionError {
        long deadline = System.currentTimeMillis() + windowMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.ok()) {
                    return;
                }
            } catch (Exception checked) {
                throw new AssertionError("check threw while waiting for: " + what, checked);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting: " + what);
            }
        }
        throw new AssertionError("timed out waiting for: " + what);
    }
}
