package guide.domain.messaging;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.time.Clock;

/**
 * Use case: the outbox demo's PROOF ORCHESTRATOR. One POST, two publishers,
 * two crash points — and the differences between them are the topic's sixth
 * section told as an executable:
 *
 * OUTBOX mode: the event append rides the SAME transaction as the posts
 * mutation (the adapter calls the outbox publisher inside the @Transactional
 * bracket). failBeforeCommit rolls BOTH back — no post change, no event.
 * failAfterPublish crashes AFTER the commit — the post IS updated and the
 * event IS queued; the relay delivers it anyway. The crash that would lose
 * a direct-publish event loses nothing here, because the durable copy of
 * the event was never outside the database.
 *
 * DIRECT mode: the transaction contains only the posts mutation, then the
 * XADD fires after commit — the two-systems window. failBeforeCommit still
 * rolls the mutation back cleanly (the transaction works); failAfterPublish
 * crashes BETWEEN commit and XADD — the post is updated, the event never
 * exists anywhere. THE LOST EVENT, demonstrated rather than described.
 *
 * The crashes are UNTYPED IllegalStateExceptions on purpose (a crash is not
 * a domain failure with an HTTP status — the same call the audit demo
 * made): they surface as the taxonomy's 500 envelope, and the demo's /state
 * readout afterwards is the evidence that matters.
 */
public class PublishPostEvent {

    /** The two publishers the demo teaches side by side. */
    public static final String PUBLISHER_OUTBOX = "outbox";
    public static final String PUBLISHER_DIRECT = "direct";

    private final PostEventTransaction transaction;
    private final TeachingEventPublisher directPublisher;
    private final TeachingEventPublisher outboxPublisher;
    private final Clock clock;

    public PublishPostEvent(PostEventTransaction transaction,
            TeachingEventPublisher directPublisher, TeachingEventPublisher outboxPublisher,
            Clock clock) {
        this.transaction = transaction;
        this.directPublisher = directPublisher;
        this.outboxPublisher = outboxPublisher;
        this.clock = clock;
    }

    /** The 200 answer when no crash was staged. */
    public record Outcome(String publisher, String postId, String title, long newVersion,
            String eventId, String streamId, String note) {
    }

    public Result<Outcome> execute(String postId, String publisher,
            boolean failBeforeCommit, boolean failAfterPublish) {
        if (postId == null || postId.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "the transactional demo needs a postId to publish", null));
        }
        boolean outboxMode = PUBLISHER_OUTBOX.equals(publisher);
        if (!outboxMode && !PUBLISHER_DIRECT.equals(publisher)) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "publisher must be \"outbox\" or \"direct\"", null));
        }
        // The event is minted ONCE, before any I/O: the same eventId rides
        // the outbox row, the stream entry, and the consumer's dedupe set —
        // one identity across every hop is what makes dedupe possible.
        DomainEvent event = DomainEvent.postPublished(postId.strip(), clock.instant());
        PostEventTransaction.PostMutation mutation;
        if (outboxMode) {
            mutation = transaction.transactPublish(postId.strip(), event,
                    failBeforeCommit, outboxPublisher);
            if (failAfterPublish) {
                throw new IllegalStateException("simulated crash AFTER commit — the post and "
                        + "the outbox row committed together, so the relay still delivers event "
                        + event.eventId() + " (watch /state, then POST /relay)");
            }
            return Result.success(new Outcome(PUBLISHER_OUTBOX, mutation.postId(),
                    mutation.title(), mutation.newVersion(), event.eventId(), null,
                    "one transaction: the post update and the outbox row committed together — "
                            + "the relay owns delivery from here"));
        }
        mutation = transaction.transactPublish(postId.strip(), event, failBeforeCommit, null);
        if (failAfterPublish) {
            throw new IllegalStateException("simulated crash BETWEEN commit and XADD — the post "
                    + "is updated but the event was never published anywhere: THE LOST EVENT "
                    + "(the dual-write window the outbox pattern exists to close)");
        }
        TeachingEventPublisher.PublishedEvent published = directPublisher.publish(event);
        return Result.success(new Outcome(PUBLISHER_DIRECT, mutation.postId(),
                mutation.title(), mutation.newVersion(), event.eventId(), published.streamId(),
                "two systems: the transaction committed the post, then XADD published outside "
                        + "it — a crash in between loses the event"));
    }
}
