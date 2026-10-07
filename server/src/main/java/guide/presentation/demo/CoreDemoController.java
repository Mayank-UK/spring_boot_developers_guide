package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.core.container.BeanSnapshot;
import guide.domain.core.container.ContainerExhibit;
import guide.domain.core.container.InspectBean;
import guide.domain.core.container.ShowContainer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints for topic 05 (spring-core) — the live half of the
 * application-context section:
 * - GET /api/demo/core/beans → 200 overview of the RUNNING container: total
 *   bean count, this repo's beans grouped by top-level package, the use-case
 *   beans claimed by the registered naming conventions, and one deterministic
 *   sample bean with its scope, factory method, and dependencies.
 * - GET /api/demo/core/beans/{name} → 200 that bean's detail, or the typed
 *   404 envelope when no such bean exists.
 *
 * Thin like every controller: no parsing beyond the path variable, ONE use
 *   case call per endpoint, the Result mapped onto HTTP.
 */
@RestController
public class CoreDemoController {

    private final ShowContainer showContainer;
    private final InspectBean inspectBean;

    public CoreDemoController(ShowContainer showContainer, InspectBean inspectBean) {
        this.showContainer = showContainer;
        this.inspectBean = inspectBean;
    }

    /** GET /api/demo/core/beans → 200 overview of the running context. */
    @GetMapping("/api/demo/core/beans")
    ContainerExhibit beans() {
        return unwrap(showContainer.execute());
    }

    /** GET /api/demo/core/beans/{name} → 200 one bean's detail, 404 envelope when unknown. */
    @GetMapping("/api/demo/core/beans/{name}")
    BeanSnapshot bean(@PathVariable String name) {
        return unwrap(inspectBean.execute(name));
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
