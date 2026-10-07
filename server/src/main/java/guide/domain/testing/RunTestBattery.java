package guide.domain.testing;

import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.core.scenario.ScenarioStore;
import guide.domain.posts.GetPost;
import guide.domain.posts.ListPosts;
import guide.domain.posts.PagedPosts;
import guide.domain.posts.Post;
import guide.domain.posts.PostFilter;
import guide.domain.posts.PostSort;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Use case behind the test-runner demo (topic 34): run a small in-process
 * battery of example test-style checks ACROSS THE PYRAMID'S LAYERS and
 * return each check's verdict — name, layer, status, duration — exactly the
 * way a real test runner reports. Every check exercises the app's genuine
 * machinery (real use cases, real wired ports); nothing is scripted to
 * pass:
 *
 * - UNIT: pure domain rules over the posts use cases — a hostile page is a
 *   validation failure, an unknown id maps to not_found carrying that id.
 * - DATA: a paged read through whatever repository the app wired (the fake
 *   seed or live PostgreSQL — behavior, not counts, is the assertion).
 * - WEB: a self-call over the real HTTP port (absent in slices → SKIPPED,
 *   reported, never faked).
 * - INJECTED: when the scenario's failure injection is ON, one deliberately
 *   wrong assertion FAILS — so the demo can show what red looks like: a
 *   named check, a reason, a duration. The runner's honesty about failure
 *   is the point of the demo.
 *
 * Deterministic and fast by design: no clocks but its own stopwatch, no
 * randomness, no sleeps, milliseconds per check.
 */
public final class RunTestBattery {

    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    /** One check's verdict, runner-shaped: name, layer, status, duration, and the why. */
    public record CheckResult(String name, String layer, String status, double durationMs, String detail) {
    }

    /** The battery's answer: the scenario it ran under, the tally, and every check. */
    public record BatteryReport(boolean failureInjected, int passed, int failed, int skipped,
            double totalDurationMs, List<CheckResult> checks) {
    }

    /** Internal per-check outcome before timing is applied. */
    private record Outcome(String status, String detail) {

        static Outcome passed(String detail) {
            return new Outcome(PASSED, detail);
        }

        static Outcome failed(String detail) {
            return new Outcome(FAILED, detail);
        }

        static Outcome skipped(String detail) {
            return new Outcome(SKIPPED, detail);
        }
    }

    private static final String INJECTED_CHECK =
            "injected: a deliberately wrong assertion fails the check";

    private final ListPosts listPosts;
    private final GetPost getPost;
    private final WebProbe webProbe;
    private final ScenarioStore scenario;

    /** Collaborators may be null: absent machinery is reported as SKIPPED, never faked. */
    public RunTestBattery(ListPosts listPosts, GetPost getPost, WebProbe webProbe, ScenarioStore scenario) {
        this.listPosts = listPosts;
        this.getPost = getPost;
        this.webProbe = Objects.requireNonNull(webProbe, "webProbe");
        this.scenario = Objects.requireNonNull(scenario, "scenario");
    }

    public BatteryReport execute() {
        long startNs = System.nanoTime();
        boolean injected = scenario.get().injectFailure();
        List<CheckResult> checks = List.of(
                timed("unit: bounds — a hostile page is a validation failure", "unit", this::checkBounds),
                timed("unit: absence — an unknown id maps to not_found carrying the id", "unit", this::checkAbsence),
                timed("data: a paged read serves titled posts within the page bound", "data", this::checkPagedRead),
                timed("web: a self-call answers the scenario endpoint", "web", this::checkSelfCall),
                injected
                        ? timed(INJECTED_CHECK, "injected", this::checkInjectedFailure)
                        : new CheckResult(INJECTED_CHECK, "injected", SKIPPED, 0.0,
                                "failure injection is off — flip the scenario switch to see a check fail"));
        double totalMs = (System.nanoTime() - startNs) / 1_000_000.0;
        return new BatteryReport(injected,
                (int) checks.stream().filter(check -> PASSED.equals(check.status())).count(),
                (int) checks.stream().filter(check -> FAILED.equals(check.status())).count(),
                (int) checks.stream().filter(check -> SKIPPED.equals(check.status())).count(),
                totalMs, checks);
    }

    /** Times one check the way a runner does: outcome first, duration around it, surprises become failures. */
    private CheckResult timed(String name, String layer, Supplier<Outcome> check) {
        long startNs = System.nanoTime();
        Outcome outcome;
        try {
            outcome = check.get();
        } catch (RuntimeException unexpected) {
            outcome = Outcome.failed("threw " + unexpected.getClass().getSimpleName()
                    + ": " + unexpected.getMessage());
        }
        return new CheckResult(name, layer, outcome.status(),
                (System.nanoTime() - startNs) / 1_000_000.0, outcome.detail());
    }

    // ---- the checks, one per layer ----

    private Outcome checkBounds() {
        if (listPosts == null) {
            return Outcome.skipped("no posts substrate in this context");
        }
        Result<PagedPosts> hostile = listPosts.execute(-1, 10, PostSort.DEFAULT, PostFilter.NONE);
        if (hostile instanceof Failed<PagedPosts> failed
                && failed.failure().kind() == FailureKind.VALIDATION) {
            return Outcome.passed("page=-1 rejected as a validation failure, exactly as the boundary promises");
        }
        return Outcome.failed("page=-1 was not rejected as a validation failure");
    }

    private Outcome checkAbsence() {
        if (getPost == null) {
            return Outcome.skipped("no posts substrate in this context");
        }
        Result<Post> missing = getPost.execute("no-such-post");
        if (missing instanceof Failed<Post> failed
                && failed.failure().kind() == FailureKind.NOT_FOUND
                && "no-such-post".equals(failed.failure().details().get("postId"))) {
            return Outcome.passed("the failure carried postId=no-such-post in its details");
        }
        return Outcome.failed("absence did not map to a not_found failure carrying the id");
    }

    private Outcome checkPagedRead() {
        if (listPosts == null) {
            return Outcome.skipped("no posts substrate in this context");
        }
        Result<PagedPosts> page = listPosts.execute(0, 10, PostSort.DEFAULT, PostFilter.NONE);
        if (!(page instanceof Success<PagedPosts> success)) {
            return Outcome.failed("the paged read did not succeed");
        }
        List<Post> items = success.value().items();
        boolean titled = items.stream().allMatch(post -> post.title() != null && !post.title().isBlank());
        // Behavior, not volume: the seed's exact count is the substrate's
        // business (live mode adds and deletes rows), so the assertion is the
        // PAGE BOUND plus the row SHAPE — either backing store satisfies it.
        if (!items.isEmpty() && items.size() <= 10 && titled) {
            return Outcome.passed("page 0 at size 10 served " + items.size() + " titled posts, bound held");
        }
        return Outcome.failed("expected 1..10 titled posts, got " + items.size()
                + (titled ? "" : " (some titles blank)"));
    }

    private Outcome checkSelfCall() {
        Optional<Integer> status = webProbe.selfCallScenarioStatus();
        if (status.isEmpty()) {
            return Outcome.skipped("no live web server in this context (a slice) — reported, not faked");
        }
        if (status.get() == 200) {
            return Outcome.passed("GET /api/demo/scenario answered 200 over real HTTP");
        }
        return Outcome.failed("expected 200 from the scenario endpoint, got " + status.get());
    }

    private Outcome checkInjectedFailure() {
        // The deliberately wrong expectation — this is what a bug looks like
        // from the runner's side: red, named, carrying the reason.
        boolean twoPlusTwoIsFive = 2 + 2 == 5;
        return twoPlusTwoIsFive
                ? Outcome.passed("mathematics held")
                : Outcome.failed("expected 2 + 2 = 5 — the assertion is wrong on purpose "
                        + "(failure injection is ON)");
    }
}
