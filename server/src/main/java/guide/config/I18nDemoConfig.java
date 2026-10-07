package guide.config;

import guide.domain.i18n.LocaleNegotiator;
import guide.domain.i18n.MessageChainProbe;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Topic 27 (internationalization) wiring — the deliberate composition root
 * for locale negotiation. Boot ALREADY runs an AcceptHeaderLocaleResolver
 * (the dispatcher's default when no LocaleResolver bean exists), but the
 * teaching posture this topic advocates is the EXPLICIT one: declare the
 * bean, name the supported locales, name the default — so the answer to
 * "what does a zh-CN request get?" is one readable bean method, not
 * autoconfiguration archaeology. Defining this bean makes Boot's
 * conditional localeResolver back off (the autoconfig is
 * &#64;ConditionalOnMissingBean on exactly this bean name).
 *
 * The MessageSource needs NO bean here on purpose: Boot's
 * MessageSourceAutoConfiguration builds it from spring.messages.* in
 * application.yml (basename, encoding, fallback-to-system-locale) — the
 * canonical path this topic quotes and teaches. The negotiator and chain
 * probe are the topic's pure domain instruments, exposed as beans so the
 * controller stays at wiring-and-mapping only.
 */
@Configuration
public class I18nDemoConfig {

    /** What this application promises to serve — the whole i18n contract in one list. */
    public static final List<Locale> SUPPORTED_LOCALES = List.of(
            Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, Locale.JAPANESE);

    /** The answer when nothing matches (and the base bundle's language). */
    public static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    /** Where the message bundles live (classpath), per spring.messages.basename. */
    public static final String MESSAGES_BASENAME = "messages/messages";

    /**
     * The negotiator of record: reads Accept-Language, two-pass lookup
     * against SUPPORTED_LOCALES, falls back to DEFAULT_LOCALE. Requests for
     * unsupported locales (zh-CN, es) land on English — never a 406, never
     * a guess; the header is a preference, not a command.
     */
    @Bean
    LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(SUPPORTED_LOCALES);
        resolver.setDefaultLocale(DEFAULT_LOCALE);
        return resolver;
    }

    /** The pure negotiation trace the demo renders beside the resolver's answer. */
    @Bean
    LocaleNegotiator localeNegotiator() {
        return new LocaleNegotiator(
                SUPPORTED_LOCALES.stream().map(Locale::toLanguageTag).toList(),
                DEFAULT_LOCALE.toLanguageTag());
    }

    /** The bundle-chain prober wired to the same basename the MessageSource uses. */
    @Bean
    MessageChainProbe messageChainProbe() {
        return new MessageChainProbe(MESSAGES_BASENAME);
    }
}
