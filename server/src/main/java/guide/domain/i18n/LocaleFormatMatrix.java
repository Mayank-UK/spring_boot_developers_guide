package guide.domain.i18n;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The locale format matrix — topic 27's formatting exhibit rendered from
 * JDK-only machinery (java.time and java.text localized styles backed by
 * CLDR, the Unicode locale database the JDK ships). One fixed instant and
 * one fixed number go through every localized formatter for every
 * supported locale, plus the zone contrast: the SAME instant and the SAME
 * display locale through two ZoneIds — the proof that locale and time zone
 * are independent axes (a German reader in Tokyo keeps German words and
 * gains Tokyo clocks).
 *
 * The currency row carries its own lesson: a language-only locale ("de")
 * names NO currency (CLDR answers XXX — currency belongs to a territory),
 * so the matrix maps each bundle locale to its likely-region counterpart
 * (de → de-DE → EUR) and formats with THAT. Real products go further and
 * make currency an account setting, never a locale inference — the
 * currency code rides along in the row so the demo can say so.
 */
public record LocaleFormatMatrix(
        String instant,
        double number,
        List<FormattedLocale> locales,
        List<ZonedMoment> zones) {

    /** One locale's row in the matrix. */
    public record FormattedLocale(
            String tag,
            String display,
            String date,
            String time,
            String dateTime,
            String currencyLocaleTag,
            String currency,
            String currencyCode,
            String decimal,
            String percent,
            String compact) {
    }

    /** The zone contrast: same instant, same display locale, two ZoneIds. */
    public record ZonedMoment(String zoneId, String offset, String dateTime) {
    }

    /**
     * Language-only bundle locales → the likely-region locale that names a
     * currency. This is exactly the CLDR "likely subtag" relation, spelled
     * out for teaching instead of reached through Locale extensions.
     */
    private static final Map<String, String> CURRENCY_LOCALES = Map.of(
            "en", "en-US",
            "de", "de-DE",
            "fr", "fr-FR",
            "ja", "ja-JP");

    /**
     * Renders the matrix. Every formatter is created WITH an explicit locale
     * (or .withLocale) — nothing reads the JVM default, so the output is a
     * pure function of the arguments and identical on every server.
     */
    public static LocaleFormatMatrix render(Instant instant, double number, List<Locale> locales,
            ZoneId primaryZone, ZoneId contrastZone, Locale zoneDisplayLocale) {
        List<FormattedLocale> rows = new ArrayList<>();
        for (Locale locale : locales) {
            ZonedDateTime moment = instant.atZone(primaryZone);
            NumberFormat currency = NumberFormat.getCurrencyInstance(
                    Locale.forLanguageTag(CURRENCY_LOCALES.getOrDefault(
                            locale.toLanguageTag(), locale.toLanguageTag())));
            NumberFormat decimal = NumberFormat.getNumberInstance(locale);
            decimal.setMinimumFractionDigits(3);
            decimal.setMaximumFractionDigits(3);
            NumberFormat compact = NumberFormat.getCompactNumberInstance(
                    locale, NumberFormat.Style.SHORT);
            compact.setMaximumFractionDigits(1);
            rows.add(new FormattedLocale(
                    locale.toLanguageTag(),
                    locale.getDisplayLanguage(locale),
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
                            .format(moment),
                    DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale)
                            .format(moment),
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
                            .format(moment),
                    CURRENCY_LOCALES.getOrDefault(locale.toLanguageTag(), locale.toLanguageTag()),
                    currency.format(number),
                    currency.getCurrency().getCurrencyCode(),
                    decimal.format(number),
                    NumberFormat.getPercentInstance(locale).format(0.891),
                    compact.format(number)));
        }
        DateTimeFormatter zoned = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(zoneDisplayLocale);
        List<ZonedMoment> zoneRows = new ArrayList<>();
        for (ZoneId zone : List.of(primaryZone, contrastZone)) {
            ZonedDateTime moment = instant.atZone(zone);
            zoneRows.add(new ZonedMoment(
                    zone.getId(),
                    moment.getOffset().getId(),
                    zoned.format(moment)));
        }
        return new LocaleFormatMatrix(instant.toString(), number, rows, zoneRows);
    }
}
