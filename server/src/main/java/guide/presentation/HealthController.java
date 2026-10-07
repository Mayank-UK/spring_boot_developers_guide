package guide.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness/marketing endpoint for the guide server: proves the HTTP layer is up.
 * No dependencies yet on purpose — later phases inject use cases here.
 *
 * OpenAPI curation (topic 12): deliberately MINIMAL — a tag to group the
 * endpoint and one operation summary. The response record is self-describing;
 * annotating its two fields would add noise, not information. The curation
 * budget lives in the posts controller instead.
 */
@Tag(name = "Health", description = "Liveness of the guide server — no dependencies, on purpose.")
@RestController
public class HealthController {

    /** Immutable response body — Jackson serializes record components in declaration order. */
    record HealthResponse(String status, String service) {
    }

    @Operation(summary = "Liveness probe", operationId = "getHealth",
            description = "Succeeds the moment the web layer answers — deliberately independent "
                    + "of every other subsystem.")
    @GetMapping("/api/health")
    HealthResponse health() {
        return new HealthResponse("ok", "springboot-developers-guide");
    }
}
