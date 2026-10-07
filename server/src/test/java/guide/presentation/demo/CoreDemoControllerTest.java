package guide.presentation.demo;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.core.container.BeanSnapshot;
import guide.domain.core.container.ContainerExhibit;
import guide.domain.core.container.InspectBean;
import guide.domain.core.container.ShowContainer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over topic 05's demo endpoints: the happy-path wire shapes (the
 * overview and the bean detail the client demo parses) and the Result → HTTP
 * mapping through the real advice — a Failed Result must leave as the typed
 * error envelope, not a raw 500.
 */
@WebMvcTest(CoreDemoController.class)
class CoreDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShowContainer showContainer;

    @MockitoBean
    private InspectBean inspectBean;

    @Test
    void overviewServesTheExhibitWireShape() throws Exception {
        when(showContainer.execute()).thenReturn(Result.success(new ContainerExhibit(
                214,
                List.of(new ContainerExhibit.PackageView("config", 5),
                        new ContainerExhibit.PackageView("domain", 9)),
                List.of("showAppInfo", "showContainer"),
                List.of("domain-layer beans (guide.domain.*)", "UseCase-suffixed classes (*UseCase)"),
                new BeanSnapshot("appInfoSource", "guide.data.spring.AppInfoAdapter",
                        "singleton", "appInfoSource", List.of("environment", "context"), false))));

        mockMvc.perform(get("/api/demo/core/beans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBeans").value(214))
                .andExpect(jsonPath("$.byPackage[0].name").value("config"))
                .andExpect(jsonPath("$.byPackage[0].beans").value(5))
                .andExpect(jsonPath("$.byPackage[1].name").value("domain"))
                .andExpect(jsonPath("$.useCaseBeans[0]").value("showAppInfo"))
                .andExpect(jsonPath("$.useCaseBeans[1]").value("showContainer"))
                .andExpect(jsonPath("$.useCaseConventions[0]")
                        .value("domain-layer beans (guide.domain.*)"))
                .andExpect(jsonPath("$.sampleBean.name").value("appInfoSource"))
                .andExpect(jsonPath("$.sampleBean.className").value("guide.data.spring.AppInfoAdapter"))
                .andExpect(jsonPath("$.sampleBean.scope").value("singleton"))
                .andExpect(jsonPath("$.sampleBean.factoryMethod").value("appInfoSource"))
                .andExpect(jsonPath("$.sampleBean.dependsOn[0]").value("environment"))
                .andExpect(jsonPath("$.sampleBean.dependsOn[1]").value("context"))
                .andExpect(jsonPath("$.sampleBean.proxied").value(false));
    }

    @Test
    void beanDetailServesTheSnapshotWireShape() throws Exception {
        when(inspectBean.execute("showAppInfo")).thenReturn(Result.success(new BeanSnapshot(
                "showAppInfo", "guide.domain.spring.ShowAppInfo", "singleton",
                "showAppInfo", List.of("appInfoSource"), false)));

        mockMvc.perform(get("/api/demo/core/beans/showAppInfo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("showAppInfo"))
                .andExpect(jsonPath("$.className").value("guide.domain.spring.ShowAppInfo"))
                .andExpect(jsonPath("$.scope").value("singleton"))
                .andExpect(jsonPath("$.factoryMethod").value("showAppInfo"))
                .andExpect(jsonPath("$.dependsOn[0]").value("appInfoSource"))
                .andExpect(jsonPath("$.proxied").value(false));
    }

    @Test
    void anUnknownBeanLeavesAsTheTyped404Envelope() throws Exception {
        when(inspectBean.execute("no-such-bean")).thenReturn(Result.failed(Failure.of(
                FailureKind.NOT_FOUND,
                "no bean named 'no-such-bean' in this context",
                java.util.Map.of("bean", "no-such-bean", "demo", "core:container"))));

        mockMvc.perform(get("/api/demo/core/beans/no-such-bean"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.message")
                        .value("no bean named 'no-such-bean' in this context"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.bean").value("no-such-bean"))
                .andExpect(jsonPath("$.error.demo").value("core:container"));
    }

    @Test
    void aFailedOverviewLeavesAsTheTypedEnvelope() throws Exception {
        when(showContainer.execute()).thenReturn(Result.failed(Failure.of(FailureKind.INTERNAL,
                "introspection unavailable", java.util.Map.of("demo", "core:container"))));

        mockMvc.perform(get("/api/demo/core/beans"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.message").value("introspection unavailable"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }
}
