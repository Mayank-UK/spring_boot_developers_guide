package guide.domain.jvm;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Locale;
import java.util.Map;

/**
 * Use case: one run of the streaming demo. Same policy shape as the threads
 * demo: chunks default when absent and must parse into MIN..MAX; charset
 * defaults to utf-8 and must be one the exhibit teaches (case-insensitive).
 * Both failures are VALIDATION — the 400 envelope with the valid values.
 */
public class RunStreamingDemo {

    private final StreamingExhibit exhibit;

    public RunStreamingDemo(StreamingExhibit exhibit) {
        this.exhibit = exhibit;
    }

    public Result<StreamingExhibit.StreamingReport> execute(String chunks, String charset) {
        Integer chunkCount = parseChunks(chunks);
        if (chunkCount == null) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "chunks must be an integer between " + StreamingExhibit.MIN_CHUNKS
                            + " and " + StreamingExhibit.MAX_CHUNKS,
                    Map.of("parameter", "chunks", "min", StreamingExhibit.MIN_CHUNKS,
                            "max", StreamingExhibit.MAX_CHUNKS)));
        }
        String normalized = charset == null || charset.isBlank()
                ? "utf-8"
                : charset.trim().toLowerCase(Locale.ROOT);
        if (!StreamingExhibit.SUPPORTED_CHARSETS.contains(normalized)) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "charset must be one of: " + String.join(", ",
                            StreamingExhibit.SUPPORTED_CHARSETS),
                    Map.of("parameter", "charset", "validValues",
                            StreamingExhibit.SUPPORTED_CHARSETS)));
        }
        return Result.success(exhibit.run(chunkCount, normalized));
    }

    /** Null/blank means "use the default"; anything else must parse and range-check. */
    static Integer parseChunks(String raw) {
        if (raw == null || raw.isBlank()) {
            return StreamingExhibit.DEFAULT_CHUNKS;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < StreamingExhibit.MIN_CHUNKS || value > StreamingExhibit.MAX_CHUNKS) {
                return null;
            }
            return value;
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
