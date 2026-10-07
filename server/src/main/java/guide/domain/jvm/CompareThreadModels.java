package guide.domain.jvm;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;

/**
 * Use case: run BOTH threading models on the same task count — the demo's
 * headline view. Shares the tasks parsing/validation policy with
 * RunThreadsDemo so the two endpoints accept exactly the same inputs.
 */
public class CompareThreadModels {

    private final ThreadsExhibit exhibit;

    public CompareThreadModels(ThreadsExhibit exhibit) {
        this.exhibit = exhibit;
    }

    public Result<ThreadsExhibit.ComparisonReport> execute(String tasks) {
        Integer taskCount = RunThreadsDemo.parseTasks(tasks);
        if (taskCount == null) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "tasks must be an integer between 1 and " + ThreadsExhibit.MAX_TASKS,
                    Map.of("parameter", "tasks", "min", 1, "max", ThreadsExhibit.MAX_TASKS)));
        }
        return Result.success(exhibit.compare(taskCount));
    }
}
