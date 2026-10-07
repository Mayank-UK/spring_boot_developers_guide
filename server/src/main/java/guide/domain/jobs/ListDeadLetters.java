package guide.domain.jobs;

import java.util.List;
import java.util.Objects;

/**
 * Use case: the dead-letter queue's contents, oldest first, as console
 * views. This is the desk an operator sits at when the two observability
 * numbers go wrong: each row is a job that exhausted its attempt budget,
 * carrying the full error trail the replay decision is made from.
 */
public final class ListDeadLetters {

    private final JobQueue queue;

    public ListDeadLetters(JobQueue queue) {
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    public List<GetJob.JobView> execute() {
        return queue.deadLetters().stream()
                .map(GetJob::jobView)
                .toList();
    }
}
