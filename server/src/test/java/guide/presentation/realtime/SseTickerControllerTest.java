package guide.presentation.realtime;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.RealtimeDemoConfig;
import guide.domain.realtime.TickerFeed;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The ticker stream through MockMvc's REAL async dispatch — the same
 * machinery the reqres streaming demo uses. The slice has no scheduler
 * (nothing processes @Scheduled in a @WebMvcTest), which is the design:
 * the test IS the pump, driving tickerFeed.tick(...) with constructed
 * instants while the emitter hangs open. What is asserted:
 *
 * - the stream is text/event-stream carrying named events with ids, a
 *   retry hint, comment heartbeats, and JSON data frames;
 * - the emitter gauge: 1 while the stream is open, 0 after it completes —
 *   the cleanup contract (no ghost listener keeps the feed awake);
 * - Last-Event-ID: a reconnect presenting the id it last saw gets the
 *   missed ticks replayed before the new ones;
 * - hostile parameters (limit, Last-Event-ID) get the typed 400 envelope.
 */
@WebMvcTest(controllers = {SseTickerController.class, guide.presentation.demo.RealtimeDemoController.class})
@Import(RealtimeDemoConfig.class)
class SseTickerControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TickerFeed tickerFeed;

    @Test
    void streamCarriesNamedEventsIdsRetryAndHeartbeatsThenCleansUp() throws Exception {
        long ticksBefore = tickerFeed.ticksGenerated();

        MvcResult started = mockMvc.perform(get("/api/demo/realtime/ticker")
                        .queryParam("limit", "7")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        // while the stream is open: exactly one emitter holds the feed awake
        mockMvc.perform(get("/api/demo/realtime/ticker/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeEmitters").value(1))
                .andExpect(jsonPath("$.ticksSent").value((int) ticksBefore));

        for (int step = 1; step <= 7; step++) {
            tickerFeed.tick(T0.plusMillis(step * 500L));
        }
        started.getAsyncResult(5000); // the limit completed the emitter

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(":ticker stream open")))
                .andExpect(content().string(containsString("retry:3000")))
                .andExpect(content().string(containsString("event:tick")))
                .andExpect(content().string(containsString("id:1")))
                .andExpect(content().string(containsString("id:7")))
                .andExpect(content().string(containsString("\"seq\":7")))
                .andExpect(content().string(containsString("\"symbol\":\"SDG\"")))
                .andExpect(content().string(containsString(":heartbeat")));

        // after completion: the gauge is back to zero and the seven ticks are on the record
        mockMvc.perform(get("/api/demo/realtime/ticker/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeEmitters").value(0))
                .andExpect(jsonPath("$.ticksSent").value((int) (ticksBefore + 7)));
    }

    @Test
    void lastEventIdReplaysTheMissedTicksBeforeTheNewOnes() throws Exception {
        // first subscription: three ticks, then complete
        MvcResult first = mockMvc.perform(get("/api/demo/realtime/ticker")
                        .queryParam("limit", "3")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        long afterFirst = -1;
        for (int step = 0; step < 3; step++) {
            tickerFeed.tick(T0.plusSeconds(step));
            afterFirst = tickerFeed.latestSeq();
        }
        first.getAsyncResult(5000);
        mockMvc.perform(asyncDispatch(first)).andExpect(status().isOk());

        // reconnect: presenting the id of the FIRST tick only — the stream must
        // replay ticks 2..3 (the ring) and then deliver the new one
        MvcResult resumed = mockMvc.perform(get("/api/demo/realtime/ticker")
                        .queryParam("limit", "1")
                        .header("Last-Event-ID", Long.toString(afterFirst - 2))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        tickerFeed.tick(T0.plusSeconds(30));
        resumed.getAsyncResult(5000);

        long newest = tickerFeed.latestSeq();
        mockMvc.perform(asyncDispatch(resumed))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id:" + (afterFirst - 1))))
                .andExpect(content().string(containsString("id:" + afterFirst)))
                .andExpect(content().string(containsString("id:" + newest)))
                .andExpect(content().string(containsString("event:tick")));

        // and both subscriptions cleaned up after themselves
        mockMvc.perform(get("/api/demo/realtime/ticker/state"))
                .andExpect(jsonPath("$.activeEmitters").value(0));
    }

    @Test
    void hostileParametersGetTheTypedValidationEnvelope() throws Exception {
        // no event-stream Accept here on purpose: the error answer must render
        // as the JSON envelope, and a text/event-stream-only Accept would make
        // the error response negotiate against the wrong media type
        mockMvc.perform(get("/api/demo/realtime/ticker").queryParam("limit", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));

        mockMvc.perform(get("/api/demo/realtime/ticker")
                        .queryParam("limit", String.valueOf(SseTickerController.MAX_LIMIT + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));

        mockMvc.perform(get("/api/demo/realtime/ticker").header("Last-Event-ID", "not-a-tick-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }
}
