package guide.domain.core.container;

import guide.core.result.Result;
import java.util.List;
import java.util.Objects;

/**
 * Use case: summarize the running container for topic 05's core:container
 * demo (the overview behind GET /api/demo/core/beans). The conventions arrive
 * through the constructor as a LIST — the composition root injects every
 * UseCaseConvention bean it registered, which is this demo's live example of
 * collection injection: two beans, one interface, one ordered List parameter,
 * no ambiguity because the consumer asked for ALL of them.
 */
public class ShowContainer {

    private final ContainerSnapshotSource source;
    private final List<UseCaseConvention> conventions;

    public ShowContainer(ContainerSnapshotSource source, List<UseCaseConvention> conventions) {
        this.source = Objects.requireNonNull(source, "source");
        this.conventions = conventions == null ? List.of() : List.copyOf(conventions);
    }

    public Result<ContainerExhibit> execute() {
        ContainerFacts facts = Objects.requireNonNull(source.snapshot(), "snapshot");
        return Result.success(ContainerExhibit.of(facts, conventions));
    }
}
