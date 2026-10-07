package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.UnavailableException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.core.scenario.ScenarioStore;
import guide.data.messaging.MsgDemoActivation;
import guide.domain.messaging.ClaimIdleEntries;
import guide.domain.messaging.ConsumeFromStream;
import guide.domain.messaging.EventStream;
import guide.domain.messaging.InspectPending;
import guide.domain.messaging.ListDeadLetters;
import guide.domain.messaging.PublishToStream;
import guide.domain.messaging.TeachingEventPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 22 (messaging) — the STREAM CONSOLE over one
 * Redis stream and one consumer group:
 *
 * - POST /api/demo/msg/stream/publish  → XADD one event ({eventType,
 *   payload}); answers the stream id the broker assigned.
 * - POST /api/demo/msg/stream/consume  → XREADGROUP (max, ack,
 *   replayPending) through the idempotent consumer, then XACK when acked;
 *   the answer carries per-delivery duplicate flags and the counters — the
 *   exactly-once-achievable receipt.
 * - GET  /api/demo/msg/stream/pending  → XPENDING: totals, per-consumer
 *   breakdown, and per-entry idle + delivery counts.
 * - POST /api/demo/msg/stream/claim    → the visibility-timeout takeover:
 *   XPENDING idle-filtered + XCLAIM (minIdleMs), reprocess or dead-letter.
 * - GET  /api/demo/msg/stream/dlq      → the dead-letter stream.
 *
 * Thin by the house rule: parse the input, hand it to a use case, map the
 * Result onto the wire. Every endpoint pays activation first (typed 503
 * while PG or Redis is down) and honors injectFailure.
 */
@Tag(name = "Messaging", description = "The messaging topic's stream demo: the consumer-group "
        + "console — publish, consume, pending, claim, dead letters.")
@RestController
@RequestMapping("/api/demo/msg/stream")
public class StreamDemoController {

    /** The publish request: any vocabulary event with an arbitrary payload. */
    record PublishRequest(String eventType, Map<String, Object> payload) {
    }

    /** The publish answer: where the event landed. */
    record PublishView(String streamId, String stream, String eventId, String eventType,
            String aggregateId) {
    }

    /** The consume answer (use-case view, verbatim — its shape IS the lesson). */
    record ConsumeView(List<guide.domain.messaging.IdempotentConsumer.Outcome> deliveries,
            long acked, boolean ackedRequested, Map<String, Long> counters, long duplicatesSeen) {
    }

    /** The DLQ window. */
    record DlqView(List<EventStream.DeadLetter> entries) {
    }

    private final MsgDemoActivation activation;
    private final PublishToStream publishToStream;
    private final ConsumeFromStream consumeFromStream;
    private final InspectPending inspectPending;
    private final ClaimIdleEntries claimIdleEntries;
    private final ListDeadLetters listDeadLetters;
    private final ScenarioStore scenarioStore;

    public StreamDemoController(MsgDemoActivation activation, PublishToStream publishToStream,
            ConsumeFromStream consumeFromStream, InspectPending inspectPending,
            ClaimIdleEntries claimIdleEntries, ListDeadLetters listDeadLetters,
            ScenarioStore scenarioStore) {
        this.activation = activation;
        this.publishToStream = publishToStream;
        this.consumeFromStream = consumeFromStream;
        this.inspectPending = inspectPending;
        this.claimIdleEntries = claimIdleEntries;
        this.listDeadLetters = listDeadLetters;
        this.scenarioStore = scenarioStore;
    }

    @Operation(summary = "XADD one event to the demo stream", operationId = "publishToStream",
            description = "Direct publish — XADD, right now, no outbox. Answers the stream id "
                    + "the broker assigned (millis-sequence, monotonic) plus the event as stored.")
    @PostMapping(value = "/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublishView> publish(@RequestBody PublishRequest request) {
        requireReady();
        Result<PublishToStream.Published> outcome =
                publishToStream.execute(request.eventType(), request.payload());
        return switch (outcome) {
            case Success<PublishToStream.Published> success -> ResponseEntity.ok(new PublishView(
                    success.value().streamId(), success.value().stream(),
                    success.value().event().eventId(), success.value().event().eventType(),
                    success.value().event().aggregateId()));
            case Failed<PublishToStream.Published> failed -> throw AppException.of(failed.failure());
        };
    }

    @Operation(summary = "XREADGROUP + idempotent process (+ XACK)", operationId = "consumeStream",
            description = "Deliver up to max entries to worker-1 — new entries by default, this "
                    + "consumer's un-acked ones with replayPending=true (the restart read). Each "
                    + "delivery runs through the idempotent consumer (duplicate flag per entry); "
                    + "ack=true XACKs what it processed, ack=false stages the crash window.")
    @PostMapping(value = "/consume", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ConsumeView> consume(@RequestParam(defaultValue = "10") int max,
            @RequestParam(defaultValue = "true") boolean ack,
            @RequestParam(defaultValue = "false") boolean replayPending) {
        requireReady();
        ConsumeFromStream.ConsumeView view = consumeFromStream.execute(max, ack, replayPending);
        return ResponseEntity.ok(new ConsumeView(view.deliveries(), view.acked(),
                view.ackedRequested(), view.counters(), view.duplicatesSeen()));
    }

    @Operation(summary = "XPENDING — what is delivered and not acked",
            operationId = "streamPendingEntries",
            description = "The pending-entries list: totals, per-consumer breakdown, and each "
                    + "entry's idle time and delivery count — the crash-window ledger.")
    @GetMapping(value = "/pending", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EventStream.PendingView> pending() {
        requireReady();
        return ResponseEntity.ok(inspectPending.execute());
    }

    @Operation(summary = "Claim idle entries (XPENDING + XCLAIM)", operationId = "claimIdleEntries",
            description = "The visibility-timeout takeover: entries idle at least minIdleMs move "
                    + "to worker-2 (each claim is one more delivery). Reprocessable entries go "
                    + "through the idempotent consumer and ack; entries past the delivery "
                    + "threshold are dead-lettered to the DLQ stream and acked out of the group.")
    @PostMapping(value = "/claim", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ClaimIdleEntries.ClaimView> claim(
            @RequestParam(defaultValue = "0") long minIdleMs,
            @RequestParam(defaultValue = "true") boolean ack) {
        requireReady();
        return ResponseEntity.ok(claimIdleEntries.execute(minIdleMs, ack));
    }

    @Operation(summary = "The dead-letter stream", operationId = "streamDeadLetters",
            description = "Poison entries parked by claim (delivery threshold) and by the outbox "
                    + "relay (attempt threshold) — a work queue for operators, newest last.")
    @GetMapping(value = "/dlq", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DlqView> dlq(@RequestParam(defaultValue = "50") int limit) {
        requireReady();
        return ResponseEntity.ok(new DlqView(listDeadLetters.execute(limit)));
    }

    // ---- edge helpers ----

    /** Activation first on every endpoint: typed 503 while infra is down. */
    private void requireReady() {
        if (scenarioStore.get().injectFailure()) {
            throw new UnavailableException(
                    "messaging demos are unavailable by scenario (injectFailure=true)",
                    Map.of("area", TeachingEventPublisher.AREA));
        }
        activation.ensureReady();
    }
}
