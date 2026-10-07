package guide.domain.caching;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Locale;
import java.util.Map;

/**
 * Use case: one topic's stats, through ONE OF THE TWO CACHING PATHS — the
 * use-case-level switch that makes the topic's central comparison runnable:
 * the same expensive read, the same backend, mode=explicit vs
 * mode=annotation, and a response that reports WHICH path answered, its
 * HIT/MISS verdict, and the KEY it traveled under (the keys cannot collide:
 * "stats:rest" vs "topicStats:rest" — different namespaces on purpose, so
 * switching modes never serves the other path's entry by accident).
 */
public class GetTopicStats {

    /** The wire spellings the demo accepts; EXPLICIT is the default. */
    public enum Mode {
        EXPLICIT("explicit"), ANNOTATION("annotation");

        private final String wireName;

        Mode(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        /** Null/blank → EXPLICIT; anything else is a typed validation failure. */
        public static Result<Mode> parse(String wire) {
            if (wire == null || wire.isBlank()) {
                return Result.success(EXPLICIT);
            }
            String normalized = wire.strip().toLowerCase(Locale.ROOT);
            for (Mode mode : values()) {
                if (mode.wireName.equals(normalized)) {
                    return Result.success(mode);
                }
            }
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "mode must be \"explicit\" or \"annotation\"",
                    Map.of("fields", Map.of("mode", "must be \"explicit\" or \"annotation\""))));
        }
    }

    private final CachedTopicStats explicit;
    private final AnnotationPathStats annotation;

    public GetTopicStats(CachedTopicStats explicit, AnnotationPathStats annotation) {
        this.explicit = explicit;
        this.annotation = annotation;
    }

    public Result<StatsOutcome> execute(String topicId, Mode mode) {
        if (topicId == null || topicId.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "topicId must be a non-empty string", Map.of("parameter", "topicId")));
        }
        if (mode == null) {
            mode = Mode.EXPLICIT;
        }
        return Result.success(switch (mode) {
            case EXPLICIT -> explicit.stats(topicId);
            case ANNOTATION -> annotation.stats(topicId);
        });
    }
}
