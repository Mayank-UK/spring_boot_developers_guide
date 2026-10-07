package guide.domain.jobs;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Use case: the queue's dashboard row — the TWO numbers that catch every
 * failure mode a queue has. DEPTH (how many records still owe the world an
 * attempt) tells you throughput problems: producer outrunning consumer,
 * workers down, a poison dependency backing everything up. OLDEST PENDING
 * AGE (how long the most senior unfinished record has been waiting) tells
 * you latency and stall problems: a queue that is shallow but stale is a
 * scheduler not firing; one that is deep and fresh is capacity; deep AND
 * stale is an outage you are already having.
 *
 * Counts are reported for all five states (zeros included) so the
 * console's chips never flicker between shapes.
 */
public final class ReadQueueOverview {

    public record Overview(
            Map<String, Long> countsByState,
            int depth,
            String oldestPendingId,
            Long oldestPendingAgeMs,
            int deadLetters) {
    }

    private final JobQueue queue;
    private final Clock clock;

    public ReadQueueOverview(JobQueue queue, Clock clock) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Overview execute() {
        List<JobRecord> snapshot = queue.snapshot();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JobRecord.JobState state : JobRecord.JobState.values()) {
            counts.put(state.wireName(), 0L);
        }
        snapshot.forEach(job -> counts.merge(job.state().wireName(), 1L, Long::sum));

        JobRecord oldestPending = snapshot.stream()
                .filter(job -> !job.state().terminal())
                .min(Comparator.comparing(JobRecord::queuedAt))
                .orElse(null);
        int depth = (int) snapshot.stream().filter(job -> !job.state().terminal()).count();
        return new Overview(counts, depth,
                oldestPending == null ? null : oldestPending.id(),
                oldestPending == null ? null
                        : java.time.Duration.between(oldestPending.queuedAt(), clock.instant()).toMillis(),
                queue.deadLetters().size());
    }
}
