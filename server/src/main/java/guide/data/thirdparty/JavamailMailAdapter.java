package guide.data.thirdparty;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.thirdparty.MailPort;
import guide.domain.thirdparty.Posture;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import java.util.Objects;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Adapter: the REAL one — Spring Mail's {@link JavaMailSender} behind the
 * mail port, exactly the drop-in {@code RecordingEmailSender}'s javadoc
 * promised topic 43 would wire. The port contract is what makes it a
 * drop-in: nothing upstream knows which adapter answered.
 *
 * Two deliberate boundaries in this guide:
 *
 * <ul>
 *   <li>CONSTRUCTED, NEVER CALLED — the demo wires this bean (proving the
 *       context boots with a real adapter present) but routes every
 *       trigger to the in-process fake. The unit test exercises the send
 *       path with a STUBBED JavaMailSender — an interface, so no socket
 *       ever opens. A guide that teaches "no network in tests" must not
 *       ship its own exception.</li>
 *   <li>THROW, DON'T SWALLOW — transport failure surfaces as the app's
 *       typed UNAVAILABLE failure carrying the relay's message; the
 *       caller (in production: topic 29's retrying job worker) decides
 *       what a failure means. Timeouts live on the SENDER's configuration
 *       (see ThirdPartyDemoConfig), not on hope.</li>
 * </ul>
 */
public final class JavamailMailAdapter implements MailPort {

    private final JavaMailSender sender;
    private final String from;

    public JavamailMailAdapter(JavaMailSender sender, String from) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.from = Objects.requireNonNull(from, "from");
    }

    @Override
    public MailReceipt send(String to, String subject, String body) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);
            return new MailReceipt("sent", message.getMessageID(),
                    "delivered to the configured SMTP relay (posture live)");
        } catch (MessagingException | MailException failure) {
            throw AppException.of(Failure.of(FailureKind.UNAVAILABLE,
                    "the mail send failed: " + failure.getMessage(),
                    Map.of("adapter", "javamail", "to", to)));
        }
    }

    @Override
    public Posture posture() {
        return Posture.LIVE;
    }

    @Override
    public boolean adoptPosture(Posture next) {
        // LIVE is a key-and-wiring decision, never a runtime dial — refuse.
        return false;
    }

    @Override
    public void reset() {
        // no state: the relay's state is the provider's business
    }
}
