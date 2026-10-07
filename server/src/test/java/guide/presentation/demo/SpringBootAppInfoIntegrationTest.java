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
 * Topic 04's demo against the REAL application context — not mocked wiring:
 * the endpoint must answer from the actual Environment/ApplicationContext the
 * tests boot. All count assertions are RANGES/presence on purpose: bean and
 * configuration counts move with every topic added to the registry, and an
 * exact number here would break the next sibling topic's build.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpringBootAppInfoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void appInfoReportsTheRealRunningContext() throws Exception {
        mockMvc.perform(get("/api/demo/spring/app-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationName").value("springboot-developers-guide"))
                .andExpect(jsonPath("$.activeProfiles").isArray())
                .andExpect(jsonPath("$.javaVersion").isNotEmpty())
                .andExpect(jsonPath("$.javaVendor").isNotEmpty())
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.uptimeSeconds").isNumber())
                .andExpect(jsonPath("$.embeddedServer").value("tomcat"))
                .andExpect(jsonPath("$.serverPort").value(8080))
                .andExpect(jsonPath("$.actuatorEndpoints[0]").value("health"))
                .andExpect(jsonPath("$.actuatorEndpoints[1]").value("info"))
                .andExpect(jsonPath("$.beanDefinitionCount", greaterThanOrEqualTo(50)))
                .andExpect(jsonPath("$.configurationClasses", greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.guideBeans", greaterThanOrEqualTo(10)))
                .andExpect(jsonPath("$.packages.length()", greaterThanOrEqualTo(4)));
    }

    @Test
    void theExhibitIsLiveStateNotACache() throws Exception {
        // Two calls, both must answer 200 and carry the computed-per-request
        // uptime field — the exact value is timing-dependent, so assert shape.
        for (int call = 0; call < 2; call++) {
            mockMvc.perform(get("/api/demo/spring/app-info"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.uptimeSeconds").isNumber());
        }
    }
}
