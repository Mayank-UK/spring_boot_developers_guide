package guide.domain.mongo;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.List;
import java.util.Map;

/**
 * Use case: the recent-visits feed. The only domain rule is the bound —
 * clamped to a sane maximum so a caller cannot ask the store for a
 * million documents; out-of-range limits answer the typed 400 instead of
 * being silently coerced (fail loudly at the boundary, once).
 */
public class ReadRecentVisits {

    /** The feed's hard ceiling — a console, not an export. */
    public static final int MAX_LIMIT = 100;

    private final VisitStore visitStore;

    public ReadRecentVisits(VisitStore visitStore) {
        this.visitStore = visitStore;
    }

    public Result<List<VisitDocument>> execute(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "limit must be between 1 and " + MAX_LIMIT,
                    Map.of("parameter", "limit")));
        }
        return Result.success(visitStore.recent(limit));
    }
}
