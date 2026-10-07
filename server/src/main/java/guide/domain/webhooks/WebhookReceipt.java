package guide.domain.webhooks;

import java.time.Instant;
import java.util.Objects;

/**
 * What the receiver saw — the other half of the console. Every inbound POST
 * to the loopback receiver lands as one receipt, whether it verified, was
 * rejected, or turned out to be a duplicate; the receipts view is therefore
 * an honest log from the CONSUMER's chair, next to the sender's delivery
 * timelines, so both sides of one webhook can be read side by side.
 */
public record WebhookReceipt(
        String eventId,
        String eventType,
        /** verified | invalid | stale — plus the reason a human can act on. */
        String signatureVerdict,
        String signatureReason,
        /** True when this event id was already received: the at-least-once reality, recorded. */
        boolean duplicate,
        int respondedStatus,
        String note,
        Instant receivedAt) {

    public WebhookReceipt {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(signatureVerdict, "signatureVerdict");
        Objects.requireNonNull(signatureReason, "signatureReason");
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
