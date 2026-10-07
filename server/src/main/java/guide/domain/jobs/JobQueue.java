package guide.domain.jobs;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The persistence port for topic 29's queue: everything the worker, the use
 * cases, and the console need to remember about deferred work. The
 * production adapter is the bounded in-memory store (data/jobs) — a
 * teaching console's state is per-run — but the worker and use cases only
 * ever speak this interface, so a durable adapter (a {@code jobs} table
 * claimed with {@code UPDATE … WHERE state='queued' AND next_attempt_at <=
 * now RETURNING …}, or Redis Streams with consumer groups like topic 22)
 * slots in without touching a line of domain code.
 *
 * The load-bearing method is {@link #claimNextDue(Instant)}: claiming must
 * be ATOMIC — flip the record to running and hand it to exactly one caller
 * — because the worker's ticker and any future competing worker all poll
 * the same queue. The in-memory adapter serializes; the SQL adapter uses
 * the row lock; the semantics are the contract.
 */
public interface JobQueue {

    /** A new record enters the queue: state queued, first attempt due at its nextAttemptAt. */
    void enqueue(JobRecord job);

    /**
     * The worker's whole view of "what time has made runnable": the
     * earliest-due pending record (queued or failed-retry), atomically
     * flipped to RUNNING and returned — empty when nothing is due. Passing
     * {@code now} explicitly (instead of reading a clock) is what makes the
     * worker deterministic under a fake clock.
     */
    Optional<JobRecord> claimNextDue(Instant now);

    /**
     * Persist a state transition the worker derived — this IS
     * markSucceeded/markFailed realized: the worker computes the retry
     * decision (succeeded, retry-with-backoff, or dead), the store just
     * remembers it.
     */
    void save(JobRecord job);

    Optional<JobRecord> find(String id);

    /** Insertion order, oldest first — the overview's raw material. */
    List<JobRecord> snapshot();

    /** Terminal failures only, oldest first — the confession booth's contents. */
    List<JobRecord> deadLetters();
}
