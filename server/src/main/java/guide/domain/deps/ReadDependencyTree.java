package guide.domain.deps;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import java.util.List;

/**
 * Use case: serve the summarized tree. The interesting decision is the
 * FAILURE SHAPE: a missing or unparseable snapshot resource is an
 * INTERNAL failure (it ships inside the jar; if it is broken the build
 * is broken), so it leaves as the typed 500 envelope rather than a raw
 * stack trace — same taxonomy as every other area.
 */
public final class ReadDependencyTree {

    private final DependencyTreeSource source;

    public ReadDependencyTree(DependencyTreeSource source) {
        this.source = source;
    }

    public Result<DependencyTreeSnapshot> execute() {
        try {
            return new Success<>(source.load(), List.of());
        } catch (RuntimeException e) {
            return new Failed<>(Failure.of(FailureKind.INTERNAL,
                    "dependency-tree snapshot is missing or unparseable: " + e.getMessage()));
        }
    }
}
