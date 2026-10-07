package guide.domain.jobs;

/**
 * The port for "how one kind of work actually runs". The worker knows the
 * retry policy; a handler knows the WORK: parse the payload, call the
 * outside world, and signal outcome by RETURNING (success) or THROWING
 * (failure — the message becomes the attempt's error and, eventually, the
 * dead-letter's evidence).
 *
 * Throwing is the contract's honest half: a handler that swallows its own
 * failures and returns normally has told the queue a lie — the job will
 * read succeeded while the email never left. Handlers are registered per
 * JobType at the composition root; a type with two handlers is a wiring
 * bug the worker refuses at construction.
 */
public interface JobHandler {

    JobType type();

    /**
     * Do the work. Must be idempotent-tolerant: the queue is at-least-once
     * (a retry after a lost acknowledgement re-runs this), so side effects
     * key on stable ids — the messaging topic's consumer discipline, restated
     * for jobs.
     */
    void execute(JobRecord job) throws Exception;
}
