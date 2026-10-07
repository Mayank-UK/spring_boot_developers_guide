package guide.domain.core.container;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;
import java.util.Objects;

/**
 * Use case: report one named bean of the running container (the detail behind
 * GET /api/demo/core/beans/{name}). Input is hostile like everywhere else: a
 * blank name leaves as a VALIDATION failure, a well-formed name the container
 * does not hold leaves as a NOT_FOUND failure carrying the name in its detail
 * map — so the terminal tour's 404 demonstrates the typed envelope, not a raw
 * 500. The lookup re-snapshots per call: the container answers about itself
 * as it is NOW.
 */
public class InspectBean {

    private final ContainerSnapshotSource source;

    public InspectBean(ContainerSnapshotSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public Result<BeanSnapshot> execute(String beanName) {
        if (beanName == null || beanName.isBlank()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "bean name must not be blank",
                    Map.of("parameter", "name")));
        }
        ContainerFacts facts = Objects.requireNonNull(source.snapshot(), "snapshot");
        return facts.beans().stream()
                .filter(bean -> beanName.equals(bean.name()))
                .findFirst()
                .<Result<BeanSnapshot>>map(Result::success)
                .orElse(Result.failed(Failure.of(FailureKind.NOT_FOUND,
                        "no bean named '" + beanName.trim() + "' in this context",
                        Map.of("bean", beanName.trim(), "demo", "core:container"))));
    }
}
