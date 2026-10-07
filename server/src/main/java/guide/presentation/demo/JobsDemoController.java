package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.data.jobs.AsyncWorkService;
import guide.data.jobs.JobsHeartbeat;
import guide.data.jobs.RecordingEmailSender;
import guide.domain.jobs.AsyncWorkBoard;
import guide.domain.jobs.EnqueueJob;
import guide.domain.jobs.GetJob;
import guide.domain.jobs.ListDeadLetters;
import guide.domain.jobs.ReadQueueOverview;
import guide.domain.jobs.ReplayDeadLetter;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 29 (background jobs) — the queue's console:
 * - POST /api/demo/jobs/enqueue            → put work on the queue
 *   ({type, payload, failAttempts} — failAttempts injects N deterministic
 *   failures so a full retry episode is reproducible from curl)
 * - GET  /api/demo/jobs                    → the overview: counts by state
 *   plus the two observability numbers (depth, oldest pending age)
 * - GET  /api/demo/jobs/jobs/{id}          → one job's full lifecycle
 *   trace, attempt history included
 * - GET  /api/demo/jobs/dead               → the dead-letter queue
 * - POST /api/demo/jobs/dead/{id}/replay   → re-enqueue a dead record
 *   (optional {failAttempts} — the operator's "fix" in demo form)
 * - GET  /api/demo/jobs/heartbeat          → the @Scheduled cadence proof
 * - POST /api/demo/jobs/async              → the @Async contrast specimen:
 *   returns immediately, work completes on the executor
 * - GET  /api/demo/jobs/async              → the async tickets (thread
 *   names included — executor saturation is visible here)
 * - GET  /api/demo/jobs/emails             → what the recording sender
 *   actually sent — one mail per succeeded job, zero per failed one
 *
 * Thin as always: bind, call ONE use case (or board), map the Result onto
 * HTTP — retry/backoff/DLQ policy lives in the domain worker, never here.
 */
@RestController
public class JobsDemoController {

    static final long MAX_ASYNC_DELAY_MS = 10_000;

    private final EnqueueJob enqueueJob;
    private final GetJob getJob;
    private final ReadQueueOverview readQueueOverview;
    private final ListDeadLetters listDeadLetters;
    private final ReplayDeadLetter replayDeadLetter;
    private final JobsHeartbeat jobsHeartbeat;
    private final AsyncWorkBoard asyncWorkBoard;
    private final AsyncWorkService asyncWorkService;
    private final RecordingEmailSender recordingEmailSender;

    public JobsDemoController(EnqueueJob enqueueJob, GetJob getJob,
            ReadQueueOverview readQueueOverview, ListDeadLetters listDeadLetters,
            ReplayDeadLetter replayDeadLetter,
            JobsHeartbeat jobsHeartbeat, AsyncWorkBoard asyncWorkBoard,
            AsyncWorkService asyncWorkService, RecordingEmailSender recordingEmailSender) {
        this.enqueueJob = enqueueJob;
        this.getJob = getJob;
        this.readQueueOverview = readQueueOverview;
        this.listDeadLetters = listDeadLetters;
        this.replayDeadLetter = replayDeadLetter;
        this.jobsHeartbeat = jobsHeartbeat;
        this.asyncWorkBoard = asyncWorkBoard;
        this.asyncWorkService = asyncWorkService;
        this.recordingEmailSender = recordingEmailSender;
    }

    /**
     * Payload binds as JsonNode (any JSON shape the caller speaks) and is
     * STRINGIFIED on the way into the use case — the job stores its
     * arguments as JSON text, data not closure state.
     */
    public record EnqueueRequest(String type, tools.jackson.databind.JsonNode payload,
            Integer failAttempts) {
    }

    @PostMapping(value = "/api/demo/jobs/enqueue",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    GetJob.JobView enqueue(@RequestBody EnqueueRequest request) {
        String payloadJson = request == null || request.payload() == null
                || request.payload().isNull()
                        ? null
                        : request.payload().toString();
        return unwrap(enqueueJob.execute(
                new EnqueueJob.Request(request.type(), payloadJson, request.failAttempts()))
                .map(GetJob::jobView));
    }

    @GetMapping(value = "/api/demo/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
    ReadQueueOverview.Overview overview() {
        return readQueueOverview.execute();
    }

    @GetMapping(value = "/api/demo/jobs/jobs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    GetJob.JobView job(@PathVariable String id) {
        return unwrap(getJob.execute(id));
    }

    @GetMapping(value = "/api/demo/jobs/dead", produces = MediaType.APPLICATION_JSON_VALUE)
    DeadLettersView deadLetters() {
        return new DeadLettersView(listDeadLetters.execute());
    }

    record DeadLettersView(List<GetJob.JobView> deadLetters) {
    }

    @PostMapping(value = "/api/demo/jobs/dead/{id}/replay",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    GetJob.JobView replay(@PathVariable String id, @RequestBody(required = false) ReplayDeadLetter.Request request) {
        return unwrap(replayDeadLetter.execute(id, request).map(GetJob::jobView));
    }

    @GetMapping(value = "/api/demo/jobs/heartbeat", produces = MediaType.APPLICATION_JSON_VALUE)
    JobsHeartbeat.HeartbeatView heartbeat() {
        return jobsHeartbeat.view();
    }

    public record AsyncRequest(Long delayMs) {
    }

    @PostMapping(value = "/api/demo/jobs/async",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    AsyncWorkBoard.AsyncTicket async(@RequestBody AsyncRequest request) {
        long delayMs = request == null || request.delayMs() == null ? 1_000 : request.delayMs();
        if (delayMs < 1 || delayMs > MAX_ASYNC_DELAY_MS) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "delayMs must be between 1 and " + MAX_ASYNC_DELAY_MS));
        }
        AsyncWorkBoard.AsyncTicket ticket = asyncWorkBoard.open(
                "work_" + UUID.randomUUID(), delayMs, java.time.Instant.now());
        // through the PROXY (injected bean) — never self-invoked: an
        // internal call would skip it and run right here on the request thread
        asyncWorkService.runSlowWork(ticket.id(), delayMs);
        return ticket;
    }

    @GetMapping(value = "/api/demo/jobs/async", produces = MediaType.APPLICATION_JSON_VALUE)
    List<AsyncWorkBoard.AsyncTicket> asyncTickets() {
        return asyncWorkBoard.tickets();
    }

    @GetMapping(value = "/api/demo/jobs/emails", produces = MediaType.APPLICATION_JSON_VALUE)
    List<RecordingEmailSender.SentEmail> emails() {
        return recordingEmailSender.sent();
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
