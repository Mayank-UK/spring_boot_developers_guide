package guide.domain.mongo;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * Use case: the stats snapshot — every aggregate the console renders,
 * assembled from one port view. topicId is an OPTIONAL filter: present, the
 * topicVisits/avgDuration numbers describe that topic alone (topTopics and
 * the histogram stay global — the demo's fixed axes); absent, everything
 * describes the whole population. Malformed filters answer the typed 400;
 * an empty store is NOT a failure, just zeros (no visits yet is a fact).
 */
public class ReadVisitStats {

    /** The console's fixed axes: how many topics and histogram rows it renders. */
    public static final int TOP_TOPICS_LIMIT = 5;

    private final VisitStore visitStore;

    public ReadVisitStats(VisitStore visitStore) {
        this.visitStore = visitStore;
    }

    public Result<VisitStats> execute(String topicId) {
        boolean filtered = topicId != null && !topicId.isBlank();
        if (filtered) {
            String problem = VisitDocument.topicIdProblem(topicId);
            if (problem != null) {
                return Result.failed(Failure.of(FailureKind.VALIDATION,
                        "topicId " + problem, Map.of("parameter", "topicId")));
            }
        }
        long total = visitStore.countAll();
        return Result.success(new VisitStats(
                total,
                filtered ? visitStore.countByTopic(topicId) : total,
                filtered,
                visitStore.avgDurationMs(filtered ? topicId : null),
                visitStore.topTopics(TOP_TOPICS_LIMIT),
                visitStore.byLocaleHistogram()));
    }
}
