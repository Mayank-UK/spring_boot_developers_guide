package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.UnavailableException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.core.scenario.ScenarioStore;
import guide.data.messaging.MsgDemoActivation;
import guide.domain.messaging.OutboxRelay;
import guide.domain.messaging.PublishPostEvent;
import guide.domain.messaging.ReadOutboxState;
import guide.domain.messaging.TeachingEventPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
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
 * Demo endpoints for topic 22 (messaging) — the TRANSACTIONAL OUTBOX:
 *
 * - POST /api/demo/msg/outbox/transactional → the proof orchestrator. The
 *   posts mutation (publish) and — on the outbox path — the event append
 *   ride ONE transaction; the crash flags demonstrate both windows:
 *   failBeforeCommit rolls everything back (no post change, no event);
 *   failAfterPublish crashes after commit — the outbox row survives and
 *   the relay delivers it, while the DIRECT publisher in the same spot
 *   loses the event forever (the dual-write window, live).
 * - POST /api/demo/msg/outbox/relay → one relay batch: claim (SKIP LOCKED,
 *   single-flight), XADD, mark processed; poison rows past the attempt
 *   threshold dead-letter to the DLQ stream.
 * - GET  /api/demo/msg/outbox/state → the evidence readout: pending /
 *   delivered / dead counts, the newest rows, and whether the poller is
 *   live (activation + area mode).
 *
 * The staged crashes surface as the taxonomy's 500 envelope on purpose (a
 * crash is not a domain failure with a status of its own) — the demo UI
 * treats that envelope as the START of the proof and /state as its end.
 */
@Tag(name = "Messaging", description = "The messaging topic's outbox demo: the dual-write "
        + "problem and the transactional fix, crashed on purpose, relayed for real.")
@RestController
@RequestMapping("/api/demo/msg/outbox")
public class OutboxDemoController {

    /** The proof request: which publisher, and where to crash. */
    record TransactionalRequest(String postId, String publisher, Boolean failBeforeCommit,
            Boolean failAfterPublish) {
    }

    /** The relay answer (the use-case report, verbatim). */
    record RelayView(int claimed, int published, int deadLettered, boolean skippedInFlight,
            java.util.List<String> streamIds) {
    }

    private final MsgDemoActivation activation;
    private final PublishPostEvent publishPostEvent;
    private final OutboxRelay relay;
    private final ReadOutboxState readOutboxState;
    private final ScenarioStore scenarioStore;

    public OutboxDemoController(MsgDemoActivation activation, PublishPostEvent publishPostEvent,
            OutboxRelay relay, ReadOutboxState readOutboxState, ScenarioStore scenarioStore) {
        this.activation = activation;
        this.publishPostEvent = publishPostEvent;
        this.relay = relay;
        this.readOutboxState = readOutboxState;
        this.scenarioStore = scenarioStore;
    }

    @Operation(summary = "The dual-write proof: mutate + event in one tx (or not)",
            operationId = "transactPublish",
            description = "publisher=outbox: the post update and the outbox row commit together — "
                    + "failAfterPublish crashes AFTER commit (the relay still delivers); "
                    + "failBeforeCommit rolls both back. publisher=direct: the XADD fires after "
                    + "commit — failAfterPublish crashes in the window where the event is lost "
                    + "forever. Staged crashes answer the 500 envelope on purpose.")
    @PostMapping(value = "/transactional", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PublishPostEvent.Outcome> transactional(
            @RequestBody TransactionalRequest request) {
        requireReady();
        Result<PublishPostEvent.Outcome> outcome = publishPostEvent.execute(
                request.postId(),
                request.publisher() == null ? PublishPostEvent.PUBLISHER_OUTBOX
                        : request.publisher(),
                Boolean.TRUE.equals(request.failBeforeCommit()),
                Boolean.TRUE.equals(request.failAfterPublish()));
        return switch (outcome) {
            case Success<PublishPostEvent.Outcome> success ->
                    ResponseEntity.ok(success.value());
            case Failed<PublishPostEvent.Outcome> failed ->
                    throw AppException.of(failed.failure());
        };
    }

    @Operation(summary = "Run one relay batch (claim → XADD → mark)",
            operationId = "relayOutboxBatch",
            description = "Claims pending rows with FOR UPDATE SKIP LOCKED (attempts+1), XADDs "
                    + "each to the stream, marks processed only after the broker accepted it. "
                    + "Concurrent batches: single-flight — the loser answers skippedInFlight. "
                    + "Rows past the attempt threshold dead-letter to the DLQ stream.")
    @PostMapping(value = "/relay", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RelayView> relay(@RequestParam(defaultValue = "16") int limit) {
        requireReady();
        OutboxRelay.RelayReport report = relay.relayNextBatch(Instant.now(), limit);
        return ResponseEntity.ok(new RelayView(report.claimed(), report.published(),
                report.deadLettered(), report.skippedInFlight(), report.streamIds()));
    }

    @Operation(summary = "The outbox state: counts, newest rows, poller verdict",
            operationId = "outboxState",
            description = "The demo's evidence endpoint — pending/delivered/dead (the two-column "
                    + "state machine, counted), the newest rows with their attempts, and whether "
                    + "the relay ticker is currently live (activation + scenario area).")
    @GetMapping(value = "/state", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReadOutboxState.StateView> state() {
        requireReady();
        return ResponseEntity.ok(readOutboxState.execute(10, relay.canRun()));
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
