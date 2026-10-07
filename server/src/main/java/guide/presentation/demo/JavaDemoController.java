package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.java.InspectRuntimeType;
import guide.domain.java.RuntimeTypesExhibit;
import guide.domain.java.ShowErasureEvidence;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 01 (java-language) — the live half of the
 * generics-and-reflection section:
 * - GET /api/demo/java/reflection?class=result|text-block|topic → 200 report
 *   of what reflection sees on one of this repo's own classes, or the 400
 *   envelope for anything else
 * - GET /api/demo/java/erasure → 200 side-by-side erasure evidence
 *
 * Thin like every controller: parse the input, call ONE use case, map the
 * Result onto HTTP. The class whitelist lives in the exhibit's domain, and
 * the param is optional at this edge so a missing value flows through the
 * same validation path as an unknown one.
 */
@RestController
public class JavaDemoController {

    private final InspectRuntimeType inspectRuntimeType;
    private final ShowErasureEvidence showErasureEvidence;

    public JavaDemoController(InspectRuntimeType inspectRuntimeType,
            ShowErasureEvidence showErasureEvidence) {
        this.inspectRuntimeType = inspectRuntimeType;
        this.showErasureEvidence = showErasureEvidence;
    }

    /** GET /api/demo/java/reflection?class=result → 200 report or 400 envelope. */
    @GetMapping("/api/demo/java/reflection")
    RuntimeTypesExhibit.ClassReport reflection(
            @RequestParam(name = "class", required = false) String className) {
        return unwrap(inspectRuntimeType.execute(className));
    }

    /** GET /api/demo/java/erasure → 200 side-by-side erasure evidence. */
    @GetMapping("/api/demo/java/erasure")
    RuntimeTypesExhibit.ErasureReport erasure() {
        return unwrap(showErasureEvidence.execute());
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
