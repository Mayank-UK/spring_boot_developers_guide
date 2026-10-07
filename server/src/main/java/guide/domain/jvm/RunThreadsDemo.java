package guide.domain.jvm;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.List;
import java.util.Map;

/**
 * Use case: one run of the threads demo (platform pool or virtual threads).
 * Owns the parameter policy the presentation edge trusts: tasks defaults
 * when absent and must parse into 1..MAX_TASKS; mode must name a model.
 * Every bad input becomes a typed VALIDATION failure — the 400 envelope —
 * never a 500.
 */
public class RunThreadsDemo {

    public static final List<String> MODES = List.of("platform", "virtual");

    private final ThreadsExhibit exhibit;

    public RunThreadsDemo(ThreadsExhibit exhibit) {
        this.exhibit = exhibit;
    }

    public Result<ThreadsExhibit.RunReport> execute(String tasks, String mode) {
        Integer taskCount = parseTasks(tasks);
        if (taskCount == null) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "tasks must be an integer between 1 and " + ThreadsExhibit.MAX_TASKS,
                    Map.of("parameter", "tasks", "min", 1, "max", ThreadsExhibit.MAX_TASKS)));
        }
        if (mode == null || !MODES.contains(mode)) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "mode must be one of: " + String.join(", ", MODES),
                    Map.of("parameter", "mode", "validValues", MODES)));
        }
        ThreadsExhibit.RunReport report = mode.equals("platform")
                ? exhibit.runPlatform(taskCount)
                : exhibit.runVirtual(taskCount);
        return Result.success(report);
    }

    /** Null/blank means "use the default"; anything else must parse and range-check. */
    static Integer parseTasks(String tasks) {
        if (tasks == null || tasks.isBlank()) {
            return ThreadsExhibit.DEFAULT_TASKS;
        }
        try {
            int value = Integer.parseInt(tasks.trim());
            if (value < 1 || value > ThreadsExhibit.MAX_TASKS) {
                return null;
            }
            return value;
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
