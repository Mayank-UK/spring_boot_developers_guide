package guide.domain.java;

import guide.core.result.Result;

/**
 * Use case: serve the fixed erasure-evidence report (declared generic field
 * types vs two Box instances that share one raw Class). No input, no
 * failure mode — kept as a use case anyway so the controller stays thin and
 * the wiring mirrors every other endpoint: controller → use case → Result.
 */
public class ShowErasureEvidence {

    private final RuntimeTypesExhibit exhibit;

    public ShowErasureEvidence(RuntimeTypesExhibit exhibit) {
        this.exhibit = exhibit;
    }

    public Result<RuntimeTypesExhibit.ErasureReport> execute() {
        return Result.success(exhibit.erasureEvidence());
    }
}
