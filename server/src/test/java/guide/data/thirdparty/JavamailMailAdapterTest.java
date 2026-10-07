package guide.data.thirdparty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.AppException;
import guide.core.errors.FailureKind;
import guide.domain.thirdparty.MailPort;
import guide.domain.thirdparty.Posture;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The REAL mail adapter's send path over a STUBBED JavaMailSender — the
 * interface makes the transport a contract double, so NO socket ever
 * opens (the guide's zero-network rule, applied to the one adapter that
 * could break it). Pinned: a successful transport hands the relay a
 * real MIME message (recipient intact) and answers a sent receipt; a
 * MailException from the relay surfaces as the typed UNAVAILABLE
 * failure carrying the relay's message — never swallowed; and the
 * posture contract holds — the real adapter reports LIVE and REFUSES
 * runtime posture changes, the topic's rule that live is a
 * key-and-wiring decision, not a dial.
 */
class JavamailMailAdapterTest {

    /** The transport as a test double: hands out real (session-less) MIME messages, send is scripted. */
    private static final class StubSender implements JavaMailSender {
        MimeMessage lastSent;
        RuntimeException onSend;

        @Override
        public MimeMessage createMimeMessage() {
            return new MimeMessage((jakarta.mail.Session) null);
        }

        @Override
        public MimeMessage createMimeMessage(InputStream content) {
            throw new UnsupportedOperationException("not used by the adapter");
        }

        @Override
        public void send(MimeMessage mimeMessage) {
            if (onSend != null) {
                throw onSend;
            }
            lastSent = mimeMessage;
        }

        @Override
        public void send(MimeMessage... mimeMessages) {
            throw new UnsupportedOperationException("not used by the adapter");
        }

        @Override
        public void send(org.springframework.mail.SimpleMailMessage... simpleMessages) {
            throw new UnsupportedOperationException("not used by the adapter");
        }
    }

    @Test
    void aSuccessfulTransportDeliversAMimeMessageAndAnswersASentReceipt() throws Exception {
        StubSender sender = new StubSender();
        JavamailMailAdapter adapter = new JavamailMailAdapter(sender, "the-guide@example.com");

        MailPort.MailReceipt receipt = adapter.send("friend@example.com", "Welcome", "Hi");

        assertEquals("sent", receipt.outcome());
        assertNotNull(sender.lastSent, "the transport saw the message");
        assertEquals("friend@example.com",
                sender.lastSent.getRecipients(Message.RecipientType.TO)[0].toString());
        assertEquals("the-guide@example.com", sender.lastSent.getFrom()[0].toString());
    }

    @Test
    void transportRefusalSurfacesAsTheTypedUnavailableFailure() {
        StubSender sender = new StubSender();
        sender.onSend = new MailAuthenticationException("535 authentication failed");
        JavamailMailAdapter adapter = new JavamailMailAdapter(sender, "the-guide@example.com");

        AppException failure = assertThrows(AppException.class,
                () -> adapter.send("friend@example.com", "Welcome", "Hi"));

        assertEquals(FailureKind.UNAVAILABLE, failure.kind());
        assertEquals("service_unavailable", failure.code());
        assertTrue(failure.getMessage().contains("authentication failed"),
                "the relay's message travels — never swallowed");
    }

    @Test
    void theRealAdapterReportsLiveAndRefusesRuntimePostureChanges() {
        JavamailMailAdapter adapter = new JavamailMailAdapter(new StubSender(), "the-guide@example.com");

        assertEquals(Posture.LIVE, adapter.posture());
        assertFalse(adapter.adoptPosture(Posture.FAKE), "live is wiring, not a dial");
        assertFalse(adapter.adoptPosture(Posture.SANDBOX), "sandbox is a fake-only affordance");
        assertEquals(Posture.LIVE, adapter.posture());
    }
}
