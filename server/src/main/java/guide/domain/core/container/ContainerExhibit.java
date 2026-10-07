package guide.domain.core.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The live exhibit behind topic 05's core:container demo — the running
 * container summarized as a serializable snapshot. Pure domain: no Spring
 * types, no I/O. All derivation happens here (package layout, convention
 * matching, sample pick) from the plain data in ContainerFacts, so tests pin
 * the behavior without booting anything.
 *
 * Nothing in the wire shape is a stable constant — bean counts move with every
 * sibling topic's beans — so consumers assert ranges and presence, never
 * exact totals. The sample bean is picked deterministically: the first bean
 * (alphabetically) from this repo's own packages that has at least one
 * dependency, so the card always shows real wiring, not a framework internal.
 */
public record ContainerExhibit(
        int totalBeans,
        List<PackageView> byPackage,
        List<String> useCaseBeans,
        List<String> useCaseConventions,
        BeanSnapshot sampleBean) {

    public ContainerExhibit {
        byPackage = byPackage == null ? List.of() : List.copyOf(byPackage);
        useCaseBeans = useCaseBeans == null ? List.of() : List.copyOf(useCaseBeans);
        useCaseConventions = useCaseConventions == null ? List.of() : List.copyOf(useCaseConventions);
    }

    /** One top-level package under guide/ and how many context beans live there. */
    public record PackageView(String name, int beans) {
    }

    /**
     * Folds a snapshot into the overview exhibit. Conventions arrive as a list
     * (the composition root injects every UseCaseConvention bean) and are
     * applied in order — any match claims the bean.
     */
    public static ContainerExhibit of(ContainerFacts facts, List<UseCaseConvention> conventions) {
        List<BeanSnapshot> beans = facts.beans();
        List<UseCaseConvention> rules =
                conventions == null ? List.of() : List.copyOf(conventions);
        return new ContainerExhibit(
                beans.size(),
                packageLayout(beans),
                useCaseBeanNames(beans, rules),
                rules.stream().map(UseCaseConvention::name).toList(),
                sampleBean(beans));
    }

    /**
     * Groups this repo's own beans by top-level package under guide/ — the
     * same fold AppInfoExhibit performs for topic 04, now over individual bean
     * snapshots instead of raw type names. Framework beans (everything outside
     * guide.*) are deliberately absent: the table is about OUR wiring.
     */
    private static List<PackageView> packageLayout(List<BeanSnapshot> beans) {
        Map<String, Integer> counts = new TreeMap<>();
        for (BeanSnapshot bean : beans) {
            String className = bean.className();
            if (className == null) {
                continue;
            }
            String[] parts = className.split("\\.");
            if (parts.length < 2 || !"guide".equals(parts[0])) {
                continue;
            }
            String segment = parts.length == 2 ? "(root)" : parts[1];
            counts.merge(segment, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new PackageView(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** Sorted, distinct bean names claimed by any convention. */
    private static List<String> useCaseBeanNames(List<BeanSnapshot> beans, List<UseCaseConvention> conventions) {
        TreeSet<String> names = new TreeSet<>();
        for (BeanSnapshot bean : beans) {
            for (UseCaseConvention convention : conventions) {
                if (convention.matches(bean.className())) {
                    names.add(bean.name());
                    break;
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * The deterministic sample: first alphabetically among guide.* beans with
     * at least one dependency — a bean that demonstrates injection — falling
     * back to any guide.* bean, then to any bean at all, so a context of one
     * bean still yields a sample.
     */
    private static BeanSnapshot sampleBean(List<BeanSnapshot> beans) {
        List<BeanSnapshot> sorted = new ArrayList<>(beans);
        sorted.sort(java.util.Comparator.comparing(BeanSnapshot::name));
        return sorted.stream()
                .filter(bean -> bean.className() != null && bean.className().startsWith("guide."))
                .filter(bean -> !bean.dependsOn().isEmpty())
                .findFirst()
                .or(() -> sorted.stream()
                        .filter(bean -> bean.className() != null
                                && bean.className().startsWith("guide."))
                        .findFirst())
                .orElse(beans.isEmpty() ? unknownSample() : sorted.getFirst());
    }

    private static BeanSnapshot unknownSample() {
        return new BeanSnapshot("(no beans)", "unknown", "unknown", null, List.of(), false);
    }
}
