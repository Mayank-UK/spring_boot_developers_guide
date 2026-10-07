package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.patterns.EventStore;
import guide.domain.patterns.OrderSagas;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 44 (advanced-architecture) — the SAGA and EVENT
 * STORE surfaces under the arch: area (topic 32's tracer lives here too).
 * Deterministic and offline by construction: sequential ids, no clock, no
 * randomness — every answer depends only on the calls made since reset.
 *
 * Saga (arch:saga):
 * - POST /api/demo/arch/saga/start?items=&amountCents=&failAtStep= → run one saga
 * - GET  /api/demo/arch/saga/{sagaId} → one saga's trail (audit read-back)
 * - GET  /api/demo/arch/saga/recent → recent sagas + the world's current state
 * - POST /api/demo/arch/saga/reset → constructed state (tour + test determinism)
 *
 * Event store (arch:eventstore):
 * - POST /api/demo/arch/eventstore/append?stream=&type=&item=&priceCents=
 * - GET  /api/demo/arch/eventstore/stream/{streamId} → the raw log
 * - GET  /api/demo/arch/eventstore/state/{streamId} → the replayed projection
 * - GET  /api/demo/arch/eventstore/streams → every stream the store owns
 * - POST /api/demo/arch/eventstore/reset → the empty log
 *
 * Thin as always: parse and validate at the edge, one engine call per
 * endpoint, typed envelopes for bad input — the domain's own guards are the
 * second wall, never the first.
 */
@RestController
public class PatternsDemoController {

    private final OrderSagas orderSagas;
    private final EventStore eventStore;

    public PatternsDemoController(OrderSagas orderSagas, EventStore eventStore) {
        this.orderSagas = orderSagas;
        this.eventStore = eventStore;
    }

    // ---- the saga surface -------------------------------------------------------

    /** POST /api/demo/arch/saga/start?items=2&amountCents=1999&failAtStep=0. */
    @PostMapping(value = "/api/demo/arch/saga/start", produces = MediaType.APPLICATION_JSON_VALUE)
    OrderSagas.SagaReport startSaga(
            @RequestParam(name = "items", defaultValue = "2") int items,
            @RequestParam(name = "amountCents", defaultValue = "1999") long amountCents,
            @RequestParam(name = "failAtStep", defaultValue = "0") int failAtStep) {
        requireInRange(items, OrderSagas.MIN_ITEMS, OrderSagas.MAX_ITEMS, "items");
        requireInRange(amountCents, OrderSagas.MIN_AMOUNT_CENTS, OrderSagas.MAX_AMOUNT_CENTS, "amountCents");
        requireInRange(failAtStep, 0, OrderSagas.MAX_FAIL_AT_STEP, "failAtStep");
        return orderSagas.start(items, amountCents, failAtStep);
    }

    /** GET /api/demo/arch/saga/saga-1 → the trail as it was recorded. */
    @GetMapping(value = "/api/demo/arch/saga/{sagaId}", produces = MediaType.APPLICATION_JSON_VALUE)
    OrderSagas.SagaReport sagaTrail(@PathVariable String sagaId) {
        return orderSagas.trail(sagaId);
    }

    /** GET /api/demo/arch/saga/recent → newest sagas first, plus the world now. */
    @GetMapping(value = "/api/demo/arch/saga/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    SagaOverview recentSagas() {
        return new SagaOverview(orderSagas.recent(), orderSagas.world());
    }

    /** POST /api/demo/arch/saga/reset → fresh world, no history. */
    @PostMapping(value = "/api/demo/arch/saga/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    SagaOverview resetSagas() {
        orderSagas.reset();
        return recentSagas();
    }

    // ---- the event-store surface ------------------------------------------------

    /**
     * POST /api/demo/arch/eventstore/append?stream=cart-1&type=cart.item-added&item=mug&priceCents=1200
     * → the stored fact plus the projection AFTER folding it in.
     */
    @PostMapping(value = "/api/demo/arch/eventstore/append", produces = MediaType.APPLICATION_JSON_VALUE)
    AppendAnswer append(
            @RequestParam(name = "stream", defaultValue = "cart-1") String stream,
            @RequestParam(name = "type") String type,
            @RequestParam(name = "item", required = false) String item,
            @RequestParam(name = "priceCents", required = false) Long priceCents) {
        if (!EventStore.vocabulary().contains(type)) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "type must be one of " + String.join(", ", EventStore.vocabulary()),
                    Map.of("parameter", "type", "allowed", String.join(", ", EventStore.vocabulary()))));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (type) {
            case EventStore.ITEM_ADDED -> {
                requireNonBlank(item, "item");
                requireInRange(priceCents, 1, 1_000_000, "priceCents");
                payload.put("item", item);
                payload.put("priceCents", priceCents);
            }
            case EventStore.ITEM_REMOVED -> {
                requireNonBlank(item, "item");
                payload.put("item", item);
            }
            case EventStore.CHECKED_OUT -> {
                if (item != null || priceCents != null) {
                    throw AppException.of(Failure.of(FailureKind.VALIDATION,
                            "checked-out carries no item or priceCents — it is a bare fact",
                            Map.of("parameter", "type")));
                }
            }
            default -> throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "type must be one of " + String.join(", ", EventStore.vocabulary()), null));
        }
        EventStore.StoredEvent event = eventStore.append(stream, type, payload);
        return new AppendAnswer(event, eventStore.replay(stream));
    }

    /** GET /api/demo/arch/eventstore/stream/cart-1 → the raw log, nothing derived. */
    @GetMapping(value = "/api/demo/arch/eventstore/stream/{streamId}", produces = MediaType.APPLICATION_JSON_VALUE)
    StreamAnswer stream(@PathVariable String streamId) {
        return new StreamAnswer(streamId, eventStore.stream(streamId));
    }

    /** GET /api/demo/arch/eventstore/state/cart-1 → the projection, replayed on read. */
    @GetMapping(value = "/api/demo/arch/eventstore/state/{streamId}", produces = MediaType.APPLICATION_JSON_VALUE)
    EventStore.Projection state(@PathVariable String streamId) {
        return eventStore.replay(streamId);
    }

    /** GET /api/demo/arch/eventstore/streams → the store's whole inventory. */
    @GetMapping(value = "/api/demo/arch/eventstore/streams", produces = MediaType.APPLICATION_JSON_VALUE)
    StreamsAnswer streams() {
        return new StreamsAnswer(eventStore.streams());
    }

    /** POST /api/demo/arch/eventstore/reset → the empty log. */
    @PostMapping(value = "/api/demo/arch/eventstore/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    StreamsAnswer resetStore() {
        eventStore.reset();
        return streams();
    }

    // ---- edge validation (shared) -----------------------------------------------

    private static void requireInRange(long value, long min, long max, String parameter) {
        if (value < min || value > max) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    parameter + " must be between " + min + " and " + max,
                    Map.of("parameter", parameter)));
        }
    }

    /** Boxed variant: a missing parameter is a validation failure, never an NPE. */
    private static void requireInRange(Long value, long min, long max, String parameter) {
        if (value == null) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    parameter + " is required for this event type",
                    Map.of("parameter", parameter)));
        }
        requireInRange(value.longValue(), min, max, parameter);
    }

    private static void requireNonBlank(String value, String parameter) {
        if (value == null || value.isBlank()) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    parameter + " is required for this event type",
                    Map.of("parameter", parameter)));
        }
    }

    // ---- answer records ----------------------------------------------------------

    /** Recent sagas plus the world they share — one fetch paints the whole panel. */
    public record SagaOverview(List<OrderSagas.SagaReport> sagas, OrderSagas.WorldView world) {
    }

    /** What one append did: the stored fact, and the state after folding it in. */
    public record AppendAnswer(EventStore.StoredEvent event, EventStore.Projection after) {
    }

    /** The raw history: stream id plus the events in append order. */
    public record StreamAnswer(String streamId, List<EventStore.StoredEvent> events) {
    }

    /** The inventory: every stream, its event count, whether checkout sealed it. */
    public record StreamsAnswer(List<EventStore.StreamSummary> streams) {
    }
}
