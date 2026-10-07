package guide.presentation.demo;

import guide.config.I18nDemoConfig;
import guide.core.errors.AppException;
import guide.core.errors.UnavailableException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.core.scenario.ScenarioStore;
import guide.domain.i18n.GreetingRequest;
import guide.domain.i18n.LocaleFormatMatrix;
import guide.domain.i18n.LocaleNegotiator;
import guide.domain.i18n.MessageChainProbe;
import guide.domain.i18n.ParseAcceptLanguage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Demo endpoints for topic 27 (internationalization) — the negotiator:
 *
 * - GET /api/demo/i18n/negotiate?header=... → the full negotiation story
 *   for one raw Accept-Language value (the request's own header when the
 *   param is absent): our pure parser's candidates and skipped junk, the
 *   two-pass scores against the supported locales, the winning tag and
 *   why, AND the resolver's actual choice side by side (run against the
 *   same header via a request wrapper — the teaching artifact).
 * - GET /api/demo/i18n/greeting?useCase=welcome|returning|plural|fallback|
 *   missing|pseudo&name=&count= → MessageSource.getMessage for the
 *   negotiated locale across six bundle behaviors: parameterized copy,
 *   date parameters, choice patterns, the base-bundle fallback chain, the
 *   absent key's fail-loud NoSuchMessageException, and pseudo-localized
 *   layout copy. The chain probe renders which bundle level answered.
 * - GET /api/demo/i18n/format → the locale format matrix: one fixed
 *   instant and one fixed number through every localized formatter for
 *   every supported locale, plus the same-instant-two-zones contrast that
 *   proves locale and time zone are independent axes.
 *
 * Thin by the house rule; every endpoint is guarded by injectFailure first.
 */
@Tag(name = "Internationalization", description = "The i18n topic's negotiator: Accept-Language parsing "
        + "and negotiation, MessageSource content with its fallback chain, and the locale format matrix.")
@RestController
@RequestMapping("/api/demo/i18n")
public class I18nDemoController {

    /** The scenario area the failure-injection switch guards. */
    static final String AREA = "i18n";

    // The teaching exhibit's fixed inputs — deterministic by design, so the
    // matrix is a pure function of the locale list and snapshot-testable.
    private static final Instant TEACHING_INSTANT = Instant.parse("2026-10-06T12:34:56Z");
    private static final double TEACHING_NUMBER = 1234567.891;
    private static final Instant LAST_VISIT = Instant.parse("2026-09-12T09:00:00Z");
    private static final ZoneId PRIMARY_ZONE = ZoneId.of("Europe/Berlin");
    private static final ZoneId CONTRAST_ZONE = ZoneId.of("Asia/Tokyo");

    // The bundle keys the greeting cases resolve (see messages/*.properties).
    static final String KEY_WELCOME = "demo.i18n.greeting.welcome";
    static final String KEY_RETURNING = "demo.i18n.greeting.returning";
    static final String KEY_UNREAD = "demo.i18n.messages.unread";
    static final String KEY_FALLBACK = "demo.i18n.fallback.shared";
    static final String KEY_MISSING = "demo.i18n.absent.everywhere";
    static final String KEY_PSEUDO = "demo.i18n.pseudo.layout";

    /** One negotiation answer: parse, scores, winner, and the resolver's choice. */
    record NegotiateView(
            String header,
            boolean fromRequest,
            List<CandidateView> candidates,
            List<String> skipped,
            List<String> supported,
            List<ScoreView> scores,
            String winner,
            String kind,
            String reason,
            String resolverChose,
            boolean resolverAgrees) {
    }

    /** A parsed candidate with its serving rank (quality order, ties in header order). */
    record CandidateView(String tag, String language, String region, double quality,
            boolean wildcard, int rank) {
    }

    /** One supported locale's best outcome. */
    record ScoreView(String supported, double score, String matched, String kind) {
    }

    /** One localized answer: the message, the chain that produced it, the note. */
    record GreetingView(
            String useCase,
            String locale,
            String localeDisplay,
            String key,
            String message,
            String answeringBundle,
            String template,
            List<ChainStepView> chain,
            String note) {
    }

    /** One bundle level the resolution walked through. */
    record ChainStepView(String bundle, boolean hit, String template) {
    }

    /** The format matrix wrapper: rows, zone contrast, and the currency lesson. */
    record FormatView(String instant, double number, List<LocaleFormatMatrix.FormattedLocale> locales,
            List<LocaleFormatMatrix.ZonedMoment> zones, String note) {
    }

    private final LocaleResolver localeResolver;
    private final MessageSource messageSource;
    private final LocaleNegotiator negotiator;
    private final MessageChainProbe probe;
    private final ScenarioStore scenarioStore;

    public I18nDemoController(LocaleResolver localeResolver, MessageSource messageSource,
            LocaleNegotiator negotiator, MessageChainProbe probe, ScenarioStore scenarioStore) {
        this.localeResolver = localeResolver;
        this.messageSource = messageSource;
        this.negotiator = negotiator;
        this.probe = probe;
        this.scenarioStore = scenarioStore;
    }

    @Operation(summary = "Parse and negotiate one Accept-Language header", operationId = "negotiateLocale",
            description = "The full negotiation trace: candidates (q-values, wildcards, case-normalized), "
                    + "skipped malformed segments, two-pass scores against the supported locales, the winner "
                    + "and why — and the configured resolver's actual choice for the same header.")
    @GetMapping(value = "/negotiate", produces = "application/json")
    ResponseEntity<NegotiateView> negotiate(HttpServletRequest request,
            @RequestParam(required = false) String header) {
        requireAvailable();
        boolean fromRequest = header == null || header.isBlank();
        String analyzed = fromRequest ? request.getHeader("Accept-Language") : header.strip();
        ParseAcceptLanguage parsed = ParseAcceptLanguage.parse(analyzed);
        LocaleNegotiator.Negotiation negotiation = negotiator.negotiate(parsed);

        List<ParseAcceptLanguage.Candidate> ranked = parsed.byQuality();
        List<CandidateView> candidates = ranked.stream()
                .map(candidate -> new CandidateView(candidate.tag(), candidate.language(),
                        candidate.region(), candidate.quality(), candidate.wildcard(),
                        ranked.indexOf(candidate) + 1))
                .toList();
        List<ScoreView> scores = negotiation.scores().stream()
                .map(scored -> new ScoreView(scored.supported(), scored.score(),
                        scored.matched(), scored.kind().name()))
                .toList();
        Locale resolverChose = localeResolver.resolveLocale(wrapping(request, analyzed));
        String winner = negotiation.winner();
        String resolverTag = resolverChose.toLanguageTag();
        return ResponseEntity.ok(new NegotiateView(
                analyzed,
                fromRequest,
                candidates,
                parsed.skipped(),
                negotiator.supported(),
                scores,
                winner,
                negotiation.kind().name(),
                negotiation.reason(),
                resolverTag,
                winner.equals(resolverTag)));
    }

    @Operation(summary = "Localized content through MessageSource", operationId = "localizedGreeting",
            description = "Six use cases over the negotiated locale: welcome (a {0} parameter), returning "
                    + "(a {1,date} parameter), plural (a MessageFormat choice pattern), fallback (a key only "
                    + "the base bundle owns), missing (a key nowhere — the fail-loud NoSuchMessageException), "
                    + "and pseudo (pseudo-localized layout copy). Each answer carries the bundle chain walked.")
    @GetMapping(value = "/greeting", produces = "application/json")
    ResponseEntity<GreetingView> greeting(HttpServletRequest request,
            @RequestParam(required = false) String useCase,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer count) {
        requireAvailable();
        Result<GreetingRequest> judged = GreetingRequest.of(useCase, name, count);
        if (judged instanceof Failed<GreetingRequest> failed) {
            throw AppException.of(failed.failure());
        }
        GreetingRequest req = judged instanceof Success<GreetingRequest> success
                ? success.value()
                : null;
        Locale locale = localeResolver.resolveLocale(request);
        return ResponseEntity.ok(switch (req.useCase()) {
            case WELCOME -> localized(req.useCase(), KEY_WELCOME, locale, note(
                    "MessageSource.getMessage for the negotiated locale; {0} interpolated by MessageFormat."),
                    req.name());
            case RETURNING -> localized(req.useCase(), KEY_RETURNING, locale, note(
                    "Two parameters: {0} a string, {1,date,medium} a java.util.Date — java.time types are "
                            + "not accepted by java.text.MessageFormat, so the controller converts the "
                            + "stored Instant at the edge."),
                    req.name(), Date.from(LAST_VISIT));
            case PLURAL -> localized(req.useCase(), KEY_UNREAD, locale, note(
                    "A java.text.MessageFormat choice pattern approximates plurals (0/1/many branches per "
                            + "locale); ICU plural rules — one/other/many/few by cardinality — are the "
                            + "production tool, taught in the formatting section."),
                    req.count());
            case FALLBACK -> localized(req.useCase(), KEY_FALLBACK, locale, note(
                    "The key exists ONLY in the base bundle: the requested locale's bundle misses and the "
                            + "chain falls through to messages.properties — English as the degradation "
                            + "floor, never an exception."));
            case MISSING -> localized(req.useCase(), KEY_MISSING, locale, note(
                    "The key exists in no bundle: NoSuchMessageException is the fail-loud default. "
                            + "spring.messages.use-code-as-default-message=true would return the key "
                            + "itself (\"??\" behavior) — a last-resort production guard, not a habit."));
            case PSEUDO -> localized(req.useCase(), KEY_PSEUDO, locale, note(
                    "Pseudo-localized copy: accents, length, and padding expose hardcoded strings and "
                            + "clipped layouts BEFORE real translations arrive. Locale-independent by "
                            + "design — it tests the box, not the language."),
                    req.name());
        });
    }

    @Operation(summary = "The locale format matrix", operationId = "formatMatrix",
            description = "One fixed instant and one fixed number through every localized formatter "
                    + "(date, time, date-time, currency, decimal, percent, compact) for every supported "
                    + "locale, plus the same instant rendered in two time zones under one display locale.")
    @GetMapping(value = "/format", produces = "application/json")
    ResponseEntity<FormatView> format() {
        requireAvailable();
        LocaleFormatMatrix matrix = LocaleFormatMatrix.render(
                TEACHING_INSTANT, TEACHING_NUMBER, I18nDemoConfig.SUPPORTED_LOCALES,
                PRIMARY_ZONE, CONTRAST_ZONE, Locale.GERMAN);
        return ResponseEntity.ok(new FormatView(
                matrix.instant(),
                matrix.number(),
                matrix.locales(),
                matrix.zones(),
                "Currency is formatted with each language's likely-region locale (de → de-DE → EUR): a "
                        + "language-only locale names no currency (CLDR answers XXX). Real products make "
                        + "currency an account setting — never a locale inference."));
    }

    // ---- internals ----

    /** Resolves one key for one locale through MessageSource, probing the chain. */
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
        List<ChainStepView> steps = chain.steps().stream()
                .map(step -> new ChainStepView(step.bundle(), step.hit(), step.template()))
                .toList();
        return new GreetingView(
                useCase.wireName(),
                locale.toLanguageTag(),
                locale.getDisplayLanguage(locale),
                key,
                message,
                chain.answeringBundle(),
                chain.template(),
                steps,
                note);
    }

    private static String note(String text) {
        return text;
    }

    /**
     * Wraps the request so the resolver reads the ANALYZED header (the
     * query param) rather than the real one — the same trick a unit test
     * or a filtering proxy uses to drive the resolver deterministically.
     */
    private static HttpServletRequest wrapping(HttpServletRequest request, String header) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return "Accept-Language".equalsIgnoreCase(name) ? header : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                // List.of() rejects null — a request with no Accept-Language
                // wraps a null header and must answer an EMPTY enumeration.
                return "Accept-Language".equalsIgnoreCase(name)
                        ? Collections.enumeration(header == null ? List.<String>of() : List.of(header))
                        : super.getHeaders(name);
            }

            @Override
            public Locale getLocale() {
                return getLocales().nextElement();
            }

            @Override
            public Enumeration<Locale> getLocales() {
                // The resolver's findSupportedLocale walks getLocales() (the
                // servlet-parsed preference list), not raw getHeader — so the
                // wrapper serves our parser's candidates in quality order.
                if (header == null || header.isBlank()) {
                    return Collections.enumeration(List.of(I18nDemoConfig.DEFAULT_LOCALE));
                }
                List<Locale> preferred = ParseAcceptLanguage.parse(header).byQuality().stream()
                        .filter(candidate -> !candidate.wildcard())
                        .map(candidate -> Locale.forLanguageTag(candidate.tag()))
                        .filter(locale -> !locale.getLanguage().isEmpty())
                        .toList();
                return Collections.enumeration(
                        preferred.isEmpty() ? List.of(I18nDemoConfig.DEFAULT_LOCALE) : preferred);
            }
        };
    }

    /** ScenarioStore exhibit: with injectFailure on, the demo dependency is "down" — 503. */
    private void requireAvailable() {
        if (scenarioStore.get().injectFailure()) {
            throw new UnavailableException(
                    "i18n demos are unavailable by scenario (injectFailure=true)",
                    Map.of("area", AREA));
        }
    }
}
