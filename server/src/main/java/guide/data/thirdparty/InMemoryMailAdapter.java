package guide.data.thirdparty;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.thirdparty.MailPort;
import guide.domain.thirdparty.Posture;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adapter: the in-process mail relay (the demo's default, posture FAKE).
 * Deterministic by construction — a counter, not a clock; no socket, no
 * credential, nothing to leak.
 *
 * SANDBOX posture models the teaching behavior of real relays:
 * GREYLISTING — the first send to any NEW recipient is deferred with the
 * classic "421 try again later" answer (outcome queued), and the retry
 * succeeds. That one behavior is why every mail integration needs a retry
 * story (topic 29's job queue) and why "send" can never be "fire and
 * forget" in the request path.
 *
 * Header-injection guard: addresses and subjects containing CR/LF are
 * REJECTED as typed validation failures before anything is recorded —
 * the security topic's injection discipline applied at an integration
 * boundary, where unvalidated input would otherwise ride into SMTP
 * headers verbatim.
 */
public final class InMemoryMailAdapter implements MailPort {

    static final int MAX_RECORDED = 16;

    /** What the fake relay accepted — the console's outbound pane, bounded. */
    public record AcceptedMail(String to, String subject, long atNanos) {
    }

    private final AtomicLong sequence = new AtomicLong();
    private final Deque<AcceptedMail> accepted = new ArrayDeque<>();
    private final Set<String> seenRecipients = new HashSet<>();
    private volatile Posture posture = Posture.FAKE;

    @Override
    public MailReceipt send(String to, String subject, String body) {
        rejectHeaderInjection(to, "to");
        rejectHeaderInjection(subject, "subject");
        String messageId = "demo-mail-" + sequence.incrementAndGet();
        if (posture == Posture.SANDBOX && seenRecipients.add(normalized(to))) {
            return new MailReceipt("queued", messageId,
                    "sandbox relay: SMTP 421 greylisting — first contact with " + to
                            + " deferred, the retry delivers (topic 29's retry story in one answer)");
        }
        record(to, subject);
        return new MailReceipt("sent", messageId,
                (posture == Posture.SANDBOX ? "sandbox relay accepted" : "in-process fake accepted")
                        + " mail to " + to);
    }

    @Override
    public Posture posture() {
        return posture;
    }

    @Override
    public boolean adoptPosture(Posture next) {
        if (next == Posture.FAKE || next == Posture.SANDBOX) {
            posture = next;
            return true;
        }
        return false;
    }

    @Override
    public synchronized void reset() {
        accepted.clear();
        seenRecipients.clear();
        sequence.set(0);
        posture = Posture.FAKE;
    }

    /** Newest first — bounded evidence of what the fake relay accepted. */
    public synchronized List<AcceptedMail> accepted() {
        return List.copyOf(accepted);
    }

    private synchronized void record(String to, String subject) {
        accepted.addFirst(new AcceptedMail(to, subject, System.nanoTime()));
        while (accepted.size() > MAX_RECORDED) {
            accepted.removeLast();
        }
    }

    private static String normalized(String to) {
        return to.trim().toLowerCase();
    }

    private static void rejectHeaderInjection(String value, String field) {
        if (value == null || value.isBlank()) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    field + " must not be blank",
                    Map.of("parameter", field)));
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    field + " contains CR/LF — SMTP header injection is rejected at the boundary",
                    Map.of("parameter", field)));
        }
    }
}
