package guide.domain.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.domain.java.RuntimeTypesExhibit.BoxInstanceView;
import guide.domain.java.RuntimeTypesExhibit.ClassReport;
import guide.domain.java.RuntimeTypesExhibit.ComponentView;
import guide.domain.java.RuntimeTypesExhibit.ErasureReport;
import guide.domain.java.RuntimeTypesExhibit.FieldView;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The exhibit + use case behind the java-language demo, tested against the
 * REAL classes it reflects (their shapes are load-bearing: the demo asserts
 * what record components, sealed permits, and erasure actually report).
 */
class RuntimeTypesExhibitTest {

    private final RuntimeTypesExhibit exhibit = new RuntimeTypesExhibit();

    @Test
    void inspectsRecordComponentNamesAndDeclaredTypes() {
        ClassReport report = exhibit.inspect("text-block").orElseThrow();

        assertEquals("guide.content.TextBlock", report.className());
        assertEquals("record", report.kind());
        assertEquals(List.of("id", "body"),
                report.recordComponents().stream().map(ComponentView::name).toList());
        assertEquals("java.lang.String", report.recordComponents().getFirst().type());
        assertFalse(report.sealed());
        assertEquals(List.of("com.fasterxml.jackson.annotation.JsonInclude"), report.annotations());
    }

    @Test
    void inspectsSealedInterfaceWithItsPermittedSubclasses() {
        ClassReport report = exhibit.inspect("result").orElseThrow();

        assertEquals("guide.core.result.Result", report.className());
        assertEquals("sealed interface", report.kind());
        assertTrue(report.sealed());
        assertEquals(List.of("guide.core.result.Success", "guide.core.result.Failed"),
                report.permittedSubclasses());
        assertEquals(List.of(), report.annotations());
    }

    @Test
    void topicCarriesAllSixRecordComponents() {
        ClassReport report = exhibit.inspect("topic").orElseThrow();

        assertEquals(6, report.recordComponents().size());
        assertEquals("sections", report.recordComponents().getLast().name());
        assertEquals("java.util.List<guide.content.ContentSection>",
                report.recordComponents().getLast().type());
    }

    @Test
    void inspectNormalizesInputAndRejectsUnknownClasses() {
        assertTrue(exhibit.inspect("  Topic ").isPresent());
        assertTrue(exhibit.inspect("nope").isEmpty());
        assertTrue(exhibit.inspect(null).isEmpty());
    }

    @Test
    void erasureEvidenceKeepsDeclaredGenericsButSharesOneRawClass() {
        ErasureReport report = exhibit.erasureEvidence();

        Map<String, FieldView> fields = report.declaredFields().stream()
                .collect(Collectors.toMap(FieldView::name, Function.identity()));
        assertEquals("java.util.List<java.lang.String>", fields.get("tags").genericType());
        assertEquals("java.util.List", fields.get("tags").rawType());
        assertEquals("java.util.Map<java.lang.String, java.lang.Integer>",
                fields.get("counts").genericType());

        assertTrue(report.shareOneRawClass());
        List<BoxInstanceView> instances = report.instances();
        assertEquals(2, instances.size());
        assertEquals("Box<String>", instances.getFirst().declaredAs());
        assertEquals("Box<Integer>", instances.getLast().declaredAs());
        assertEquals(instances.getFirst().rawClass(), instances.getLast().rawClass());
        assertEquals(List.of("guide", "boot", "jvm"), instances.getFirst().items());
    }

    @Test
    void useCaseValidatesBlankAndUnknownNamesAsTypedFailures() {
        InspectRuntimeType useCase = new InspectRuntimeType(exhibit);

        assertTrue(useCase.execute("result").isSuccess());

        Failed<RuntimeTypesExhibit.ClassReport> unknown =
                (Failed<ClassReport>) useCase.execute("string-builder");
        assertEquals(FailureKind.VALIDATION, unknown.failure().kind());
        assertTrue(unknown.failure().message().contains("string-builder"));

        Failed<RuntimeTypesExhibit.ClassReport> blank =
                (Failed<ClassReport>) useCase.execute(" ");
        assertEquals(FailureKind.VALIDATION, blank.failure().kind());
        assertEquals("class", blank.failure().details().get("parameter"));
    }
}
