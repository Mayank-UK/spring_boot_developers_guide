package guide.domain.thirdparty;

/**
 * The mail port — "send one transactional message and tell me what the
 * provider said". Mail is the integration every app eventually has, and
 * the one this guide gives the full treatment: an in-process fake (the
 * demo's default), a sandbox-flavored fake that models relay greylisting,
 * and a REAL adapter over Spring Mail's JavaMailSender (topic 43's drop-in
 * proof, constructed but never called against the network in this guide).
 *
 * Deliberate contrast with topic 29's {@code guide.domain.jobs.EmailSender}:
 * that port is the JOB engine's dependency — void, throws on transport
 * failure, consumed by the retrying worker. This port returns a provider
 * {@link MailReceipt} because the hub's product is the OUTCOME VIEW (sent
 * / queued / rate-limited / failed) — the shape an operational console
 * needs. One shape per consumer; both adapt the same underlying relay in
 * production.
 */
public non-sealed interface MailPort extends IntegrationPort {

    /**
     * Send one message. THROWS a typed UNAVAILABLE failure when the
     * transport refuses (the caller decides whether that is a retry or an
     * incident); a returned receipt is a provider ANSWER — including a
     * queued deferral or a quota rejection, which are outcomes, not
     * exceptions.
     */
    MailReceipt send(String to, String subject, String body);

    /**
     * What the provider answered. messageId is loudly fake by construction
     * in this guide's adapters (demo-mail-N) — a REAL provider id is
     * opaque anyway, and nothing here may look like a credential.
     */
    record MailReceipt(String outcome, String messageId, String detail) {
    }
}
