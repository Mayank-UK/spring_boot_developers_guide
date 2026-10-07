package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.deps.DependencyTreeSnapshot;
import guide.domain.deps.ReadDependencyTree;
import guide.domain.deps.ReadManifest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 31 (dependency-management) — the live half of
 * the tree-and-enforcer section:
 * - GET /api/demo/deps/manifest → 200 the RUNTIME-TRUTH inventory: curated
 *   key artifacts with the version the JVM actually loaded (manifest or
 *   jar-name — the response says which), plus the flagged-choices registry
 *   (why each explicitly-pinned dependency was pinned).
 * - GET /api/demo/deps/tree → 200 the summarized dependency graph from the
 *   checked-in mvn dependency:tree snapshot (labeled as a snapshot — no
 *   live maven runs at request time, by design).
 *
 * Thin like every controller: one use case per endpoint, the Result mapped
 * onto HTTP. The manifest use case is pure computation with no failure
 * mode, so it returns its value directly; the tree use case can fail
 * (missing/corrupt resource) and unwraps through the typed envelope.
 */
@RestController
public class DepsDemoController {

    private final ReadManifest readManifest;
    private final ReadDependencyTree readDependencyTree;

    public DepsDemoController(ReadManifest readManifest, ReadDependencyTree readDependencyTree) {
        this.readManifest = readManifest;
        this.readDependencyTree = readDependencyTree;
    }

    /** GET /api/demo/deps/manifest → 200 runtime-truth dependency inventory. */
    @GetMapping("/api/demo/deps/manifest")
    ReadManifest.DependencyManifest manifest() {
        return readManifest.execute();
    }

    /** GET /api/demo/deps/tree → 200 the summarized static dependency-tree snapshot. */
    @GetMapping("/api/demo/deps/tree")
    DependencyTreeSnapshot tree() {
        return unwrap(readDependencyTree.execute());
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
