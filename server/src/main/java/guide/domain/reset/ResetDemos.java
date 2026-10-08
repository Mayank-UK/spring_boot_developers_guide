package guide.domain.reset;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Use case: the shared reset sweep behind POST /api/demo/reset. Every
 * registered {@link ResettableDemo} area is offered a reset; the answer is
 * a REPORT row per area, never a transaction — one area's failure is
 * recorded (reset=false, the reason in the note) while the rest still run,
 * because a sweep that aborted on the first dead Redis would leave the
 * jobs console dirty for no reason.
 *
 * Determinism: the constructor SORTS the registered areas by id, so both
 * the report's row order and the known-areas list in the validation
 * message are stable regardless of bean registration order — tests pin
 * them without knowing how Spring collected the beans.
 *
 * The filter: {@code ?areas=messaging,jobs} is validated HERE (the domain
 * boundary the domain controls) against the registered ids — an unknown
 * area is a validation failure (the 400 envelope) whose message carries
 * the derived known list, the same one-value-source discipline as
 * JobType.wireNames().
 */
public final class ResetDemos {

    /** One area's outcome: what it is, whether it reset, and why if not. */
    public record AreaReport(String area, boolean reset, String note) {
    }

    /** The sweep's answer: one row per reset area. */
    public record ResetReport(List<AreaReport> reset) {
    }

    private final List<ResettableDemo> demos;

    public ResetDemos(List<ResettableDemo> demos) {
        Objects.requireNonNull(demos, "demos");
        Map<String, ResettableDemo> byId = new LinkedHashMap<>();
        for (ResettableDemo demo : demos) {
            Objects.requireNonNull(demo, "demo");
            if (byId.putIfAbsent(demo.id(), demo) != null) {
                throw new IllegalStateException(
                        "two ResettableDemo registrations share the id '" + demo.id() + "'");
            }
        }
        List<ResettableDemo> sorted = new ArrayList<>(byId.values());
        sorted.sort(Comparator.comparing(ResettableDemo::id));
        this.demos = List.copyOf(sorted);
    }

    /** The registered area ids, sorted — the filter's vocabulary, derived. */
    public List<String> knownAreas() {
        return demos.stream().map(ResettableDemo::id).toList();
    }

    /**
     * Reset every registered area, or only the requested subset. An empty
     * or null request means ALL areas; an area name the registry does not
     * know is a validation failure that resets NOTHING (typo protection:
     * "messaging,job" would silently skip the jobs console otherwise).
     */
    public Result<ResetReport> execute(List<String> requestedAreas) {
        List<ResettableDemo> targets = new ArrayList<>(demos);
        if (requestedAreas != null && !requestedAreas.isEmpty()) {
            Result<List<ResettableDemo>> selection = select(requestedAreas);
            if (selection instanceof Failed<List<ResettableDemo>> failed) {
                return Result.failed(failed.failure());
            }
            if (selection instanceof Success<List<ResettableDemo>> success) {
                targets.clear();
                targets.addAll(success.value());
                targets.sort(Comparator.comparing(ResettableDemo::id));
            }
        }
        List<AreaReport> rows = new ArrayList<>();
        for (ResettableDemo demo : targets) {
            rows.add(resetOne(demo));
        }
        return Result.success(new ResetReport(List.copyOf(rows)));
    }

    private Result<List<ResettableDemo>> select(List<String> requestedAreas) {
        List<String> unknown = new ArrayList<>();
        Set<ResettableDemo> chosen = new LinkedHashSet<>();
        for (String area : requestedAreas) {
            String key = area == null ? "" : area.trim();
            if (key.isEmpty()) {
                continue;
            }
            boolean found = false;
            for (ResettableDemo demo : demos) {
                if (demo.id().equals(key)) {
                    chosen.add(demo);
                    found = true;
                    break;
                }
            }
            if (!found && !unknown.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "unknown area" + (unknown.size() > 1 ? "s" : "") + ": "
                            + String.join(", ", unknown)
                            + " (known areas: " + String.join(", ", knownAreas()) + ")"));
        }
        return Result.success(List.copyOf(chosen));
    }

    /**
     * One area, both failure shapes handled: a Failed Result becomes the
     * row's note verbatim; an unexpected THROW becomes the row's note too
     * (belt and braces — a reset adapter that throws still cannot abort
     * the sweep or swallow its own failure).
     */
    private static AreaReport resetOne(ResettableDemo demo) {
        try {
            return switch (demo.reset()) {
                case Success<String> success ->
                        new AreaReport(demo.id(), true, success.value());
                case Failed<String> failed ->
                        new AreaReport(demo.id(), false, failed.failure().message());
            };
        } catch (RuntimeException unexpected) {
            return new AreaReport(demo.id(), false,
                    "reset threw " + unexpected.getClass().getSimpleName()
                            + ": " + unexpected.getMessage());
        }
    }
}
