package guide.domain.webhooks;

import java.util.List;

/**
 * Use case: the receiver-side log — everything the loopback consumer saw,
 * with each request's signature verdict and the status it was answered
 * with. Read next to the delivery timelines it closes the loop: the same
 * event appears on the left as the sender's attempts and on the right as
 * the consumer's receipts, duplicates included (at-least-once, visible).
 */
public class ListWebhookReceipts {

    public record ReceiptView(String eventId, String eventType, String signatureVerdict,
            String signatureReason, boolean duplicate, int respondedStatus, String note,
            String receivedAt) {

        static ReceiptView of(WebhookReceipt receipt) {
            return new ReceiptView(receipt.eventId(), receipt.eventType(),
                    receipt.signatureVerdict(), receipt.signatureReason(), receipt.duplicate(),
                    receipt.respondedStatus(), receipt.note(), receipt.receivedAt().toString());
        }
    }

    public record ReceiptsView(List<ReceiptView> receipts) {
    }

    private final WebhookStore store;

    public ListWebhookReceipts(WebhookStore store) {
        this.store = store;
    }

    public ReceiptsView execute() {
        return new ReceiptsView(store.receipts().stream().map(ReceiptView::of).toList());
    }
}
