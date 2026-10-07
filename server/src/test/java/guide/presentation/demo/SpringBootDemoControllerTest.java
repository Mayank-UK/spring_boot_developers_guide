package guide.presentation.demo;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.spring.AppInfoExhibit;
import guide.domain.spring.ShowAppInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over topic 04's demo endpoint: the happy-path exhibit JSON (the
 * wire contract the client demo parses) and the Result → HTTP mapping
 * through the real advice — a Failed Result must leave as the typed error
 * envelope, not a raw 500.
 */
@WebMvcTest(SpringBootDemoController.class)
class SpringBootDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShowAppInfo showAppInfo;

    private static AppInfoExhibit exhibit() {
        return new AppInfoExhibit(
                "springboot-developers-guide",
                List.of("dev"),
                new AppInfoExhibit.BuildView("server", "0.0.1-SNAPSHOT", "2026-10-06T09:00:00Z"),
                "25", "Eclipse Adoptium",
                "2026-10-06T09:00:01Z", 90,
                "tomcat", 8080, List.of("health", "info"),
                187, 41, 7,
                List.of(new AppInfoExhibit.PackageView("config", 3),
                        new AppInfoExhibit.PackageView("presentation", 2)));
    }

    @Test
    void appInfoServesTheExhibitWireShape() throws Exception {
        when(showAppInfo.execute()).thenReturn(Result.success(exhibit()));

        mockMvc.perform(get("/api/demo/spring/app-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationName").value("springboot-developers-guide"))
                .andExpect(jsonPath("$.activeProfiles[0]").value("dev"))
                .andExpect(jsonPath("$.build.artifact").value("server"))
                .andExpect(jsonPath("$.build.version").value("0.0.1-SNAPSHOT"))
                .andExpect(jsonPath("$.javaVersion").value("25"))
                .andExpect(jsonPath("$.javaVendor").value("Eclipse Adoptium"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-06T09:00:01Z"))
                .andExpect(jsonPath("$.uptimeSeconds").value(90))
                .andExpect(jsonPath("$.embeddedServer").value("tomcat"))
                .andExpect(jsonPath("$.serverPort").value(8080))
                .andExpect(jsonPath("$.actuatorEndpoints[0]").value("health"))
                .andExpect(jsonPath("$.actuatorEndpoints[1]").value("info"))
                .andExpect(jsonPath("$.beanDefinitionCount").value(187))
                .andExpect(jsonPath("$.configurationClasses").value(41))
                .andExpect(jsonPath("$.guideBeans").value(7))
                .andExpect(jsonPath("$.packages[0].name").value("config"))
                .andExpect(jsonPath("$.packages[0].beans").value(3))
                .andExpect(jsonPath("$.packages[1].name").value("presentation"))
                .andExpect(jsonPath("$.packages[1].beans").value(2));
    }

    @Test
    void aFailedResultLeavesAsTheTypedEnvelope() throws Exception {
        when(showAppInfo.execute()).thenReturn(Result.failed(Failure.of(FailureKind.INTERNAL,
                "introspection unavailable", Map.of("demo", "spring:app-info"))));

        mockMvc.perform(get("/api/demo/spring/app-info"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.message").value("introspection unavailable"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.demo").value("spring:app-info"));
    }
}
