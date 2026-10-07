package guide.domain.java;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * Use case: reflect one whitelisted class for the java-language demo.
 * Missing or unknown names are validation failures — the presentation edge
 * maps them to the 400 envelope. The whitelist itself lives in the exhibit,
 * so this use case stays a thin policy shell: validate input, delegate,
 * convert absence into a typed failure that names the valid values.
 */
public class InspectRuntimeType {

    private final RuntimeTypesExhibit exhibit;

    public InspectRuntimeType(RuntimeTypesExhibit exhibit) {
        this.exhibit = exhibit;
    }

    public Result<RuntimeTypesExhibit.ClassReport> execute(String shortName) {
        if (shortName == null || shortName.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "class query parameter must be a non-empty string",
                    Map.of("parameter", "class", "validValues", RuntimeTypesExhibit.INSPECTABLE)));
        }
        return exhibit.inspect(shortName)
                .map(report -> Result.<RuntimeTypesExhibit.ClassReport>success(report))
                .orElseGet(() -> Result.failed(Failure.of(FailureKind.VALIDATION,
                        "unknown class \"" + shortName.trim() + "\" — inspectable classes are: "
                                + String.join(", ", RuntimeTypesExhibit.INSPECTABLE),
                        Map.of("parameter", "class", "validValues", RuntimeTypesExhibit.INSPECTABLE))));
    }
}
