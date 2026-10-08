package guide.presentation.demo;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The routing explorer against the REAL application context — the table IS
 * the registry Boot built for every @RequestMapping in the app. Presence and
 * range assertions only: siblings keep adding routes, and this test must
 * stay green while the table grows.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoutingTableIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theRealRegistryCarriesTheCoreRoutes() throws Exception {
        mockMvc.perform(get("/api/demo/routing/table"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", greaterThanOrEqualTo(20)))
                .andExpect(jsonPath("$.routes[?(@.pattern == '/api/content')]").isNotEmpty())
                .andExpect(jsonPath("$.routes[?(@.pattern == '/api/content/{topicId}')]").isNotEmpty())
                .andExpect(jsonPath("$.routes[?(@.pattern == '/api/health')]").isNotEmpty())
                .andExpect(jsonPath("$.routes[?(@.pattern == '/api/demo/scenario')]").isNotEmpty())
                .andExpect(jsonPath("$.routes[?(@.pattern == '/api/demo/routing/table')]").isNotEmpty())
                .andExpect(jsonPath("$.routes[?(@.handler == 'numericIdSpecimen')]").isNotEmpty());
    }

    @Test
    void prefixFilterNarrowsTheRealTable() throws Exception {
        mockMvc.perform(get("/api/demo/routing/table").queryParam("prefix", "/api/demo/routing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value("/api/demo/routing"))
                .andExpect(jsonPath("$.total", greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$.routes[0].pattern").value("/api/demo/routing/match"));
    }

    @Test
    void matchExplainsARealContentRoute() throws Exception {
        mockMvc.perform(get("/api/demo/routing/match")
                        .queryParam("path", "/api/content/java-language")
                        .queryParam("method", "GET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("matched"))
                .andExpect(jsonPath("$.winner.pattern").value("/api/content/{topicId}"))
                .andExpect(jsonPath("$.winner.controller").value("ContentController"))
                .andExpect(jsonPath("$.captured.topicId").value("java-language"))
                .andExpect(jsonPath("$.candidates[0].entry.pattern")
                        .value("/api/content/{topicId}"));
    }
}
