package guide.domain.reqres;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;

/**
 * Use case behind the reqres inspector (topic 09): the policy shell around
 * the exhibit. An inspector's job is to tolerate and report whatever the
 * wire carried, so the only failure it knows is a blank method token —
 * everything else is data to classify, never to police.
 */
public class InspectRequestSurface {

    private final RequestSurfaceExhibit exhibit;

    public InspectRequestSurface(RequestSurfaceExhibit exhibit) {
        this.exhibit = exhibit;
    }

    public Result<RequestSurfaceExhibit.Report> execute(RequestSurfaceExhibit.Incoming incoming) {
        // Incoming normalizes a blank method to the UNKNOWN sentinel; both are invalid here.
        if (incoming == null || incoming.method().equals("UNKNOWN")) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "method must be a non-empty string", java.util.Map.of("parameter", "method")));
        }
        return Result.success(exhibit.inspect(incoming));
    }
}
