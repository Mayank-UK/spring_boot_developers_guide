package guide.presentation.demo;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.GuideConfig;
import guide.config.RoutingDemoConfig;
import guide.presentation.ContentController;
import guide.presentation.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the routing explorer with the REAL demo wiring imported —
 * the table, the match explainer, and the three live pattern specimens. The
 * slice mounts content + health + routing controllers (GuideConfig supplies
 * the content use cases) so "table contains core routes" is assertable
 * here; count assertions are RANGES because the registry grows with every
 * topic. The full-registry truth (including the functional-route caveat) is
 * pinned by RoutingTableIntegrationTest.
 */
@WebMvcTest({RoutingDemoController.class, ContentController.class, HealthController.class})
@Import({RoutingDemoConfig.class, GuideConfig.class})
class RoutingDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void tableListsCoreRoutesAndOwnsItsProduceDeclaration() throws Exception {
        mockMvc.perform(get("/api/demo/routing/table"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", greaterThanOrEqualTo(6)))
                .andExpect(jsonPath("$.routes[?(@.pattern == '/api/content')].handler")
                        .value(hasItem("list")))
                .andExpect(jsonPath("$.routes[?(@.pattern == '/api/content/{topicId}')].handler")
                        .value(hasItem("detail")))
                .andExpect(jsonPath("$.routes[?(@.pattern == '/api/health')].controller")
                        .value(hasItem("HealthController")))
                .andExpect(jsonPath("$.routes[?(@.pattern == '/api/demo/routing/table')].produces")
                        .value(hasItem(hasItem("application/json"))));
    }

    @Test
    void prefixFilterKeepsOnlyRowsUnderThePrefix() throws Exception {
        mockMvc.perform(get("/api/demo/routing/table").queryParam("prefix", "/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value("/api/health"))
                .andExpect(jsonPath("$.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.routes[0].pattern").value("/api/health"));
        mockMvc.perform(get("/api/demo/routing/table").queryParam("prefix", "api/health"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    void matchRanksTheExactRouteAboveTheVariableSpecimen() throws Exception {
        mockMvc.perform(get("/api/demo/routing/match")
                        .queryParam("path", "/api/demo/routing/table")
                        .queryParam("method", "GET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("matched"))
                .andExpect(jsonPath("$.winner.pattern").value("/api/demo/routing/table"))
                .andExpect(jsonPath("$.winner.controller").value("RoutingDemoController"))
                .andExpect(jsonPath("$.candidates[0].entry.pattern").value("/api/demo/routing/table"))
                .andExpect(jsonPath("$.candidates[0].score").value(0))
                .andExpect(jsonPath(
                        "$.candidates[?(@.entry.pattern == '/api/demo/routing/{section}')].entry.handler")
                        .value(hasItem("sectionSpecimen")))
                .andExpect(jsonPath(
                        "$.candidates[?(@.entry.pattern == '/api/demo/routing/{section}')].score")
                        .value(hasItem(1)));
    }

    @Test
    void regexSpecimenOutranksTheCatchAllForDigitSegments() throws Exception {
        mockMvc.perform(get("/api/demo/routing/match")
                        .queryParam("path", "/api/demo/routing/specimens/42")
                        .queryParam("method", "GET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("matched"))
                .andExpect(jsonPath("$.winner.pattern").value("/api/demo/routing/specimens/{id:\\d+}"))
                .andExpect(jsonPath("$.captured.id").value("42"))
                // the catch-all specimen ALSO matched — ranked behind the regex variable
                // (catchAll/score live on the CANDIDATE, next to entry — not inside it)
                .andExpect(jsonPath("$.candidates[1].entry.handler").value("restSpecimen"))
                .andExpect(jsonPath("$.candidates[1].catchAll").value(true));
        // the catch-all specimen also matched — ranked behind
        mockMvc.perform(get("/api/demo/routing/match")
                        .queryParam("path", "/api/demo/routing/specimens/nope")
                        .queryParam("method", "GET"))
                .andExpect(jsonPath("$.winner.pattern").value("/api/demo/routing/specimens/{*rest}"));
    }

    @Test
    void methodMismatchAndNoMatchReturnTypedVerdicts() throws Exception {
        mockMvc.perform(get("/api/demo/routing/match")
                        .queryParam("path", "/api/health")
                        .queryParam("method", "POST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("method_not_allowed"))
                .andExpect(jsonPath("$.matched").value(false))
                .andExpect(jsonPath("$.candidates[0].pathMatched").value(true))
                .andExpect(jsonPath("$.candidates[0].methodMatched").value(false));
        mockMvc.perform(get("/api/demo/routing/match")
                        .queryParam("path", "/api/definitely/missing")
                        .queryParam("method", "GET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("no_match"))
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.considered", greaterThanOrEqualTo(6)));
    }

    @Test
    void matchWithoutAPathIsATypedValidationFailureNotAMissingParam500() throws Exception {
        mockMvc.perform(get("/api/demo/routing/match").queryParam("method", "GET"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
        mockMvc.perform(get("/api/demo/routing/match")
                        .queryParam("path", "content/not-absolute")
                        .queryParam("method", "GET"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    void theThreeSpecimenRoutesAreLiveAndBindTheirVariables() throws Exception {
        mockMvc.perform(get("/api/demo/routing/specimens/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pattern").value("/api/demo/routing/specimens/{id:\\d+}"))
                .andExpect(jsonPath("$.captured.id").value("99"));
        mockMvc.perform(get("/api/demo/routing/proof"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pattern").value("/api/demo/routing/{section}"))
                .andExpect(jsonPath("$.captured.section").value("proof"));
        mockMvc.perform(get("/api/demo/routing/specimens/a/b/c"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pattern").value("/api/demo/routing/specimens/{*rest}"))
                .andExpect(jsonPath("$.captured.rest").value("/a/b/c"));
    }

    @Test
    void theFunctionalSpecimenIsReachableButNotInTheAnnotationTable() throws Exception {
        mockMvc.perform(get("/api/demo/routing/functional/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.style").value("RouterFunction"));
        mockMvc.perform(get("/api/demo/routing/table").queryParam("prefix", "/api/demo/routing"))
                .andExpect(jsonPath("$.routes.length()", greaterThanOrEqualTo(5)))
                .andExpect(jsonPath(
                        "$.routes[?(@.pattern == '/api/demo/routing/functional/ping')]").isEmpty());
    }
}
