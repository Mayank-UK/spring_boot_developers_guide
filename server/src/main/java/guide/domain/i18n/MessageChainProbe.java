package guide.domain.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * The fallback-chain prober — the teaching instrument that makes
 * MessageSource resolution INSPECTABLE. {@link ResourceBundle} resolves a
 * key by walking a parent chain: messages_fr → messages (the base
 * bundle), and the resolution ORDER comes from the candidate-locale list
 * a {@link ResourceBundle.Control} computes. Normally that walk is
 * invisible — you get a string and no story. This probe renders the walk
 * itself: which bundle FILES exist for the locale (one per candidate
 * level), which file OWNS the key, and the raw template it carries
 * before MessageFormat ever interpolates it.
 *
 * Each level is loaded FRESH — a PropertyResourceBundle straight from the
 * classpath file, with no parent attached — because a parented bundle's
 * keySet and getString are parent-INCLUSIVE by contract, and a locale
 * whose copy is byte-identical to its parent's is indistinguishable from
 * an inherited one through the public API. A fresh file answers exactly:
 * containsKey is file membership, nothing else.
 *
 * Determinism: the {@link Control} returns null from getFallbackLocale,
 * so the JVM's default locale can NEVER enter the candidate chain — the
 * exact guarantee {@code spring.messages.fallback-to-system-locale: false}
 * buys in production, asserted by tests that flip the JVM default
 * mid-probe.
 */
public record MessageChainProbe(String basename) {

    /** The rendered walk: levels in resolution order, stopping at the owner. */
    public record Chain(List<Step> steps, String answeringBundle, String template) {
    }

    /** One bundle level: its file name, whether it owns the key, its raw template. */
    public record Step(String bundle, boolean hit, String template) {
    }

    /** The determinism guarantee: never consult the JVM's default locale. */
    private static final ResourceBundle.Control NO_FALLBACK = new ResourceBundle.Control() {
        @Override
        public Locale getFallbackLocale(String baseName, Locale locale) {
            return null;
        }
    };

    /** Probes one key's resolution chain for one locale. */
    public Chain probe(String key, Locale locale) {
        List<Step> steps = new ArrayList<>();
        for (Locale level : NO_FALLBACK.getCandidateLocales(basename, locale)) {
            ResourceBundle file = loadLevel(level);
            if (file == null) {
                continue;
            }
            // Fresh bundle, no parent: containsKey is EXACT file membership.
            boolean owns = file.containsKey(key);
            steps.add(new Step(bundleName(level), owns, owns ? file.getString(key) : null));
            if (owns) {
                return new Chain(steps, bundleName(level), file.getString(key));
            }
        }
        return new Chain(steps, null, null);
    }

    /**
     * Loads one candidate level's file fresh from the classpath — UTF-8 by
     * construction (the reader is explicit; the .properties-in-ISO-8859-1
     * era cannot sneak back in) — or null when that level has no file.
     */
    private ResourceBundle loadLevel(Locale level) {
        String resource = "/" + bundleName(level);
        try (InputStream stream = MessageChainProbe.class.getResourceAsStream(resource)) {
            return stream == null
                    ? null
                    : new PropertyResourceBundle(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return null;
        }
    }

    private String bundleName(Locale level) {
        return level.equals(Locale.ROOT)
                ? basename + ".properties"
                : basename + "_" + level.toLanguageTag() + ".properties";
    }
}
