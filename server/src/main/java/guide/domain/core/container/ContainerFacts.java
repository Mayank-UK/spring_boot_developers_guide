package guide.domain.core.container;

import java.util.List;

/**
 * Raw inputs of topic 05's core:container demo: every bean definition the
 * running context holds, snapshotted at request time. The adapter that builds
 * this record is the only Spring-aware piece of the demo; from here on the
 * exhibit works over plain data — which is what lets the domain unit-test the
 * derivation with hand-built facts and no container at all.
 */
public record ContainerFacts(List<BeanSnapshot> beans) {

    public ContainerFacts {
        beans = beans == null ? List.of() : List.copyOf(beans);
    }
}
