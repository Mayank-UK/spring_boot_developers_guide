package guide.presentation.demo;

import guide.domain.realtime.TickerFeed;
import guide.presentation.realtime.ChatWebSocketHandler;
import guide.presentation.realtime.ChatWebSocketHandler.RoomOccupancy;
import guide.presentation.realtime.SseTickerController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The realtime demos' observability endpoints (topic 28): two GETs that
 * answer what the live surfaces are doing RIGHT NOW —
 *
 * - GET /api/demo/realtime/ticker/state → {activeEmitters, ticksSent}:
 *   how many SSE subscribers hold the feed awake, and how many ticks it
 *   has generated (generation requires listeners, so this counter moving
 *   with activeEmitters=0 would be a bug — the pause is the invariant).
 * - GET /api/demo/realtime/chat/state → {rooms: [{name, sessions}]}:
 *   per-room occupancy, both rooms always listed.
 *
 * Thin like every demo controller: read-only views over the demo beans,
 * no logic to hide. The terminal companion curls both while a stream runs.
 */
@Tag(name = "Realtime", description = "Topic 28's observability: ticker emitter gauge and chat room occupancy.")
@RestController
@RequestMapping("/api/demo/realtime")
public class RealtimeDemoController {

    /** The ticker feed's health: live subscriber count + lifetime tick count. */
    record TickerStateView(int activeEmitters, long ticksSent) {
    }

    /** The chat rooms' occupancy: both rooms, always, with live member counts. */
    record ChatStateView(List<RoomOccupancy> rooms) {
    }

    private final TickerFeed tickerFeed;
    private final SseTickerController sseTickerController;
    private final ChatWebSocketHandler chatWebSocketHandler;

    public RealtimeDemoController(TickerFeed tickerFeed, SseTickerController sseTickerController,
            ChatWebSocketHandler chatWebSocketHandler) {
        this.tickerFeed = tickerFeed;
        this.sseTickerController = sseTickerController;
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @Operation(summary = "Ticker stream observability", operationId = "tickerStreamState",
            description = "activeEmitters: how many SseEmitters hold the feed awake (the feed pauses "
                    + "at zero). ticksSent: total ticks generated — every one of them to a live listener.")
    @GetMapping(value = "/ticker/state", produces = "application/json")
    TickerStateView tickerState() {
        return new TickerStateView(sseTickerController.activeEmittersCount(),
                tickerFeed.ticksGenerated());
    }

    @Operation(summary = "Chat room occupancy", operationId = "chatRoomState",
            description = "Every room in the closed vocabulary with its live member count — "
                    + "rooms with zero members are listed too, because 'empty' is information.")
    @GetMapping(value = "/chat/state", produces = "application/json")
    ChatStateView chatState() {
        return new ChatStateView(chatWebSocketHandler.occupancy());
    }
}
