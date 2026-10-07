package guide.domain.obs;

import guide.domain.jobs.JobQueue;
import guide.domain.jobs.JobRecord;
import guide.domain.jobs.JobType;
import guide.domain.jobs.JobWorker;
import guide.domain.posts.CreatePost;
import guide.domain.posts.PostDraft;
import guide.domain.posts.PostStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use case: the cross-layer WORK the trace demo hops into — one method that
 * genuinely touches other areas of the app, so the trace that crosses HTTP
 * also crosses domain layers. What one hop does:
 *
 * 1. creates a real post through the posts substrate ({@code CreatePost} —
 *    domain/posts), bumping the posts-created counter on the way out;
 * 2. enqueues a real send-welcome-email job and drains it through topic 29's
 *    actual worker ({@code JobWorker.runOnce}), bumping the outcome counter
 *    with the outcome vocabulary THAT topic defined (succeeded/failed/dead);
 * 3. logs one INFO business beat describing both.
 *
 * The INFO line is the load-bearing one: it is logged on the HOP's thread,
 * inside the trace the incoming {@code traceparent} started — so it reaches
 * the log ring carrying that trace id in MDC, which is the one-id proof the
 * demo renders.
 *
 * Collaborators are OPTIONAL on purpose (this repo's optional-service
 * discipline, applied to the probe itself): test slices that import only the
 * observability wiring get a hop that still runs and still logs — the steps
 * whose machinery is absent are reported as skipped, never faked. The full
 * application context wires all of it.
 */
public final class RunTracedHop {

    private static final Logger LOGGER = LoggerFactory.getLogger(RunTracedHop.class);

    private final CreatePost createPost;
    private final JobQueue jobQueue;
    private final JobWorker jobWorker;
    private final ObsMetrics metrics;

    public RunTracedHop(CreatePost createPost, JobQueue jobQueue, JobWorker jobWorker, ObsMetrics metrics) {
        this.createPost = createPost;
        this.jobQueue = jobQueue;
        this.jobWorker = jobWorker;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** Runs the hop's work; every step reports whether it ran or was skipped. */
    public HopFacts execute(String origin) {
        List<String> steps = new ArrayList<>();
        String postId = null;
        if (createPost != null) {
            postId = switch (createPost.execute(new PostDraft(
                    "Traced by the observability tour",
                    "Created by the traced hop from " + origin + " — a real post, really persisted.",
                    List.of("observability"), PostStatus.DRAFT))) {
                case guide.core.result.Success<guide.domain.posts.Post> success -> success.value().id();
                case guide.core.result.Failed<guide.domain.posts.Post> failed -> null;
            };
            steps.add(postId == null
                    ? "post creation returned a failed Result (skipped)"
                    : "post " + postId + " created through CreatePost (domain/posts)");
            if (postId != null) {
                metrics.postCreated();
            }
        } else {
            steps.add("posts substrate absent in this context — post creation skipped");
        }

        String jobId = null;
        String outcome = null;
        if (jobQueue != null && jobWorker != null) {
            Instant now = Instant.now();
            jobId = "obs-" + UUID.randomUUID();
            JobRecord job = new JobRecord(jobId, JobType.SEND_WELCOME_EMAIL,
                    "{\"to\":\"tourist@example.com\",\"name\":\"Tourist\"}",
                    JobRecord.JobState.QUEUED, 3, 0, List.of(), null, now, null, null, now, 0);
            jobQueue.enqueue(job);
            for (JobRecord touched : jobWorker.runOnce(now)) {
                if (jobId.equals(touched.id())) {
                    outcome = outcomeOf(touched);
                }
            }
            steps.add(outcome == null
                    ? "job " + jobId + " enqueued but not due in this pass"
                    : "job " + jobId + " drained through JobWorker.runOnce — outcome " + outcome
                            + " (topic 29's vocabulary)");
            if (outcome != null) {
                metrics.jobOutcome(outcome);
            }
        } else {
            steps.add("jobs machinery absent in this context — job episode skipped");
        }

        LOGGER.info("traced hop from {}: post {} job {} outcome {}", origin,
                postId == null ? "skipped" : postId, jobId == null ? "skipped" : jobId,
                outcome == null ? "skipped" : outcome);
        return new HopFacts(origin, postId, jobId, outcome, List.copyOf(steps));
    }

    /** The record's settled state in topic 29's outcome vocabulary (retry-pending attempts read as failed). */
    private static String outcomeOf(JobRecord settled) {
        return switch (settled.state()) {
            case SUCCEEDED -> "succeeded";
            case DEAD -> "dead";
            case QUEUED, RUNNING, FAILED -> "failed";
        };
    }

    /** What one hop actually did — every step claims ran or skipped, nothing invented. */
    public record HopFacts(String origin, String postId, String jobId, String outcome, List<String> steps) {
    }
}
