package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.I18nDemoConfig;
import guide.core.scenario.ScenarioPatch;
import guide.core.scenario.ScenarioStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the i18n negotiator with the REAL demo beans: negotiation
 * answers for the request's own header AND for a synthetic header param
 * (with the resolver driven through the wrapper on the same string);
 * malformed segments are skipped without failing the request; the greeting
 * round-trips all four locales (Japanese asserted in escaped form, proving
 * the UTF-8 bundles survive the whole pipeline), the choice pattern
 * survives the French apostrophe trap, the fallback chain lands on the
 * base bundle, the absent key fails loud, and the format matrix renders
 * its snapshot rows with the two-zone contrast. The slice's MessageSource
 * comes from MessageSourceAutoConfiguration reading application.yml — the
 * production wiring, not a test double.
 */
@WebMvcTest(I18nDemoController.class)
@Import(I18nDemoConfig.class)
class I18nDemoControllerTest {

    private static final String NEGOTIATE = "/api/demo/i18n/negotiate";
    private static final String GREETING = "/api/demo/i18n/greeting";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScenarioStore scenarioStore;

    /** The slice has no GuideConfig — the store the demo guard needs comes from here. */
    @TestConfiguration
    static class RealScenarioStore {
        @Bean
        ScenarioStore scenarioStore() {
            return new ScenarioStore();
        }
    }

    @BeforeEach
    @AfterEach
    void resetScenario() {
        scenarioStore.reset();
    }

    @Test
    void negotiateUsesTheRequestsOwnHeaderByDefault() throws Exception {
        mockMvc.perform(get(NEGOTIATE).header("Accept-Language", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromRequest").value(true))
                .andExpect(jsonPath("$.header").value("fr"))
                .andExpect(jsonPath("$.candidates[0].tag").value("fr"))
                .andExpect(jsonPath("$.candidates[0].quality").value(1.0))
                .andExpect(jsonPath("$.supported.length()").value(4))
                .andExpect(jsonPath("$.winner").value("fr"))
                .andExpect(jsonPath("$.kind").value("EXACT"))
                .andExpect(jsonPath("$.resolverChose").value("fr"))
                .andExpect(jsonPath("$.resolverAgrees").value(true));
    }

    @Test
    void negotiateWithAHeaderParamAnalyzesThatString() throws Exception {
        mockMvc.perform(get(NEGOTIATE).param("header", "de-DE,fr;q=0.8,en;q=0.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromRequest").value(false))
                .andExpect(jsonPath("$.candidates.length()").value(3))
                .andExpect(jsonPath("$.candidates[0].tag").value("de-DE"))
                .andExpect(jsonPath("$.candidates[0].rank").value(1))
                .andExpect(jsonPath("$.candidates[1].tag").value("fr"))
                .andExpect(jsonPath("$.candidates[2].tag").value("en"))
                // Our two-pass negotiator: the EXACT pass completes first, so
                // fr (exact, q=0.8) beats de-DE (language-only, q=1.0).
                .andExpect(jsonPath("$.winner").value("fr"))
                .andExpect(jsonPath("$.kind").value("EXACT"))
                // Spring's resolver is PREFERENCE-first: for EACH request
                // locale in order it takes an exact OR language match before
                // considering the next — so de-DE's language match (de) wins
                // before fr's exact match is ever consulted. The demo shows
                // both verdicts; the divergence is the teaching point.
                .andExpect(jsonPath("$.resolverChose").value("de"))
                .andExpect(jsonPath("$.resolverAgrees").value(false));
    }

    @Test
    void malformedSegmentsAreSkippedAndTheSalvageableCandidateStillServes() throws Exception {
        mockMvc.perform(get(NEGOTIATE).param("header", "EN-gb;q=1.0, zz-ZZ;q=OOPS, , fr;q=1.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].tag").value("en-GB"))
                .andExpect(jsonPath("$.skipped.length()").value(3))
                .andExpect(jsonPath("$.winner").value("en"))
                .andExpect(jsonPath("$.kind").value("LANGUAGE"))
                // Spring's own LanguageRange.parse refuses the whole header
                // (q=OOPS) and falls to the default — the honest divergence
                // between salvage-the-good-parts and reject-the-header.
                .andExpect(jsonPath("$.resolverChose").value("en"));
    }

    @Test
    void anUnsupportedLocaleFallsBackToTheDefault() throws Exception {
        mockMvc.perform(get(NEGOTIATE).header("Accept-Language", "zh-CN,zh;q=0.8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winner").value("en"))
                .andExpect(jsonPath("$.kind").value("DEFAULT"))
                .andExpect(jsonPath("$.resolverChose").value("en"));
    }

    @Test
    void theGreetingRoundTripsAllFourLocales() throws Exception {
        mockMvc.perform(get(GREETING).param("name", "Mak").header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Welcome, Mak!"))
                .andExpect(jsonPath("$.answeringBundle").value("messages/messages_en.properties"))
                .andExpect(jsonPath("$.localeDisplay").value("English"));
        mockMvc.perform(get(GREETING).param("name", "Mak").header("Accept-Language", "de"))
                .andExpect(jsonPath("$.message").value("Willkommen, Mak!"))
                .andExpect(jsonPath("$.answeringBundle").value("messages/messages_de.properties"))
                .andExpect(jsonPath("$.localeDisplay").value("Deutsch"));
        mockMvc.perform(get(GREETING).param("name", "Mak").header("Accept-Language", "fr"))
                .andExpect(jsonPath("$.message").value("Bienvenue, Mak !"))
                .andExpect(jsonPath("$.answeringBundle").value("messages/messages_fr.properties"));
        mockMvc.perform(get(GREETING).param("name", "Mak").header("Accept-Language", "ja"))
                .andExpect(jsonPath("$.message").value("\u3088\u3046\u3053\u305D\u3001Mak\u3055\u3093!"))
                .andExpect(jsonPath("$.answeringBundle").value("messages/messages_ja.properties"))
                .andExpect(jsonPath("$.localeDisplay").value("\u65E5\u672C\u8A9E"))
                .andExpect(jsonPath("$.chain[0].hit").value(true));
    }

    @Test
    void aRegionedHeaderServesTheLanguageBundle() throws Exception {
        mockMvc.perform(get(GREETING).header("Accept-Language", "de-AT,fr;q=0.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("de"))
                .andExpect(jsonPath("$.message").value("Willkommen, developer!"));
    }

    @Test
    void theReturningCaseInterpolatesTheDateParameter() throws Exception {
        mockMvc.perform(get(GREETING).param("useCase", "returning").param("name", "Mak")
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("Welcome back, Mak! Your last visit was Sep 12, 2026."));
    }

    @Test
    void thePluralCaseSurvivesTheFrenchApostropheTrap() throws Exception {
        mockMvc.perform(get(GREETING).param("useCase", "plural").param("count", "1")
                        .header("Accept-Language", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Tu as un message non lu"));
        mockMvc.perform(get(GREETING).param("useCase", "plural").param("count", "0")
                        .header("Accept-Language", "fr"))
                .andExpect(jsonPath("$.message").value("Tu n'as aucun message non lu"));
        mockMvc.perform(get(GREETING).param("useCase", "plural").param("count", "5")
                        .header("Accept-Language", "ja"))
                .andExpect(jsonPath("$.message")
                        .value("\u672A\u8AAD\u30E1\u30C3\u30BB\u30FC\u30B8\u304C5\u4EF6\u3042\u308A\u307E\u3059"));
    }

    @Test
    void theFallbackCaseWalksTheChainToTheBaseBundle() throws Exception {
        mockMvc.perform(get(GREETING).param("useCase", "fallback").header("Accept-Language", "de"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        "This sentence lives only in the base bundle. Every locale falls through to it."))
                .andExpect(jsonPath("$.answeringBundle").value("messages/messages.properties"))
                .andExpect(jsonPath("$.chain.length()").value(2))
                .andExpect(jsonPath("$.chain[0].bundle").value("messages/messages_de.properties"))
                .andExpect(jsonPath("$.chain[0].hit").value(false))
                .andExpect(jsonPath("$.chain[1].bundle").value("messages/messages.properties"))
                .andExpect(jsonPath("$.chain[1].hit").value(true));
    }

    @Test
    void theMissingCaseFailsLoudInsteadOfShippingTheKey() throws Exception {
        mockMvc.perform(get(GREETING).param("useCase", "missing").header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.answeringBundle").doesNotExist())
                .andExpect(jsonPath("$.note").value(
                        "The key exists in no bundle: NoSuchMessageException is the fail-loud default. "
                                + "spring.messages.use-code-as-default-message=true would return the key "
                                + "itself (\"??\" behavior) — a last-resort production guard, not a habit."));
    }

    @Test
    void unknownUseCasesAndBoundsAreValidationEnvelopes() throws Exception {
        mockMvc.perform(get(GREETING).param("useCase", "magic").header("Accept-Language", "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
        mockMvc.perform(get(GREETING).param("useCase", "plural").param("count", "1001")
                        .header("Accept-Language", "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    void theFormatMatrixRendersSnapshotRowsAndTheZoneContrast() throws Exception {
        mockMvc.perform(get("/api/demo/i18n/format"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instant").value("2026-10-06T12:34:56Z"))
                .andExpect(jsonPath("$.locales.length()").value(4))
                .andExpect(jsonPath("$.locales[0].date").value("October 6, 2026"))
                .andExpect(jsonPath("$.locales[1].percent").value("89\u00A0%"))
                .andExpect(jsonPath("$.locales[2].currency")
                        .value("1\u202F234\u202F567,89\u00A0\u20AC"))
                .andExpect(jsonPath("$.locales[3].currency").value("\uFFE51,234,568"))
                .andExpect(jsonPath("$.locales[3].currencyCode").value("JPY"))
                .andExpect(jsonPath("$.locales[3].compact").value("123.5\u4E07"))
                .andExpect(jsonPath("$.zones.length()").value(2))
                .andExpect(jsonPath("$.zones[0].dateTime").value("06.10.2026, 14:34:56"))
                .andExpect(jsonPath("$.zones[1].dateTime").value("06.10.2026, 21:34:56"))
                .andExpect(jsonPath("$.note").exists());
    }

    @Test
    void injectedFailureAnswersTheTypedUnavailableEnvelope() throws Exception {
        scenarioStore.apply(new ScenarioPatch(null, true));
        mockMvc.perform(get(NEGOTIATE).header("Accept-Language", "fr"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("service_unavailable"))
                .andExpect(jsonPath("$.error.area").value("i18n"));
        mockMvc.perform(get(GREETING).header("Accept-Language", "fr"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/demo/i18n/format"))
                .andExpect(status().isServiceUnavailable());
    }
}
