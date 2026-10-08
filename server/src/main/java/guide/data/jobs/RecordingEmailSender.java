package guide.data.jobs;

import guide.domain.jobs.EmailSender;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Adapter: the in-memory mail relay. The canonical job needs a flaky
 * external dependency to be honest, and the flakiness comes from the
 * handler's injected failures — so the SENDER itself just records what
 * landed, bounded, and never fails. What the recording proves is the
 * invariant that matters: ONE welcome mail per succeeded job, zero per
 * failed one, exactly the attempt history the record claims.
 *
 * The real adapter (topic 43) wraps Spring Mail's JavaMailSender —
 * {@code sender.send(mimeMessage)} with timeouts set, throwing
 * MailException on transport trouble — and drops into the same port
 * without the handler noticing.
 */
public final class RecordingEmailSender implements EmailSender {

    static final int MAX_RECORDED = 100;

    /** What actually left: the receipt the console shows beside the queue's claims. */
    public record SentEmail(String to, String subject, String body, Instant at) {
    }

    private final Clock clock;
    private final Deque<SentEmail> sent = new ArrayDeque<>();

    public RecordingEmailSender(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized void send(String to, String subject, String body) {
        sent.addFirst(new SentEmail(to, subject, body, clock.instant()));
        while (sent.size() > MAX_RECORDED) {
            sent.removeLast();
        }
    }

    /** Newest first — the console's "what actually left" pane. */
    public synchronized List<SentEmail> sent() {
        return List.copyOf(sent);
    }

    /** Demo-console handle for the shared reset sweep: forget every receipt. */
    public synchronized void clear() {
        sent.clear();
    }
}
