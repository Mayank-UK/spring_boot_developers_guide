package guide.domain.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The format matrix as SNAPSHOTS: one fixed instant (2026-10-06T12:34:56Z)
 * and one fixed number (1234567.891) through every localized formatter,
 * with the exact strings this JDK's CLDR produces pinned per locale. Every
 * formatter is created with an EXPLICIT locale, so the output is a pure
 * function of the arguments — the no-default-locale-leakage test flips the
 * JVM default mid-render and asserts the bytes do not move.
 */
class LocaleFormatMatrixTest {

    private static final Instant INSTANT = Instant.parse("2026-10-06T12:34:56Z");
    private static final double NUMBER = 1234567.891;

    private Locale originalDefault;

    @BeforeEach
    void rememberDefaultLocale() {
        originalDefault = Locale.getDefault();
    }

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefault);
    }

    private LocaleFormatMatrix.FormattedLocale row(String tag) {
        LocaleFormatMatrix matrix = LocaleFormatMatrix.render(
                INSTANT, NUMBER,
                List.of(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, Locale.JAPANESE),
                ZoneId.of("Europe/Berlin"), ZoneId.of("Asia/Tokyo"), Locale.GERMAN);
        return matrix.locales().stream()
                .filter(row -> row.tag().equals(tag))
                .findFirst().orElseThrow();
    }

    @Test
    void englishRow() {
        LocaleFormatMatrix.FormattedLocale en = row("en");
        assertEquals("English", en.display());
        assertEquals("October 6, 2026", en.date());
        assertEquals("2:34:56\u202FPM", en.time());
        assertEquals("Oct 6, 2026, 2:34:56\u202FPM", en.dateTime());
        assertEquals("en-US", en.currencyLocaleTag());
        assertEquals("$1,234,567.89", en.currency());
        assertEquals("USD", en.currencyCode());
        assertEquals("1,234,567.891", en.decimal());
        assertEquals("89%", en.percent());
        assertEquals("1.2M", en.compact());
    }

    @Test
    void germanRow() {
        LocaleFormatMatrix.FormattedLocale de = row("de");
        assertEquals("Deutsch", de.display());
        assertEquals("6. Oktober 2026", de.date());
        assertEquals("14:34:56", de.time());
        assertEquals("06.10.2026, 14:34:56", de.dateTime());
        assertEquals("de-DE", de.currencyLocaleTag());
        assertEquals("1.234.567,89\u00A0\u20AC", de.currency());
        assertEquals("EUR", de.currencyCode());
        assertEquals("1.234.567,891", de.decimal());
        assertEquals("89\u00A0%", de.percent());
        assertEquals("1,2\u00A0Mio.", de.compact());
    }

    @Test
    void frenchRowUsesNarrowNoBreakSpacing() {
        LocaleFormatMatrix.FormattedLocale fr = row("fr");
        assertEquals("fran\u00E7ais", fr.display());
        assertEquals("6 octobre 2026", fr.date());
        assertEquals("14:34:56", fr.time());
        assertEquals("6 oct. 2026, 14:34:56", fr.dateTime());
        assertEquals("fr-FR", fr.currencyLocaleTag());
        assertEquals("1\u202F234\u202F567,89\u00A0\u20AC", fr.currency());
        assertEquals("EUR", fr.currencyCode());
        assertEquals("1\u202F234\u202F567,891", fr.decimal());
        assertEquals("89\u00A0%", fr.percent());
        assertEquals("1,2\u00A0M", fr.compact());
    }

    @Test
    void japaneseRowCarriesYearEraKanjiAndFullwidthYen() {
        LocaleFormatMatrix.FormattedLocale ja = row("ja");
        assertEquals("\u65E5\u672C\u8A9E", ja.display());
        assertEquals("2026\u5E7410\u67086\u65E5", ja.date());
        assertEquals("14:34:56", ja.time());
        assertEquals("2026/10/06 14:34:56", ja.dateTime());
        assertEquals("ja-JP", ja.currencyLocaleTag());
        // JPY carries no minor unit: the fraction ROUNDS AWAY, not truncates.
        assertEquals("\uFFE51,234,568", ja.currency());
        assertEquals("JPY", ja.currencyCode());
        assertEquals("1,234,567.891", ja.decimal());
        assertEquals("89%", ja.percent());
        // Japanese compact notation groups by ten-thousand (万), not thousands.
        assertEquals("123.5\u4E07", ja.compact());
    }

    @Test
    void theZoneContrastMovesTheClockButNotTheWords() {
        LocaleFormatMatrix matrix = LocaleFormatMatrix.render(
                INSTANT, NUMBER,
                List.of(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, Locale.JAPANESE),
                ZoneId.of("Europe/Berlin"), ZoneId.of("Asia/Tokyo"), Locale.GERMAN);
        assertEquals(2, matrix.zones().size());
        assertEquals("Europe/Berlin", matrix.zones().get(0).zoneId());
        assertEquals("+02:00", matrix.zones().get(0).offset());
        assertEquals("06.10.2026, 14:34:56", matrix.zones().get(0).dateTime());
        assertEquals("Asia/Tokyo", matrix.zones().get(1).zoneId());
        assertEquals("+09:00", matrix.zones().get(1).offset());
        assertEquals("06.10.2026, 21:34:56", matrix.zones().get(1).dateTime());
        // October 6 2026: Berlin is still on summer time (CEST, +2) — the
        // DST trap the time-zones-vs-locales section teaches, visible here.
    }

    @Test
    void renderingIgnoresTheJvmDefaultLocale() {
        Locale.setDefault(Locale.FRENCH);
        LocaleFormatMatrix.FormattedLocale en = row("en");
        assertEquals("October 6, 2026", en.date());
        assertEquals("$1,234,567.89", en.currency());
        assertEquals("1.2M", en.compact());
    }
}
