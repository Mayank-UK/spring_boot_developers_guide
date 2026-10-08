package guide.presentation.realtime;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.realtime.TickerFeed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * Topic 28's SSE surface: GET /api/demo/realtime/ticker — one SseEmitter per
 * subscriber, fed by the shared {@link TickerFeed}. The lifecycle rules this
 * controller demonstrates (and the topic quotes):
 *
 * - SseEmitter HANDS THE RESPONSE BACK: nothing may be sent before the
 *   controller method returns (send on an uninitialized emitter throws) —
 *   so the opening comment, the retry hint, and any Last-Event-ID replay
 *   are QUEUED in the listener and flushed on the first tick delivery.
 * - ALL THREE terminal callbacks (onCompletion, onTimeout, onError)
 *   funnel into one detach(): unsubscribe from the feed, decrement the
 *   emitter gauge. A stream that ends any way at all must not leave a
 *   ghost listener keeping the feed awake.
 * - {@code limit} bounds a subscription (complete after N new ticks) — the
 *   honest way to demo a stream that ends, and the trigger that lets a
 *   browser EventSource show its auto-reconnect.
 *
 * The emitter timeout is explicit (2 minutes): the async request behind an
 * SseEmitter is NOT immortal — the container will time it out and fire
 * onTimeout, and a server that ignores that callback leaks both the emitter
 * and its listener.
 */
@Tag(name = "Realtime", description = "Topic 28's live surfaces: the SSE ticker stream and its observability.")
@RestController
@RequestMapping("/api/demo/realtime")
public class SseTickerController {

    /** Async timeout for one subscription: generous for a demo, finite by principle. */
    static final long EMITTER_TIMEOUT_MILLIS = 120_000;

    /** The reconnect hint EventSource should honor after a dropped stream. */
    static final long RECONNECT_HINT_MILLIS = 3_000;

    /** A comment-only event every N ticks — the heartbeat that keeps proxies from idling us out. */
    static final int HEARTBEAT_EVERY_TICKS = 5;

    /** Defensive cap on limit: a query parameter is hostile until bounded. */
    static final int MAX_LIMIT = 1_000;

    private final TickerFeed tickerFeed;
    private final ObjectMapper objectMapper;
    private final AtomicInteger activeEmitters = new AtomicInteger();

    public SseTickerController(TickerFeed tickerFeed, ObjectMapper objectMapper) {
        this.tickerFeed = tickerFeed;
        this.objectMapper = objectMapper;
    }

    /** The emitter gauge the /ticker/state endpoint reports — the observability half of the demo. */
    public int activeEmittersCount() {
        return activeEmitters.get();
    }

    /**
     * The stream. {@code limit=0} (default) subscribes forever; a positive
     * limit completes the emitter after that many NEW ticks (replayed
     * history does not count toward it). Last-Event-ID is the resume
     * contract: EventSource sends it automatically on reconnect, carrying
     * the id of the last event it saw — we answer with every ring tick
     * after it.
     */
    @Operation(summary = "The live price ticker over SSE", operationId = "tickerStream",
            description = "text/event-stream: named 'tick' events with monotonic ids, a retry hint, "
                    + "comment heartbeats, Last-Event-ID replay, and full emitter cleanup on "
                    + "completion/timeout/error. limit=N completes the stream after N ticks.")
    @GetMapping(value = "/ticker", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter ticker(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(defaultValue = "0") int limit) {
        if (limit < 0 || limit > MAX_LIMIT) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "limit must be between 0 (endless) and " + MAX_LIMIT, Map.of("limit", limit)));
        }
        long resumeAfter = parseLastEventId(lastEventId);

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        List<TickerFeed.Tick> missed = tickerFeed.replayAfter(resumeAfter);
        StreamListener listener = new StreamListener(emitter, missed, limit);
        activeEmitters.incrementAndGet();
        // RACE NOTE: the pump may deliver the first tick the instant subscribe
        // returns — before holdUnsubscribe below runs. holdUnsubscribe closes
        // that window: if detach() already fired, it runs the real
        // unsubscription itself, so no early death can strand a ghost listener.
        Runnable unsubscribe = tickerFeed.subscribe(listener::onTick);
        listener.holdUnsubscribe(unsubscribe);
        emitter.onCompletion(listener::detach);
        emitter.onTimeout(listener::detach);
        emitter.onError(error -> listener.detach());
        return emitter;
    }

    private static long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException notNumeric) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "Last-Event-ID must be the numeric tick id the stream issued",
                    Map.of("lastEventId", lastEventId)));
        }
    }

    /**
     * One subscriber's whole life: flush the deferred preamble (opening
     * comment + retry hint + Last-Event-ID replay) on the first delivery,
     * then named tick events with ids, a heartbeat comment every
     * HEARTBEAT_EVERY_TICKS, and completion at the limit. Every terminal
     * path — client gone (send throws), limit reached, container timeout —
     * lands in detach(), exactly once.
     */
    private final class StreamListener {
        private final SseEmitter emitter;
        private final List<TickerFeed.Tick> replay;
        private final int limit;
        private volatile Runnable unsubscribe = () -> { };
        private boolean preambleSent;
        private boolean detached;
        private int newTicksSent;

        StreamListener(SseEmitter emitter, List<TickerFeed.Tick> replay, int limit) {
            this.emitter = emitter;
            this.replay = replay;
            this.limit = limit;
        }

        /** Wire the real unsubscribe; if detach() won the subscribe race, finish it now. */
        synchronized void holdUnsubscribe(Runnable unsubscribe) {
            this.unsubscribe = unsubscribe;
            if (detached) {
                unsubscribe.run();
            }
        }

        synchronized void onTick(TickerFeed.Tick tick) {
            if (detached) {
                return;
            }
            try {
                if (!preambleSent) {
                    emitter.send(SseEmitter.event()
                            .comment("ticker stream open — retry hint follows; comments are heartbeats")
                            .reconnectTime(RECONNECT_HINT_MILLIS));
                    for (TickerFeed.Tick missed : replay) {
                        emitter.send(tickEvent(missed));
                    }
                    preambleSent = true;
                }
                emitter.send(tickEvent(tick));
                newTicksSent++;
                if (newTicksSent % HEARTBEAT_EVERY_TICKS == 0) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                }
                if (limit > 0 && newTicksSent >= limit) {
                    detach();
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException clientGone) {
                detach();
            }
        }

        /** The one exit: unsubscribe + gauge down. Idempotent by flag. */
        synchronized void detach() {
            if (detached) {
                return;
            }
            detached = true;
            unsubscribe.run();
            activeEmitters.decrementAndGet();
        }

        private SseEmitter.SseEventBuilder tickEvent(TickerFeed.Tick tick) {
            return SseEmitter.event()
                    .id(Long.toString(tick.seq()))
                    .name("tick")
                    .data(objectMapper.writeValueAsString(tick), MediaType.APPLICATION_JSON);
        }
    }
}
