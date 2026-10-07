package guide.presentation.demo;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.java.InspectRuntimeType;
import guide.domain.java.RuntimeTypesExhibit;
import guide.domain.java.RuntimeTypesExhibit.BoxInstanceView;
import guide.domain.java.RuntimeTypesExhibit.ClassReport;
import guide.domain.java.RuntimeTypesExhibit.ComponentView;
import guide.domain.java.RuntimeTypesExhibit.ErasureReport;
import guide.domain.java.RuntimeTypesExhibit.FieldView;
import guide.domain.java.ShowErasureEvidence;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over the java-language demo endpoints: happy paths for both
 * endpoints plus the 400 envelope for unknown/missing class params — the
 * full Result → HTTP mapping through the real advice.
 */
@WebMvcTest(JavaDemoController.class)
class JavaDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InspectRuntimeType inspectRuntimeType;

    @MockitoBean
    private ShowErasureEvidence showErasureEvidence;

    @Test
    void reflectionServesTheSealedInterfaceReport() throws Exception {
        ClassReport report = new ClassReport("result", "guide.core.result.Result",
                "sealed interface", List.of(), true,
                List.of("guide.core.result.Success", "guide.core.result.Failed"), List.of());
        when(inspectRuntimeType.execute("result")).thenReturn(Result.success(report));

        mockMvc.perform(get("/api/demo/java/reflection").param("class", "result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedName").value("result"))
                .andExpect(jsonPath("$.className").value("guide.core.result.Result"))
                .andExpect(jsonPath("$.kind").value("sealed interface"))
                .andExpect(jsonPath("$.sealed").value(true))
                .andExpect(jsonPath("$.permittedSubclasses[0]").value("guide.core.result.Success"))
                .andExpect(jsonPath("$.permittedSubclasses[1]").value("guide.core.result.Failed"))
                .andExpect(jsonPath("$.recordComponents").isArray());
    }

    @Test
    void reflectionServesRecordComponents() throws Exception {
        ClassReport report = new ClassReport("text-block", "guide.content.TextBlock",
                "record", List.of(new ComponentView("id", "java.lang.String"),
                        new ComponentView("body", "java.lang.String")),
                false, List.of(),
                List.of("com.fasterxml.jackson.annotation.JsonInclude"));
        when(inspectRuntimeType.execute("text-block")).thenReturn(Result.success(report));

        mockMvc.perform(get("/api/demo/java/reflection").param("class", "text-block"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("record"))
                .andExpect(jsonPath("$.recordComponents[0].name").value("id"))
                .andExpect(jsonPath("$.recordComponents[0].type").value("java.lang.String"))
                .andExpect(jsonPath("$.recordComponents[1].name").value("body"))
                .andExpect(jsonPath("$.sealed").value(false));
    }

    @Test
    void unknownClassYieldsThe400EnvelopeNamingTheWhitelist() throws Exception {
        when(inspectRuntimeType.execute("string-builder")).thenReturn(Result.failed(
                Failure.of(FailureKind.VALIDATION,
                        "unknown class \"string-builder\" — inspectable classes are: "
                                + "result, text-block, topic",
                        Map.of("parameter", "class", "validValues", RuntimeTypesExhibit.INSPECTABLE))));

        mockMvc.perform(get("/api/demo/java/reflection").param("class", "string-builder"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value(
                        "unknown class \"string-builder\" — inspectable classes are: result, text-block, topic"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty())
                .andExpect(jsonPath("$.error.parameter").value("class"))
                .andExpect(jsonPath("$.error.validValues[0]").value("result"));
    }

    @Test
    void missingClassParamFlowsThroughTheSameValidationPath() throws Exception {
        when(inspectRuntimeType.execute(null)).thenReturn(Result.failed(
                Failure.of(FailureKind.VALIDATION, "class query parameter must be a non-empty string",
                        Map.of("parameter", "class", "validValues", RuntimeTypesExhibit.INSPECTABLE))));

        mockMvc.perform(get("/api/demo/java/reflection"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message")
                        .value("class query parameter must be a non-empty string"));
    }

    @Test
    void erasureServesSideBySideEvidence() throws Exception {
        ErasureReport report = new ErasureReport(
                List.of(new FieldView("tags", "java.util.List<java.lang.String>", "java.util.List"),
                        new FieldView("counts", "java.util.Map<java.lang.String,java.lang.Integer>",
                                "java.util.Map")),
                List.of(new BoxInstanceView("Box<String>",
                                "guide.domain.java.RuntimeTypesExhibit.Box",
                                List.of("guide", "boot", "jvm")),
                        new BoxInstanceView("Box<Integer>",
                                "guide.domain.java.RuntimeTypesExhibit.Box",
                                List.of("1", "2", "3"))),
                true);
        when(showErasureEvidence.execute()).thenReturn(Result.success(report));

        mockMvc.perform(get("/api/demo/java/erasure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shareOneRawClass").value(true))
                .andExpect(jsonPath("$.declaredFields[0].name").value("tags"))
                .andExpect(jsonPath("$.declaredFields[0].genericType")
                        .value("java.util.List<java.lang.String>"))
                .andExpect(jsonPath("$.declaredFields[0].rawType").value("java.util.List"))
                .andExpect(jsonPath("$.instances.length()").value(2))
                .andExpect(jsonPath("$.instances[0].declaredAs").value("Box<String>"))
                .andExpect(jsonPath("$.instances[0].rawClass")
                        .value("guide.domain.java.RuntimeTypesExhibit.Box"))
                .andExpect(jsonPath("$.instances[1].declaredAs").value("Box<Integer>"))
                .andExpect(jsonPath("$.instances[1].rawClass")
                        .value("guide.domain.java.RuntimeTypesExhibit.Box"))
                .andExpect(jsonPath("$.instances[1].items[2]").value("3"));
    }
}
