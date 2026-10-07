package guide.config;

import guide.data.thirdparty.FakeStripeAdapter;
import guide.data.thirdparty.InMemoryMailAdapter;
import guide.data.thirdparty.InMemoryObjectStore;
import guide.data.thirdparty.JavamailMailAdapter;
import guide.domain.thirdparty.ThirdPartyHub;
import guide.domain.thirdparty.ThirdPartyKeys;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Topic 43 (third-party) wiring — the thirdparty:hub demo's composition
 * root, and the topic's whole argument made executable:
 *
 * <ul>
 *   <li>every integration runs BEHIND A PORT (mail, payments, storage)
 *       with an in-process fake adapter — no network, no key, nothing to
 *       leak — flippable to SANDBOX semantics at runtime;</li>
 *   <li>ONE REAL adapter exists too: {@link JavamailMailAdapter} over a
 *       constructed {@link JavaMailSenderImpl}. It is wired to prove the
 *       context boots with a real adapter present (a drop-in behind the
 *       same port), and the demo NEVER calls it — live is a key-and-
 *       wiring decision, which is exactly why the adapter's posture dial
 *       refuses to flip;</li>
 *   <li>KEYS are read HERE, at the composition root, as VERDICTS: whether
 *       each env var is present. The values have no path into any bean
 *       this config serves — a set key is a fact, never a string the API
 *       can echo (topic 36's redaction discipline).</li>
 * </ul>
 */
@Configuration
public class ThirdPartyDemoConfig {

    /** The env var names the hub reports on — public knowledge, unlike their values. */
    static final String MAIL_KEY_VAR = "GUIDE_THIRDPARTY_MAIL_KEY";
    static final String STRIPE_KEY_VAR = "GUIDE_THIRDPARTY_STRIPE_KEY";
    static final String STORAGE_KEY_VAR = "GUIDE_THIRDPARTY_STORAGE_KEY";

    @Bean
    InMemoryMailAdapter inMemoryMailAdapter() {
        return new InMemoryMailAdapter();
    }

    @Bean
    FakeStripeAdapter fakeStripeAdapter() {
        return new FakeStripeAdapter();
    }

    @Bean
    InMemoryObjectStore inMemoryObjectStore() {
        return new InMemoryObjectStore();
    }

    /**
     * The REAL mail sender — CONSTRUCTED, never connected: JavaMailSender
     * opens its socket per send, never at construction, so wiring this
     * bean proves the drop-in boots while keeping the zero-network
     * guarantee. Host/port come from env with dev-relay defaults (the
     * port MailHog/Mailpit listen on locally); the timeouts are the
     * lesson — an integration without timeouts is an outage scheduled for
     * your busiest hour.
     */
    @Bean
    JavaMailSenderImpl thirdPartyMailSender(Environment environment) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(environment.getProperty("GUIDE_THIRDPARTY_SMTP_HOST", "localhost"));
        sender.setPort(environment.getProperty("GUIDE_THIRDPARTY_SMTP_PORT", Integer.class, 3025));
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "2000");
        sender.getJavaMailProperties().put("mail.smtp.timeout", "5000");
        sender.getJavaMailProperties().put("mail.smtp.writetimeout", "5000");
        return sender;
    }

    /** The real adapter behind the same port the fake serves — present, never called. */
    @Bean
    JavamailMailAdapter javamailMailAdapter(JavaMailSenderImpl thirdPartyMailSender) {
        return new JavamailMailAdapter(thirdPartyMailSender, "the-guide@example.com");
    }

    /** Key verdicts from the real Environment: present-or-not, never the value. */
    @Bean
    ThirdPartyKeys thirdPartyKeys(Environment environment) {
        return ThirdPartyKeys.of(
                new ThirdPartyKeys.KeyState("mail", MAIL_KEY_VAR,
                        environment.getProperty(MAIL_KEY_VAR) != null),
                new ThirdPartyKeys.KeyState("payments", STRIPE_KEY_VAR,
                        environment.getProperty(STRIPE_KEY_VAR) != null),
                new ThirdPartyKeys.KeyState("storage", STORAGE_KEY_VAR,
                        environment.getProperty(STORAGE_KEY_VAR) != null));
    }

    @Bean
    ThirdPartyHub thirdPartyHub(InMemoryMailAdapter mail, FakeStripeAdapter payments,
            InMemoryObjectStore storage, ThirdPartyKeys keys) {
        return new ThirdPartyHub(mail, payments, storage, keys, Map.of(
                "mail", "a REAL JavaMailSender-backed adapter (posture live) is constructed in "
                        + "this context — this trigger routes to the fake; nothing in this demo "
                        + "can open a socket"));
    }
}
