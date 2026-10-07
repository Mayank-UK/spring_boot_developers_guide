package guide.domain.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.result.Failed;
import guide.core.result.Success;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The domain half of topic 04's app-info demo: exhibit derivation from raw
 * facts. Fixed facts + a fixed clock make every assertion exact HERE — the
 * brittleness concern applies only to live-context counts, which consumers
 * assert as ranges.
 */
class ShowAppInfoTest {

    private static final long STARTUP = 1_700_000_000_000L;
    private static final long NOW = STARTUP + 90_000L;

    private static AppInfoFacts facts() {
        return new AppInfoFacts(
                "springboot-developers-guide",
                List.of("dev"),
                "server", "0.0.1-SNAPSHOT", "2026-10-06T09:00:00Z",
                "25", "Eclipse Adoptium",
                STARTUP,
                187, 41,
                List.of("guide.GuideApplication",
                        "guide.config.GuideConfig",
                        "guide.config.SpringDemoConfig",
                        "guide.config.SpringDemoConfig", // two definitions may share one type
                        "guide.data.spring.AppInfoAdapter",
                        "guide.presentation.HealthController",
                        "guide.presentation.ContentController",
                        "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"),
                "tomcat", 8080,
                List.of("health", "info"));
    }

    /** Unwraps a Success the way the controller does, without Result::value. */
    private static AppInfoExhibit run(ShowAppInfo useCase) {
        return switch (useCase.execute()) {
            case Success<AppInfoExhibit> success -> success.value();
            case Failed<AppInfoExhibit> failed -> throw new AssertionError(failed.failure().toString());
        };
    }

    @Test
    void mapsFactsIntoExhibitWithDerivedUptime() {
        ShowAppInfo useCase = new ShowAppInfo(ShowAppInfoTest::facts, () -> NOW);

        AppInfoExhibit exhibit = run(useCase);

        assertEquals("springboot-developers-guide", exhibit.applicationName());
        assertEquals(List.of("dev"), exhibit.activeProfiles());
        assertEquals("server", exhibit.build().artifact());
        assertEquals("0.0.1-SNAPSHOT", exhibit.build().version());
        assertEquals("2026-10-06T09:00:00Z", exhibit.build().builtAt());
        assertEquals("25", exhibit.javaVersion());
        assertEquals("Eclipse Adoptium", exhibit.javaVendor());
        assertEquals("tomcat", exhibit.embeddedServer());
        assertEquals(8080, exhibit.serverPort());
        assertEquals(List.of("health", "info"), exhibit.actuatorEndpoints());
        assertEquals(187, exhibit.beanDefinitionCount());
        assertEquals(41, exhibit.configurationClasses());
    }

    @Test
    void uptimeComesFromTheInjectedClockNotWallTime() {
        ShowAppInfo useCase = new ShowAppInfo(ShowAppInfoTest::facts, () -> NOW);

        assertEquals(90, run(useCase).uptimeSeconds());
    }

    @Test
    void packageLayoutCountsTopLevelSegmentsAndBucketsTheRoot() {
        AppInfoExhibit exhibit = AppInfoExhibit.of(facts(), NOW);

        // (root)=1, config=3 (two definitions of SpringDemoConfig both count),
        // data=1, presentation=2 — framework classes outside guide. ignored.
        assertEquals(List.of(
                new AppInfoExhibit.PackageView("(root)", 1),
                new AppInfoExhibit.PackageView("config", 3),
                new AppInfoExhibit.PackageView("data", 1),
                new AppInfoExhibit.PackageView("presentation", 2)), exhibit.packages());
        assertEquals(7, exhibit.guideBeans());
    }

    @Test
    void unknownFactsDegradeToUnknownInsteadOfNulls() {
        AppInfoFacts empty = new AppInfoFacts(null, null, null, null, null,
                null, null, 0L, 0, 0, null, null, 0, null);

        AppInfoExhibit exhibit = AppInfoExhibit.of(empty, NOW);

        assertEquals("unknown", exhibit.applicationName());
        assertEquals(List.of(), exhibit.activeProfiles());
        assertEquals("unknown", exhibit.build().artifact());
        assertEquals("unknown", exhibit.javaVersion());
        assertEquals("unknown", exhibit.startedAt());
        assertEquals("unknown", exhibit.embeddedServer());
        assertEquals(0, exhibit.uptimeSeconds());
        assertEquals(List.of(), exhibit.actuatorEndpoints());
        assertEquals(List.of(), exhibit.packages());
        assertEquals(0, exhibit.guideBeans());
    }

    @Test
    void negativeClockSkewNeverReportsNegativeUptime() {
        AppInfoExhibit exhibit = AppInfoExhibit.of(facts(), STARTUP - 60_000L);

        assertEquals(0, exhibit.uptimeSeconds());
    }

    @Test
    void rejectsNullFactsFromTheSource() {
        ShowAppInfo useCase = new ShowAppInfo(() -> null, () -> NOW);

        assertThrows(NullPointerException.class, useCase::execute);
    }

    @Test
    void exhibitsAreImmutableSnapshots() {
        AppInfoExhibit exhibit = AppInfoExhibit.of(facts(), NOW);

        assertThrows(UnsupportedOperationException.class,
                () -> exhibit.activeProfiles().add("prod"));
        assertTrue(exhibit.packages().size() >= 4);
    }
}
