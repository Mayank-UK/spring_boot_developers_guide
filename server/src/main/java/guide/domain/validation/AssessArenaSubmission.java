package guide.domain.validation;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * Use case: assess one arena submission for topic 10's validation demo. The
 * controller has already let Jackson bind the body (an analysis endpoint
 * must NOT reject invalid payloads with 400 — judging them is its job), so
 * this use case is the policy shell: guard the basics, then delegate to the
 * {@link ValidationArena} exhibit. There is deliberately no "invalid input"
 * failure HERE — invalid input is the demo's product, not its error.
 */
public class AssessArenaSubmission {

    private final ValidationArena arena;

    public AssessArenaSubmission(ValidationArena arena) {
        this.arena = arena;
    }

    public Result<ValidationArena.ArenaVerdict> execute(ArenaSubmission submission, String requestId) {
        if (submission == null || requestId == null || requestId.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "submission and requestId are required",
                    Map.of("parameter", "submission/requestId")));
        }
        return Result.success(arena.assess(submission, requestId));
    }
}
