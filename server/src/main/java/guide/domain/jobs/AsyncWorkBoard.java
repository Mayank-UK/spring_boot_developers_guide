package guide.domain.jobs;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The observable state of the @Async contrast specimen: a bounded board of
 * slow-work tickets. The @Async method (data/jobs/AsyncWorkService) opens
 * a ticket, then marks it running ON THE EXECUTOR THREAD (recording the
 * thread's name — the one field that makes executor saturation visible:
 * caller-runs tickets carry the SUBMITTER's name), then done.
 *
 * Pure state, thread-safe by synchronization, no framework types — the
 * same in-memory-board discipline as the webhook receipts log. The
 * teaching payload is the contrast it makes observable: fire-and-forget
 * work has NO retries, NO dead letters, NO replay — when the process dies
 * mid-ticket, the ticket is simply gone, which is precisely the line
 * between "worth an @Async" and "worth a queue".
 */
public final class AsyncWorkBoard {

    /** Bounded like every teaching board — a left-open console must not grow memory. */
    public static final int MAX_TICKETS = 50;

    /** One piece of slow work: requested when the API said "accepted", ran where the thread name says. */
    public record AsyncTicket(
            String id,
            long delayMs,
            Instant requestedAt,
            Instant startedAt,
            String threadName,
            Instant finishedAt,
            String state) {

        public AsyncTicket {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(state, "state");
        }
    }

    private final Deque<AsyncTicket> tickets = new ArrayDeque<>();

    /** The caller's half, before the proxy call returns: the ticket exists and is pending. */
    public synchronized AsyncTicket open(String id, long delayMs, Instant now) {
        AsyncTicket ticket = new AsyncTicket(id, delayMs, now, null, null, null, "pending");
        tickets.addFirst(ticket);
        while (tickets.size() > MAX_TICKETS) {
            tickets.removeLast();
        }
        return ticket;
    }

    /** The executor's half: running, and on which thread — the saturation evidence. */
    public synchronized void markRunning(String id, String threadName, Instant now) {
        replace(id, ticket -> new AsyncTicket(ticket.id(), ticket.delayMs(), ticket.requestedAt(),
                now, threadName, ticket.finishedAt(), "running"));
    }

    public synchronized void markDone(String id, Instant now) {
        replace(id, ticket -> new AsyncTicket(ticket.id(), ticket.delayMs(), ticket.requestedAt(),
                ticket.startedAt(), ticket.threadName(), now, "done"));
    }

    public synchronized Optional<AsyncTicket> find(String id) {
        return tickets.stream().filter(ticket -> ticket.id().equals(id)).findFirst();
    }

    /** Newest first — the console's live view. */
    public synchronized List<AsyncTicket> tickets() {
        return new ArrayList<>(tickets);
    }

    /**
     * Demo-console handle for the shared reset sweep: forget every ticket.
     * A ticket still in flight when this lands later calls markRunning/
     * markDone on an EMPTY board — {@link #replace} no-ops on unknown ids,
     * so a mid-flight ticket cannot resurrect itself after the reset.
     */
    public synchronized void clear() {
        tickets.clear();
    }

    private void replace(String id, java.util.function.UnaryOperator<AsyncTicket> change) {
        java.util.List<AsyncTicket> next = new java.util.ArrayList<>();
        boolean replaced = false;
        for (AsyncTicket ticket : tickets) {
            if (ticket.id().equals(id)) {
                next.add(change.apply(ticket));
                replaced = true;
            } else {
                next.add(ticket);
            }
        }
        if (replaced) {
            tickets.clear();
            tickets.addAll(next);
        }
    }
}
