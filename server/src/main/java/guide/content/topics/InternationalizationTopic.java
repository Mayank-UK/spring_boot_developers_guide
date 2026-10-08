package guide.content.topics;

import static guide.content.topics.TopicBlocks.code;
import static guide.content.topics.TopicBlocks.demo;
import static guide.content.topics.TopicBlocks.section;
import static guide.content.topics.TopicBlocks.text;

import guide.content.ContentSection;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;

/**
 * Topic 27 — internationalization. Group internationalization, order 1.
 * Taught from THIS repository's own machinery: the pure Accept-Language
 * parser and two-pass negotiator in domain/i18n (heavily tested against
 * the malformed-header traps), the AcceptHeaderLocaleResolver configured
 * explicitly in I18nDemoConfig, four real message bundles under
 * resources/messages with a base-bundle fallback chain probed live, and
 * the locale format matrix rendered from a fixed instant and number.
 * One live demo: i18n:negotiator (header builder → trace + greeting +
 * formatting matrix).
 */
public final class InternationalizationTopic {

    private InternationalizationTopic() {
    }

    public static Topic topic() {
        return new Topic("internationalization", "Internationalization",
                "Global by default: Accept-Language negotiation, MessageSource "
                        + "bundles with their fallback chain, and locale-aware "
                        + "formatting — taught from this guide's own parser, "
                        + "resolver, bundles, and format matrix, with locale, "
                        + "time zone, and currency kept as the independent axes "
                        + "they are.",
                TopicGroupId.INTERNATIONALIZATION, 1,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("global-by-default", "Global by default",
                        "UTF-8 end to end — database, JSON, headers — and the three axes everyone conflates (locale, time zone, currency) pulled apart once and for all.",
                        text("glob:promise", """
                                INTERNATIONALIZATION is not a feature you add when the second country signs; it is defaults you either baked in early or retrofit painfully. The cheap version is small: keep every layer UTF-8, keep user-visible copy out of code, keep dates and money in unambiguous storage forms, and let the EDGES convert. This guide's own substrate made those choices in earlier topics, and this one names them: the PostgreSQL schema stores text in TEXT and timestamps in TIMESTAMP WITH TIME ZONE (topic 16's type choices — encoding-neutral, storing structure rather than presentation), Jackson serializes JSON as UTF-8 by default, and the servlet layer speaks headers whose payloads carry UTF-8 bytes. The one place encoding still bites in practice is .properties files — historically ISO-8859-1 with \\uXXXX escapes — and the bundle section shows this repo's literal-UTF-8 stance and the JDK version that earned it."""),
                        text("glob:axes", """
                                The conceptual table this topic keeps returning to: LOCALE, TIME ZONE, and CURRENCY are three INDEPENDENT axes, and every classic i18n bug is two of them collapsed into one. A LOCALE (BCP 47 tag like de-AT) names language and region conventions: which words, which date format, which decimal separator. A TIME ZONE (IANA ZoneId like Europe/Vienna) names a UTC offset and its daylight-saving history: what the clock reads at one absolute moment. A CURRENCY names a unit of account (EUR, JPY) with its own minor-unit scale — the yen has none. A user is a POINT in each axis independently: an Austrian German speaker in Tokyo paying in euros is locale de-AT, zone Asia/Tokyo, currency EUR — three answers to three different questions. Collapse locale and zone ("Germans get Berlin time") and your Berlin user in Tokyo sees wrong clocks; collapse locale and currency ("Germans get euros") and your German-in-Japan invoice is wrong by an order of magnitude; collapse zone and a stored timestamp ("store local time") and every DST transition corrupts your data twice a year. The rest of this topic is the machinery for the first axis plus the discipline for the other two: negotiation decides the locale, bundles localize the words, formatters localize the numbers and dates, and the time-zones section proves the zone rides along separately."""),
                        text("glob:server-says", """
                                The other half of "global by default" is WHO DECIDES. For an API, the honest split is: the CLIENT STATES A PREFERENCE, the SERVER DECIDES WHAT IT SERVES. Accept-Language (this topic's header) is a preference list, never a command — a zh-CN visitor of this guide gets English, politely and by design, because the server enumerates exactly what it can serve. The same stance covers HTTP content negotiation generally (topic 03 met it for media types): the server's vocabulary is a contract, the client's header is a hint, and the negotiation is the deterministic function between them that this topic builds, tests, and renders inspectable in its live demo.""")),
                section("accept-language-negotiation", "Accept-Language negotiation",
                        "The header's grammar — language ranges, q-values, wildcards, case — parsed by this repo's own hostile-input-safe parser, scored by a two-pass negotiator, and chosen by the configured AcceptHeaderLocaleResolver.",
                        text("neg:grammar", """
                                Browsers build Accept-Language from the user's preferences and send it on every request; curl can send anything — which is why the parser treats it as hostile input. The grammar (RFC 4647 basic filtering, RFC 9110 weighting) is one comma-separated list of SEGMENTS, each a language range with an optional WEIGHT: de-DE,fr;q=0.8,en;q=0.5 says "German first (weight 1 implied), French at 0.8, English at 0.5". A range is 1-8 letters then hyphen-separated subtags (de-AT, zh-Hans) or the WILDCLOUD * ("anything"); tags are case-insensitive by spec (EN-gb is legal and means en-GB — normalize, never reject, on case); q is a fraction 0..1 where 0 means "explicitly not acceptable". Real headers arrive with empty segments (",,de,"), junk parameters (de;charset=utf-8 — only q= is defined), unparseable weights (q=OOPS), out-of-range weights (q=1.5), and duplicates — and the correct behavior on all of them is the same: SKIP the bad segment, NAME what you skipped, keep the good ones, never throw. This guide implements that contract as a pure record in domain/i18n — no Spring imports, tested by a parser battery of exactly those traps:"""),
                        code("neg:parser", "java",
                                "guide/domain/i18n/ParseAcceptLanguage.java — the parser's core loop: skip-and-name the junk, keep the good segments",
                                "server/src/main/java/guide/domain/i18n/ParseAcceptLanguage.java", """
                                public static ParseAcceptLanguage parse(String header) {
                                    if (header == null) {
                                        return new ParseAcceptLanguage(List.of(), false, List.of());
                                    }
                                    List<Candidate> candidates = new ArrayList<>();
                                    List<String> skipped = new ArrayList<>();
                                    // …
                                    String[] segments = header.split(",", -1);
                                    for (int index = 0; index < segments.length; index++) {
                                        String segment = segments[index].trim();
                                        String label = "segment " + (index + 1);
                                        if (segment.isEmpty()) {
                                            skipped.add(label + " \\"\\": empty segment");
                                            continue;
                                        }
                                        String[] parts = segment.split(";", -1);
                                        String rawTag = parts[0].trim();
                                        Double quality = parseQuality(parts, label, skipped);
                                        if (quality == null) {
                                            continue;
                                        }
                                        // … tag validation, case normalization, duplicate first-wins
                                    }
                                    return new ParseAcceptLanguage(candidates, true, skipped);
                                }

                                public List<Candidate> byQuality() {
                                    return candidates.stream()
                                            .sorted(Comparator.comparingDouble(Candidate::quality).reversed())
                                            .toList();
                                }
                                """),
                        text("neg:algorithm", """
                                NEGOTIATION is then a deliberately small deterministic function from the parsed candidates to ONE served locale — small enough to draw, small enough to test exhaustively. This repo's version runs strictly ordered passes: first EXACT (each candidate in quality order against each supported locale in list order — "de" matches "de"), then LANGUAGE-ONLY (de-AT serves "de": a German speaker from Austria accepts the German bundle), then the configured DEFAULT. Two stances are pinned in tests because stacks differ on them: quality 0 candidates are excluded outright (RFC: "not acceptable"), and the wildcard * matches nothing specifically — it signals "any acceptable", which the default already expresses. And one honest nuance: Spring's resolver interleaves per preference (for EACH requested locale in order it takes an exact or language match before considering the next), so on mixed headers the two disagree — "de-DE,fr;q=0.8" answers fr under our ordered passes (exact outranks language) and de under Spring (de-DE is the first preference). The full trace — candidates, scores, winner, reason — is the demo's artifact, rendered beside the resolver's own answer so the divergence is visible: negotiation is a POLICY, and the pinning test is the contract."""),
                        code("neg:negotiator", "java",
                                "guide/domain/i18n/LocaleNegotiator.java — the two-pass lookup with its trace",
                                "server/src/main/java/guide/domain/i18n/LocaleNegotiator.java", """
                                public Negotiation negotiate(ParseAcceptLanguage parsed) {
                                    List<ParseAcceptLanguage.Candidate> ranked = acceptable(parsed);
                                    for (ParseAcceptLanguage.Candidate candidate : ranked) {
                                        for (String tag : supported) {
                                            if (candidate.tag().equals(tag)) {
                                                return negotiation(parsed, tag, MatchKind.EXACT,
                                        "exact match on candidate " + candidate.tag()
                                                + " (q=" + candidate.quality() + ")");
                                            }
                                        }
                                    }
                                    // … the LANGUAGE pass has the same shape, matching languageOf(tag)
                                    return negotiation(parsed, defaultTag, MatchKind.DEFAULT,
                                            "no candidate matched a supported locale — the configured default answers");
                                }
                                """),
                        text("neg:resolver", """
                                The RESOLVER is where this meets Spring MVC. The DispatcherServlet resolves every request's locale through one LocaleResolver bean, and Boot's default is already AcceptHeaderLocaleResolver — but the posture worth copying is the EXPLICIT one: declare the bean, enumerate the supported locales, name the default, so "what does a zh-CN request get?" has a one-method answer instead of autoconfiguration archaeology. The bean below is this guide's, verbatim — note what it does NOT do: no session, no cookie, no redirect; a stateless API negotiates per request from the header, full stop (server-driven, the client's preference respected within the server's promise):"""),
                        code("neg:resolver-bean", "java",
                                "guide/config/I18nDemoConfig.java — the explicit resolver: supported locales and the default, in one readable bean",
                                "server/src/main/java/guide/config/I18nDemoConfig.java", """
                                @Bean
                                LocaleResolver localeResolver() {
                                    AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
                                    resolver.setSupportedLocales(SUPPORTED_LOCALES);
                                    resolver.setDefaultLocale(DEFAULT_LOCALE);
                                    return resolver;
                                }
                                """),
                        text("neg:server-vs-client", """
                                SERVER-DRIVEN versus CLIENT-DRIVEN negotiation is the last distinction this section owes. What you have just read is server-driven: same URL, same route, the header picks the representation — right for APIs and server-rendered pages. The client-driven alternative — /?lang=de, /en/about versus /de/ueber, or a cookie that outlives the request — buys shareable localized links and a preference that survives header-less clients, at the price of a second resolver (CookieLocaleResolver, LocaleChangeInterceptor) and a URL policy. Real products run both: header negotiation for the API, a settings-backed choice for the account, on the SAME supported-locale list. The live demo exercises all of it — build the header, watch the parse, the scores, the winner, and the resolver's verdict:"""),
                        demo("neg:demo", "i18n:negotiator",
                                "The i18n negotiator — build an Accept-Language, watch it negotiate",
                                "Compose an Accept-Language from locale rows with q-values (or pick a preset: browser-like, wildcard, not-acceptable, or a deliberately hostile one). Negotiate to see the parsed candidates, the skipped junk, the two-pass score matrix against the supported locales, and our winner beside the resolver's actual choice — they agree on simple headers and honestly diverge on mixed ones. Then greet: the built header rides the request and the server answers in the locale it chose, with the bundle chain exposed. Finally render the format matrix — the same fixed instant and number through every locale's formatters, plus the two-zone contrast."),
                        code("neg:client", "ts",
                                "client/src/ui/demos/i18n/negotiator_demo.tsx — the builder: one header string from the rows, q-suffixed when the weight differs from 1",
                                "client/src/ui/demos/i18n/negotiator_demo.tsx", """
                                function buildHeader(rows: Row[]): string {
                                  return rows
                                    .map((row) => {
                                      const tag = row.tag.trim();
                                      if (tag === '') return '';
                                      return row.q.trim() !== '' && row.q.trim() !== '1' ? `${tag};q=${row.q.trim()}` : tag;
                                    })
                                    .filter((segment) => segment !== '')
                                    .join(', ');
                                }
                                """)),
                section("messagesource-localized-content", "MessageSource and localized content",
                        "The four bundles this guide actually ships, the basename config that binds them, parameterized messages, and the fallback chain — probed level by level.",
                        text("msg:bundles", """
                                Localized copy lives in MESSAGE BUNDLES: .properties files named <basename>_<locale>.properties, one key per string, resolved by java.util.ResourceBundle under Spring's MessageSource. This guide ships five real files under server/src/main/resources/messages/ — a complete BASE bundle (messages.properties, the English fallback floor) plus de, en, fr, and ja — and the base file teaches the file format by being one. The contract: comments start with #; the key/value split happens on the first unescaped = or :, so colons inside values are safe; values are literal UTF-8 here (JDK 9+ PropertyResourceBundle reads UTF-8, and Boot's MessageSource sets the same encoding — the \\uXXXX escape era is why older bundles look like \\u00fc soup, and escapes still work when a toolchain demands them):"""),
                        code("msg:base-bundle", "properties",
                                "server/src/main/resources/messages/messages.properties — the base bundle: complete English, the fallback floor",
                                "server/src/main/resources/messages/messages.properties", """
                                demo.i18n.app.title=The Spring Boot Developer's Guide
                                demo.i18n.greeting.welcome=Welcome, {0}!
                                demo.i18n.greeting.returning=Welcome back, {0}! Your last visit was {1,date,medium}.
                                demo.i18n.messages.unread={0,choice,0#You have no unread messages|1#You have one unread message|1<You have {0} unread messages}
                                """),
                        code("msg:ja-bundle", "properties",
                                "server/src/main/resources/messages/messages_ja.properties — literal UTF-8 Japanese, no escapes",
                                "server/src/main/resources/messages/messages_ja.properties", """
                                demo.i18n.app.title=Spring Boot 開発者ガイド
                                demo.i18n.app.tagline=ひとつのサーバー、読者それぞれの言語
                                demo.i18n.greeting.welcome=ようこそ、{0}さん!
                                demo.i18n.messages.unread={0,choice,0#未読メッセージはありません|1#未読メッセージが1件あります|1<未読メッセージが{0}件あります}
                                """),
                        text("msg:basename", """
                                The BASENAME binds the bundles to the application: Boot's MessageSourceAutoConfiguration builds the MessageSource bean from spring.messages.* — no code — and this repo's one flagged append points it at the directory-qualified messages/messages. Two of the three switches are load-bearing behavior, not ceremony. fallback-to-system-locale: false kills the CLASSIC production bug: with the default true, a locale that has no bundle falls back to the JVM'S default locale, so the same build answers Spanish visitors in English on one server and in German on another, wherever the container's locale differs; false pins the fallback floor to the base bundle — same build, same answer, every server (the probe test proves it by flipping the JVM default mid-run). And use-code-as-default-message stays false so a missing key fails loud in development instead of shipping its own key name to users:"""),
                        code("msg:yml", "yaml",
                                "server/src/main/resources/application.yml — the flagged append: basename + the fallback-floor switch",
                                "server/src/main/resources/application.yml", """
                                spring:
                                  # …
                                  messages:
                                    basename: messages/messages
                                    fallback-to-system-locale: false
                                    encoding: UTF-8
                                """),
                        text("msg:parameters", """
                                PARAMETERIZED messages are where bundles stop being dictionaries: {0} placeholders are interpolated by java.text.MessageFormat, which also formats dates and numbers inline ({1,date,medium} — with a java.util.Date argument, because MessageFormat predates java.time and still refuses Instant) and approximates plurals with choice patterns (the unread-count key above: 0/1/many branches, per locale). Two traps live exactly here and both are pinned by tests. THE APOSTROPHE TRAP: MessageFormat treats a lone single quote as the escape that opens a quoted literal, so the French "Tu n'as aucun message" with a bare apostrophe would swallow the following text and never substitute {0} — French values must double their apostrophes (n''as), and the parameterless fetch path (args null) skips MessageFormat entirely, which is why the trap only bites strings that take parameters. THE PLURAL LIMIT: choice patterns branch on numeric thresholds, not on grammar — ICU's plural rules (next section) are the real tool. The French bundle carries both lessons visibly:"""),
                        code("msg:fr-bundle", "properties",
                                "server/src/main/resources/messages/messages_fr.properties — doubled apostrophes in every parameterized value: the MessageFormat quote escape",
                                "server/src/main/resources/messages/messages_fr.properties", """
                                demo.i18n.messages.unread={0,choice,0#Tu n''as aucun message non lu|1#Tu as un message non lu|1<Tu as {0} messages non lus}
                                demo.i18n.error.not_found=Cette page n''existe pas : {0}
                                demo.i18n.error.validation=La valeur « {0} » n''est pas valide.
                                """),
                        text("msg:fallback-chain", """
                                The FALLBACK CHAIN is ResourceBundle's parent walk made visible: resolving a key for fr tries messages_fr.properties, then falls through to the base messages.properties; a locale-suffixed hit always wins over the base; a key that exists only in the base is served from the base to EVERY locale (English as the degradation floor, never an exception). Normally the walk is invisible — you get a string and no story — so this guide carries a small pure instrument that renders it: MessageChainProbe resolves the bundle, walks getParent(), and reports each level as HIT or miss with the raw template. The greeting endpoint pairs it with the real MessageSource lookup — getMessage for the negotiated locale, and for the demo's fallback use case a key that exists nowhere but the base, walking de-miss → base-hit in the response body:"""),
                        code("msg:probe", "java",
                                "guide/domain/i18n/MessageChainProbe.java — the chain walk: candidate levels loaded fresh, first file that owns the key answers",
                                "server/src/main/java/guide/domain/i18n/MessageChainProbe.java", """
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
                                """),
                        code("msg:controller", "java",
                                "guide/presentation/demo/I18nDemoController.java — the greeting edge: MessageSource for the negotiated locale, NoSuchMessageException caught honestly",
                                "server/src/main/java/guide/presentation/demo/I18nDemoController.java", """
                                private GreetingView localized(GreetingRequest.UseCase useCase, String key, Locale locale,
                                        String note, Object... args) {
                                    MessageChainProbe.Chain chain = probe.probe(key, locale);
                                    String message = null;
                                    try {
                                        message = args.length == 0
                                                ? messageSource.getMessage(key, null, locale)
                                                : messageSource.getMessage(key, args, locale);
                                    } catch (NoSuchMessageException absentEverywhere) {
                                        // The MISSING case's whole point — the note (built by the caller)
                                        // explains the failure and the configured alternative.
                                    }
                                    // … the view carries message, answeringBundle, chain, note
                                }
                                """),
                        text("msg:discipline", """
                                The closing discipline is EXTERNALIZED-VERSUS-INLINE copy. Every user-visible string is a bundle key — labels, errors, empty states, tooltips, emails — because an inline literal ("Welcome back") is invisible to translators, unreviewable, and unfixable without a rebuild. The mechanical rule this repo models: keys as dotted, area-prefixed contracts (demo.i18n.error.not_found — noun phrases, stable, meaningless to users); parameters over concatenation ({0} unread, never count + " unread", because German rearranges the sentence around the number); whole sentences per key, never assembled from fragments (a "word assembler" cannot respect word order in any language but yours); and NO Java code in values beyond MessageFormat syntax. The same discipline runs in reverse for logs and metrics — infrastructure-facing text stays English and unlocalized, because your on-call engineers grep for it at 3 a.m.""")),
                section("formatting", "Formatting — the locale matrix",
                        "DateTimeFormatter localized styles and the NumberFormat family, rendered LIVE for all four locales from one fixed instant and number — with CLDR and ICU plurals in prose.",
                        text("fmt:styles", """
                                Numbers and dates are LOCALE DATA, not code: the JDK ships CLDR (the Unicode Common Locale Data Repository — the same database ICU, operating systems, and browsers use) and java.time/java.text expose it through localized styles. DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(de) is not "German date formatting code" — it is a QUERY into locale data that answers "how Germans write dates". The formatter carries two independent knobs people constantly fuse: the LOCALIZED STYLE (SHORT..FULL — the convention) and the LOCALE (whose convention). The same split runs through java.text: NumberFormat.getCurrencyInstance/getNumberInstance/getPercentInstance/getCompactNumberInstance, each localized per locale. This guide renders all of it live from one fixed teaching instant (2026-10-06T12:34:56Z) and one fixed number (1234567.891) — a pure function of the locale list, snapshot-tested, quoted in the matrix endpoint the demo renders:"""),
                        code("fmt:matrix", "java",
                                "guide/domain/i18n/LocaleFormatMatrix.java — the matrix render: explicit locales everywhere, currency via the likely-region locale",
                                "server/src/main/java/guide/domain/i18n/LocaleFormatMatrix.java", """
                                rows.add(new FormattedLocale(
                                        locale.toLanguageTag(),
                                        locale.getDisplayLanguage(locale),
                                        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
                                                .format(moment),
                                        // … time, date-time
                                        CURRENCY_LOCALES.getOrDefault(locale.toLanguageTag(), locale.toLanguageTag()),
                                        currency.format(number),
                                        currency.getCurrency().getCurrencyCode(),
                                        decimal.format(number),
                                        NumberFormat.getPercentInstance(locale).format(0.891),
                                        compact.format(number)));
                                """),
                        text("fmt:what-it-shows", """
                                Read the live matrix slowly — every cell is a lesson. DATES: "October 6, 2026" / "6. Oktober 2026" / "6 octobre 2026" / "2026年10月6日" — the Japanese convention counts by year-年 month-月 day-日. DECIMALS: the same 1234567.891 is "1,234,567.891" in English and Japanese but "1.234.567,891" in German and "1 234 567,891" in French — comma and period TRADE ROLES, and French separates groups with narrow no-break spaces (U+202F), copy-paste-safe typography your layout must not collapse. PERCENTS: "89%" versus "89 %". COMPACT: "1.2M" / "1,2 Mio." / "1,2 M" / "123.5万" — Japanese compact notation groups by TEN THOUSAND (万), not thousands, because the language counts in powers of 10^4; a "1.2M"-shaped assumption is a locale bug wearing a unit system. CURRENCY: ¥1,234,568 — the yen has NO minor unit, so the fraction rounds away entirely; and note the matrix formats currency with each language's likely-REGION locale (de → de-DE → EUR), because a language-only locale names no currency at all — CLDR answers XXX — currency belongs to a territory. Real products go one step further: currency is an ACCOUNT SETTING, never a locale inference."""),
                        text("fmt:icu-cldr", """
                                Two proper nouns organize everything above. CLDR is the DATA — the repository of locale conventions (date skeletons, number symbols, plural rules, currency names) that the JDK embeds and everyone from ICU to Chrome consumes; "use CLDR" mostly means "use the platform's localized formatters instead of your own string templates", and it silently updates with the JDK — pin the behavior you depend on in snapshot tests, as this repo does. ICU is the LIBRARY the JDK's text layer is built on — and the place to reach when you need what java.text does not expose. The headline case is PLURALS: java.text.MessageFormat's choice patterns branch on numeric thresholds, but GRAMMAR does not — English has two plural forms (one/other), French behaves differently at zero and one (0 and 1 both take the singular form), Arabic has six (zero, one, two, few, many, other), and Japanese has essentially none. ICU's MessageFormat adds a proper {count, plural, =0 {…} one {…} other {…}} construct whose CLDR-driven rules pick the branch per locale — the production answer once your choice pattern grows its third special case. The same ICU surface covers gender (select), embedded HTML edge cases, and the segmented-number skeletons behind compact notation. Rule of thumb: java.text for simple parameters, ICU the moment plurals or gender enter the sentence."""),
                        text("fmt:rule", """
                                The formatting rule that survives every framework churn: FORMAT AT THE EDGE, STORE AND TRANSPORT NEUTRALLY. API responses carry ISO-8601 instants and raw numbers; the client's formatters (or the server's view layer, for rendered pages) localize for display. A locale-formatted "06.10.2026" crossing an API boundary is corruption waiting for a parse — unparseable by anything that assumes the other convention, and ambiguous even to humans (October 6th or June 10th?). The same logic localizes nothing twice: pick the rendering layer once — this guide's format endpoint exists to TEACH the matrix (its JSON stays neutral; the client demo renders the localized table).""")),
                section("time-zones-vs-locales", "Time zones versus locales",
                        "The ZoneId independence proof from the format endpoint; store UTC, render per zone; the DST traps in prose.",
                        text("tz:proof", """
                                The proof this section promises is already in the demo's zone contrast: the format endpoint renders the SAME instant under the SAME display locale (German) through TWO ZoneIds — Europe/Berlin answers "06.10.2026, 14:34:56" and Asia/Tokyo answers "06.10.2026, 21:34:56". The German words did not change; the clock did; the instant is one. That is the whole independence claim, executable: a Locale answers "in which conventions do I render", a ZoneId answers "at which offset do I render this absolute moment", and neither can derive the other (there is no locale-for-a-zone: UTC is used by locales from en-GB to fr-FR; there is no zone-for-a-locale: German is spoken across nine zones). The rendering recipe follows mechanically — Instant.atZone(zone) then format WITH the locale — and the pairing comes from DIFFERENT sources in a real product: the locale from negotiation (this topic), the zone from the account or the browser (Intl API), never one from the other."""),
                        text("tz:storage", """
                                STORAGE discipline is the time-zone half of "format at the edge": store and transport UTC, convert at the display edge. This guide's own schema (topic 16) chose PostgreSQL TIMESTAMP WITH TIME ZONE — which, despite the name, stores a normalized UTC instant and is the honest container for absolute moments; the anti-pattern it rejects is TIMESTAMP WITHOUT TIME ZONE holding local wall-clock times, which becomes unreadable the moment "local" changes meaning (DST, a traveling user, a server migration). FUTURE wall-clock events (calendar reminders) are the deliberate exception — they are defined in a zone and must move when the zone's rules change — and java.time models the distinction exactly: Instant/ZonedDateTime for absolute moments, LocalDateTime for civil wall-clock fields you will pair with a zone later. Future-dated scheduling bugs ("the meeting moved an hour after the DST law changed") are IANA database updates, which is why JDK (and Postgres tzdata) updates are part of operating any time-aware service."""),
                        text("tz:dst-traps", """
                                The DST traps, named once so you recognize them: (1) AMBIGUOUS TIMES — at the autumn fallback, 02:30 occurs TWICE in Europe/Berlin; ZonedDateTime documents which copy it resolves to, so never accept "local time + zone" without a disambiguation rule for the overlap. (2) SKIPPED TIMES — at the spring forward, 02:30 never happens; naive local-time arithmetic can land on a nonexistent wall-clock reading. (3) DURATION VERSUS CALENDAR ARITHMETIC — 24 hours later is an Instant question (plus 24h, always); "tomorrow same time" is a ZonedDateTime question (plus 1 day = 23 or 25 physical hours across a transition) — mixing them schedules meetings an hour off, twice a year, for everyone in a DST zone. (4) TESTING AT A FIXED INSTANT — this topic's exhibit (October 6) sits inside European summer time, which is why Berlin shows +02:00; tests that use "now()" silently roam across transitions, so deterministic suites pin the instant, as the snapshot tests here do. The habit that prevents the whole family: instants in your head, zones at the edges, and a calendar library — never arithmetic — for anything human-scheduled.""")),
                section("translation-workflow", "The translation workflow",
                        "Keys as contracts, pseudo-localization for layout (one live key), machine-translation honesty, and the RTL checklist.",
                        text("tr:keys-contracts", """
                                A bundle key is a CONTRACT with people you will never meet: the translator who fills in messages_es.properties next quarter, and your own build six months from now. They are STABLE identifiers — renaming demo.i18n.greeting.welcome silently orphans every translation not updated in lockstep, so key renames are migrations, with grep across every bundle. They are MEANINGFUL — the key names the message's role (error.not_found), not its current English text (error.page404 rots the moment the copy changes). They are COMPLETE per sentence — parameters over fragments, because word order is a per-locale property. And the workflow is boring on purpose: the source-language bundle is authored in pull requests with the code that uses it; translation files go to translators as flat key lists with context notes; missing keys are visible (fail-loud in dev, base-bundle fallback in prod — the chain this topic probes); unused keys are deleted, because dead keys are translation spend."""),
                        text("tr:pseudo", """
                                PSEUDO-LOCALIZATION finds i18n layout bugs BEFORE any translator is hired: transform the source strings into accent-heavy, lengthened, padded pseudo-text and run the product. Accented characters (Wélçómé) expose assumptions that bytes are ASCII — collation, case-folding, fonts, and the API that silently drops them; 30-40% lengthening exposes clipped boxes and missing ellipsis, because translations genuinely run longer and your fixed-width UI does not; bracketing and padding exposes concatenations (the brackets show where a string starts and ends, so "[Welcome ]to you[!]" reveals three fragments glued in code); and full-width punctuation exposes layouts that never met a Japanese comma. This guide ships one live pseudo key — the greeting endpoint's pseudo use case renders it through the same chain as real copy:"""),
                        code("tr:pseudo-key", "properties",
                                "server/src/main/resources/messages/messages.properties — the pseudo-localization exhibit: accents, length, padding",
                                "server/src/main/resources/messages/messages.properties", """
                                demo.i18n.pseudo.layout=[Ẇéĺćóḿé, {0}! ──────────── ●●●]
                                """),
                        text("tr:mt-and-rtl", """
                                MACHINE TRANSLATION, honestly: it is excellent at first-drafting a fifth language and dangerous as an unreviewed pipeline — the words will be plausible and the register, terminology, and legal nuance wrong in ways only a speaker catches. The honest workflows are MT-plus-review for user-facing copy (a human approves every key) and MT-only for content you can afford rough. What MT does NOT change is the engineering: keys, parameters, plurals, and layout are your problem before the first word is translated. The RTL CHECKLIST, because right-to-left is a layout architecture, not a font swap — enable dir="rtl" at the document level and let logical CSS properties (margin-inline-start, text-align: start) flow, never left/right absolutes; mirror icons that carry direction (arrows, progress, breadcrumbs) and leave others (brand marks, media controls) alone; expect bidirectional text where RTL prose embeds Latin names and numbers, and keep strings whole so the Unicode bidi algorithm can work; test with a real RTL locale (ar or he) plus pseudo-localization, not by eyeballing; and audit third-party components — date pickers and charts are where RTL support goes to die. Arabic is a top-five web language, and this checklist is the whole difference between "internationalized" and "translated.""")),
                section("terminal-companion", "Terminal companion",
                        "The whole topic through curl: negotiate hostile headers, greet in all four locales, walk the fallback chain, and read the format matrix.",
                        text("term:setup", """
                                The server carries everything this topic taught, live. The tour speaks to a server on localhost:8080; every response is small enough to read raw. The skill it drills is deliberate Accept-Language sending — the -H flag is your locale switch, and the four bundles answer it. Start well-formed, then get hostile and watch the parser skip the junk, then walk the greeting cases, then read the matrix:"""),
                        code("term:negotiate", "bash",
                                "negotiate: a realistic header, then a hostile one", null, """
                                # the two-pass trace: candidates, scores, winner, the resolver's verdict
                                curl -s 'localhost:8080/api/demo/i18n/negotiate?header=de-DE,fr;q=0.8,en;q=0.5'

                                # hostile: mixed case, a junk weight, an empty segment, an out-of-range q
                                curl -s 'localhost:8080/api/demo/i18n/negotiate?header=EN-gb;q=1.0,zz-ZZ;q=OOPS,,fr;q=1.5'
                                """),
                        code("term:greeting", "bash",
                                "greet in all four locales (the -H flag is the locale switch), then walk the chain", null, """
                                # one parameterized message, four locales — read each "message" field
                                curl -s -H 'Accept-Language: de' 'localhost:8080/api/demo/i18n/greeting?name=Mak'
                                curl -s -H 'Accept-Language: fr' 'localhost:8080/api/demo/i18n/greeting?name=Mak'
                                curl -s -H 'Accept-Language: ja' 'localhost:8080/api/demo/i18n/greeting?name=Mak'

                                # the fallback chain: this key exists only in the base bundle
                                curl -s -H 'Accept-Language: de' 'localhost:8080/api/demo/i18n/greeting?useCase=fallback'

                                # the absent key: fail loud, and the note names the configured alternative
                                curl -s -H 'Accept-Language: en' 'localhost:8080/api/demo/i18n/greeting?useCase=missing'

                                # plurals through the French choice pattern (watch the apostrophe survive)
                                curl -s -H 'Accept-Language: fr' 'localhost:8080/api/demo/i18n/greeting?useCase=plural&count=0'
                                """),
                        code("term:format", "bash",
                                "the locale format matrix and the two-zone contrast", null, """
                                # dates, currency, decimals, compact — one fixed instant and number, four locales
                                curl -s localhost:8080/api/demo/i18n/format

                                # same instant, same display locale, two zones — the independence proof
                                curl -s localhost:8080/api/demo/i18n/format

                                # an unsupported locale lands on the default, by design
                                curl -s -H 'Accept-Language: zh-CN' localhost:8080/api/demo/i18n/negotiate
                                """),
                        text("term:what-to-notice", """
                                Four things to notice on the tour. The hostile negotiation returns 200 with three skipped segments and one surviving candidate — malformed input degrades the ANSWER, never the request itself. The German fallback greeting answers messages.properties with the chain printed — you can see de miss and the base hit. The missing case returns a body whose message field is absent but whose note names NoSuchMessageException and the use-code-as-default alternative — failure as documentation. And the format matrix's Japanese row shows 123.5万 and ¥1,234,568 — ten-thousands grouping and a currency with no minor unit, the two cells that convince any skeptic that locale data is real data.""")));
    }
}
