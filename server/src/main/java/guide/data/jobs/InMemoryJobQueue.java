package guide.data.jobs;

import guide.domain.jobs.JobQueue;
import guide.domain.jobs.JobRecord;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter: the queue's state, in memory, bounded. A teaching console must
 * never grow without limit because someone left a poll running — so the
 * store evicts at a fixed ceiling, finished records first (their evidence
 * value decays fastest), oldest first within each class.
 *
 * All methods are synchronized: the worker's @Scheduled tick thread, any
 * competing future worker, and request threads share this store, and a
 * queue that corrupts itself under its own load would teach the wrong
 * lesson. {@link #claimNextDue(Instant)} is the atomicity that matters —
 * the synchronized keyword here plays the role {@code UPDATE … RETURNING}
 * plays in the SQL adapter this port is shaped for: earliest-due pending
 * record wins, flips to RUNNING, goes home with exactly one caller.
 */
public final class InMemoryJobQueue implements JobQueue {

    static final int MAX_RECORDS = 200;

    private final Map<String, JobRecord> records = new LinkedHashMap<>();

    @Override
    public synchronized void enqueue(JobRecord job) {
        records.put(job.id(), job);
        evictBeyondCeiling();
    }

    @Override
    public synchronized Optional<JobRecord> claimNextDue(Instant now) {
        JobRecord best = null;
        for (JobRecord record : records.values()) {
            if (!record.state().pending() || record.nextAttemptAt().isAfter(now)) {
                continue;
            }
            if (best == null || record.nextAttemptAt().isBefore(best.nextAttemptAt())) {
                best = record;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        JobRecord running = best.running(now);
        records.put(running.id(), running);
        return Optional.of(running);
    }

    @Override
    public synchronized void save(JobRecord job) {
        records.put(job.id(), job);
    }

    @Override
    public synchronized Optional<JobRecord> find(String id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public synchronized List<JobRecord> snapshot() {
        return List.copyOf(records.values());
    }

    @Override
    public synchronized List<JobRecord> deadLetters() {
        return records.values().stream()
                .filter(job -> job.state() == JobRecord.JobState.DEAD)
                .sorted(Comparator.comparing(JobRecord::queuedAt))
                .toList();
    }

    private void evictBeyondCeiling() {
        while (records.size() > MAX_RECORDS) {
            String victim = records.values().stream()
                    .sorted(Comparator
                            // finished history first (!terminal ⇒ false sorts first);
                            // runnable work only when nothing finished remains
                            .comparing((JobRecord job) -> !job.state().terminal())
                            .thenComparing(JobRecord::queuedAt))              // oldest first
                    .map(JobRecord::id)
                    .findFirst()
                    .orElseThrow();
            records.remove(victim);
        }
    }
}
