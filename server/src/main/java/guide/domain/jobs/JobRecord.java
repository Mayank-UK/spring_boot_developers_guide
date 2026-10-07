package guide.domain.jobs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One unit of deferred work — the unit the queue shepherds. The state
 * machine is the topic's spine:
 *
 * <pre>
 * queued ──claim──▶ running ──ok──▶ succeeded                     (terminal)
 *   ▲                  │
 *   │                  └─ fail ── +backoff ──▶ failed (queued again, later)
 *   └── replay (new record) ◀── dead ◀── attempts ≥ max ──┘       (terminal)
 * </pre>
 *
 * Immutable by design: the worker never mutates a record in place, it
 * derives the next state (with one more attempt appended) and saves the new
 * value — events-as-data, same discipline as the webhook delivery it is
 * modeled on. {@code failed} is the retry-pending state: the attempt DID
 * fail, the queue WILL try again at {@code nextAttemptAt}; when the attempt
 * budget runs out the same failure lands the record in {@code dead} (the
 * dead-letter queue) instead.
 *
 * {@code failFirstAttempts} is the console's deterministic failure
 * injection — attempts 1..N fail with an injected transport error, attempt
 * N+1 onward succeeds — so a full retry episode is reproducible in a test
 * or a curl tour without breaking a real SMTP server.
 */
public record JobRecord(
        String id,
        JobType type,
        /** Payload as JSON text: the job carries its arguments as data, not as closure state. */
        String payload,
        JobState state,
        int maxAttempts,
        /** Deterministic failure injection: the first N attempts fail before the handler really runs. */
        int failFirstAttempts,
        List<Attempt> attempts,
        /** The most recent failure's message; null until something fails. */
        String lastError,
        Instant queuedAt,
        /** When attempt 1 started; null until the first claim. */
        Instant startedAt,
        /** When the record reached a terminal state; null until then. */
        Instant finishedAt,
        /** When the next attempt may be claimed; null exactly when terminal or running. */
        Instant nextAttemptAt,
        /** How many times this lineage has been replayed from the dead-letter queue. */
        int replays) {

    /** Wire name is the lower-case form the console speaks: "queued", "failed", "dead". */
    public enum JobState {
        QUEUED("queued"),
        RUNNING("running"),
        SUCCEEDED("succeeded"),
        FAILED("failed"),
        DEAD("dead");

        private final String wireName;

        JobState(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public boolean terminal() {
            return this == SUCCEEDED || this == DEAD;
        }

        /** True when the record still owes the world an attempt (or a retry). */
        public boolean pending() {
            return this == QUEUED || this == FAILED;
        }
    }

    /**
     * One try, verbatim: which attempt number, when it ran, how it ended,
     * and the error message if it ended badly — the trace an operator reads
     * in the dead-letter queue to decide between "fix and replay" and
     * "delete and forget".
     */
    public record Attempt(int n, Instant at, Outcome outcome, String error) {

        public enum Outcome {
            SUCCEEDED("succeeded"),
            FAILED("failed"),
            DEAD("dead");

            private final String wireName;

            Outcome(String wireName) {
                this.wireName = wireName;
            }

            public String wireName() {
                return wireName;
            }
        }

        public Attempt {
            if (n < 1) {
                throw new IllegalArgumentException("attempt number starts at 1");
            }
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public JobRecord {
        requireNonBlank(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(state, "state");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (failFirstAttempts < 0) {
            throw new IllegalArgumentException("failFirstAttempts must not be negative");
        }
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        Objects.requireNonNull(queuedAt, "queuedAt");
        if (state.terminal()) {
            Objects.requireNonNull(finishedAt, "finishedAt — terminal records are finished");
            if (nextAttemptAt != null) {
                throw new IllegalArgumentException("terminal records have no nextAttemptAt");
            }
        } else if (state.pending() && nextAttemptAt == null) {
            throw new IllegalArgumentException(state.wireName() + " records need nextAttemptAt");
        }
    }

    /** Attempts still budgeted before the dead-letter queue takes over. */
    public int attemptsLeft() {
        return maxAttempts - attempts.size();
    }

    /** The claim transition: flip to running, stamp the first-attempt start once. */
    public JobRecord running(Instant now) {
        if (state.terminal()) {
            throw new IllegalStateException("terminal records do not run again: " + id);
        }
        Instant started = startedAt == null ? now : startedAt;
        return new JobRecord(id, type, payload, JobState.RUNNING, maxAttempts,
                failFirstAttempts, attempts, lastError, queuedAt, started, finishedAt, null, replays);
    }

    /** The happy ending: append the succeeding attempt, finish. */
    public JobRecord succeeded(Attempt attempt) {
        return advanced(JobState.SUCCEEDED, attempt, null, attempt.at());
    }

    /** A failure that still has budget: append the attempt, schedule the retry. */
    public JobRecord awaitingRetry(Attempt attempt, Instant nextDue) {
        Objects.requireNonNull(nextDue, "nextDue");
        return advanced(JobState.FAILED, attempt, nextDue, null);
    }

    /** The budget is spent: append the attempt as the killing one, finish. */
    public JobRecord dead(Attempt attempt) {
        return advanced(JobState.DEAD, attempt, null, attempt.at());
    }

    private JobRecord advanced(JobState nextState, Attempt attempt, Instant nextDue, Instant finished) {
        if (attempt.n() != attempts.size() + 1) {
            throw new IllegalArgumentException("attempt numbers are consecutive from 1");
        }
        if (attempt.outcome() == Attempt.Outcome.SUCCEEDED && nextState != JobState.SUCCEEDED) {
            throw new IllegalArgumentException("a succeeded attempt only ends in succeeded");
        }
        List<Attempt> nextAttempts = new ArrayList<>(attempts);
        nextAttempts.add(attempt);
        return new JobRecord(id, type, payload, nextState, maxAttempts, failFirstAttempts,
                List.copyOf(nextAttempts), errorOf(attempt), queuedAt, startedAt, finished, nextDue, replays);
    }

    private static String errorOf(Attempt attempt) {
        return attempt.error() == null ? null : attempt.error();
    }

    /**
     * The dead-letter queue's exit: replaying enqueues a FRESH record with
     * this one's type and payload (attempts start over, a new id, the
     * injection knob set by the operator's request) — the dead record stays
     * in the DLQ as evidence. The replays counter carries the lineage.
     */
    public JobRecord replayedAs(String newId, int newFailFirstAttempts, Instant now) {
        if (state != JobState.DEAD) {
            throw new IllegalStateException("only dead-lettered records are replayed: " + id);
        }
        return new JobRecord(newId, type, payload, JobState.QUEUED, maxAttempts, newFailFirstAttempts,
                List.of(), null, now, null, null, now, replays + 1);
    }

    private static void requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("job " + what + " must not be blank");
        }
    }
}
