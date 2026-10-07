package guide.presentation.demo;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Topic 05's demo against the REAL application context — the adapter walking
 * the actual bean factory the tests boot. All count assertions are
 * RANGES/presence on purpose: bean totals and package counts move with every
 * sibling topic's beans, and an exact number here would break the next
 * topic's build.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoreContainerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void overviewReportsTheRealRunningContainer() throws Exception {
        mockMvc.perform(get("/api/demo/core/beans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBeans", greaterThanOrEqualTo(50)))
                .andExpect(jsonPath("$.byPackage.length()", greaterThanOrEqualTo(4)))
                // our own top-level packages are present in the layout
                .andExpect(jsonPath("$.byPackage[?(@.name=='config')]").isNotEmpty())
                .andExpect(jsonPath("$.byPackage[?(@.name=='domain')]").isNotEmpty())
                .andExpect(jsonPath("$.byPackage[?(@.name=='presentation')]").isNotEmpty())
                // every registered use case of this app lives under guide.domain
                .andExpect(jsonPath("$.useCaseBeans", hasSize(greaterThanOrEqualTo(5))))
                .andExpect(jsonPath("$.useCaseBeans.length()", greaterThanOrEqualTo(5)))
                // both registered conventions describe themselves
                .andExpect(jsonPath("$.useCaseConventions.length()", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.sampleBean.name").isNotEmpty())
                .andExpect(jsonPath("$.sampleBean.className").isNotEmpty())
                .andExpect(jsonPath("$.sampleBean.scope").value("singleton"))
                .andExpect(jsonPath("$.sampleBean.dependsOn").isArray());
    }

    @Test
    void beanDetailRoundTripsARealBeanWithDependencies() throws Exception {
        mockMvc.perform(get("/api/demo/core/beans/showAppInfo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("showAppInfo"))
                .andExpect(jsonPath("$.className", endsWith("ShowAppInfo")))
                .andExpect(jsonPath("$.scope").value("singleton"))
                .andExpect(jsonPath("$.factoryMethod").value("showAppInfo"))
                .andExpect(jsonPath("$.dependsOn.length()", greaterThanOrEqualTo(1)));

        // a dependency the detail names is itself a resolvable next hop
        mockMvc.perform(get("/api/demo/core/beans/appInfoSource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("appInfoSource"))
                .andExpect(jsonPath("$.className", endsWith("AppInfoAdapter")));
    }

    @Test
    void unknownBeanNameAnswersTheTyped404Envelope() throws Exception {
        mockMvc.perform(get("/api/demo/core/beans/definitely-not-a-bean"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.bean").value("definitely-not-a-bean"))
                .andExpect(jsonPath("$.error.demo").value("core:container"));
    }
}
