package guide.domain.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fallback-chain prober pinned against the REAL bundles: a key owned
 * by the locale's bundle resolves at level one, the base-only teaching key
 * walks the chain to messages.properties, an absent key misses everywhere
 * — and none of it ever consults the JVM's default locale (the guarantee
 * spring.messages.fallback-to-system-locale=false buys in production).
 */
class MessageChainProbeTest {

    private static final String BASENAME = "messages/messages";

    private final MessageChainProbe probe = new MessageChainProbe(BASENAME);

    private Locale originalDefault;

    @BeforeEach
    void rememberDefaultLocale() {
        originalDefault = Locale.getDefault();
    }

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefault);
    }

    @Test
    void aKeyOwnedByTheLocaleBundleResolvesAtLevelOne() {
        MessageChainProbe.Chain chain = probe.probe("demo.i18n.greeting.welcome", Locale.FRENCH);
        assertEquals("messages/messages_fr.properties", chain.answeringBundle());
        assertEquals("Bienvenue, {0} !", chain.template());
        assertEquals(1, chain.steps().size());
        assertTrue(chain.steps().getFirst().hit());
    }

    @Test
    void theBaseOnlyKeyWalksTheChainToTheBaseBundle() {
        MessageChainProbe.Chain chain = probe.probe("demo.i18n.fallback.shared", Locale.GERMAN);
        assertEquals("messages/messages.properties", chain.answeringBundle());
        assertTrue(chain.template().startsWith("This sentence lives only in the base bundle"));
        assertEquals(2, chain.steps().size());
        assertFalse(chain.steps().get(0).hit());
        assertEquals("messages/messages_de.properties", chain.steps().get(0).bundle());
        assertTrue(chain.steps().get(1).hit());
        assertEquals("messages/messages.properties", chain.steps().get(1).bundle());
    }

    @Test
    void anAbsentKeyMissesEveryLevel() {
        MessageChainProbe.Chain chain = probe.probe("demo.i18n.absent.everywhere", Locale.JAPANESE);
        assertNull(chain.answeringBundle());
        assertNull(chain.template());
        assertTrue(chain.steps().stream().noneMatch(MessageChainProbe.Step::hit));
    }

    @Test
    void theJvmDefaultLocaleNeverLeaksIntoTheChain() {
        // The production trap: fallback-to-system-locale would answer a French
        // request from the GERMAN bundle here, just because the server's
        // container speaks German. The probe's control forbids it.
        Locale.setDefault(Locale.JAPANESE);
        MessageChainProbe.Chain chain = probe.probe("demo.i18n.fallback.shared", Locale.FRENCH);
        assertEquals("messages/messages.properties", chain.answeringBundle());
        assertTrue(chain.steps().stream().allMatch(step ->
                step.bundle().startsWith("messages/messages_fr") || step.bundle().equals("messages/messages.properties")));
        assertTrue(chain.steps().stream().noneMatch(step ->
                step.bundle().contains("_ja")));
    }
}
