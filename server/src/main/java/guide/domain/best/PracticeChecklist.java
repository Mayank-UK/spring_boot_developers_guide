package guide.domain.best;

import guide.core.errors.FailureKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The engine behind topic 42's best:checklist demo — the guide's practices
 * as a checklist the RUNNING APP grades against itself. Every item is either
 *
 * - LIVE: a verdict the app can self-report deterministically from in-process
 *   state (the registered failure taxonomy, the validated content registry,
 *   the effective configuration properties). No clocks, no randomness, no
 *   environment beyond the process — the same request answers the same way
 *   every time, which is what makes the MockMvc tests assertions rather
 *   than hopes.
 *
 * - VERIFY: a pointer to where the truth lives when it is environmental
 *   (CI green, a database actually parameterizing, a probe answering) — one
 *   curl command whose output IS the verdict. The checklist never claims a
 *   pass it cannot see; that honesty is itself the practice being taught.
 *
 * The FACTS arrive as a plain record built at the composition root: the only
 * layer allowed to talk to Spring reads the real Environment and hands the
 * domain values. This class is framework-free (the dependency rule) and its
 * categories mirror the topic's sections.
 */
public final class PracticeChecklist {

    /** "pass" for a live self-verdict; "verify" for an environmental pointer. */
    public static final String VERDICT_PASS = "pass";
    public static final String VERDICT_VERIFY = "verify";

    /** The categories, in topic order — the client groups items by this field. */
    public static final List<String> CATEGORIES = List.of(
            "setup", "api", "security", "data", "reliability",
            "observability", "performance", "operations", "process", "humans");

    /** The app-state facts the live items grade; built from real state, never guessed. */
    public record Facts(
            String deployEnvironment,
            boolean appConfigValidatedAtBoot,
            int topicCount,
            boolean registryValid,
            boolean virtualThreadsEnabled,
            boolean gracefulShutdown,
            String shutdownPhaseTimeout,
            boolean healthProbesEnabled,
            boolean prometheusExposed) {
    }

    /** One checklist row: category + id + title, then the verdict's evidence or pointer. */
    public record CheckItem(
            String category,
            String id,
            String title,
            String verdict,
            String evidence,
            String verify) {
    }

    /** The tallies: what was graded live, what passed, what needs a verify run. */
    public record Counts(int liveChecks, int livePasses, int verifyPointers) {
    }

    /** The whole report: every item, the tallies, and the reading lines the panel shows. */
    public record ChecklistReport(List<CheckItem> items, Counts counts, List<String> reading) {

        public ChecklistReport {
            items = List.copyOf(items);
            reading = List.copyOf(reading);
        }
    }

    /** Grades the facts against the guide's practices; pure, deterministic. */
    public ChecklistReport evaluate(Facts facts) {
        List<CheckItem> items = new ArrayList<>();

        // ---- setup ----
        items.add(live("setup", "typed-config", "Configuration is one typed, boot-validated record",
                facts.appConfigValidatedAtBoot(),
                "guide.env.* binds into the AppConfig record; deploy-environment=\""
                        + facts.deployEnvironment() + "\" was accepted from local|dev|staging|prod "
                        + "— a typo would have failed the BOOT, not the first request"));
        items.add(live("setup", "content-registry", "Content ids are unique, validated at startup",
                facts.registryValid(),
                facts.topicCount() + " topics constructed into a ContentRegistry that validates "
                        + "duplicate topic/section/block ids and blank ids at construction — "
                        + "the context never boots with broken content"));
        items.add(verify("setup", "managed-versions", "Dependency versions are managed, not hand-pinned",
                "the pinned-version ledger and its drift guard, served live",
                "curl -s localhost:8080/api/demo/deps/manifest | head -30"));

        // ---- api ----
        items.add(live("api", "one-error-envelope", "One error taxonomy, one envelope end to end",
                failureTaxonomyIsSound(),
                FailureKind.values().length + " FailureKinds carry one HTTP status and one wire code "
                        + "each (" + FailureKind.VALIDATION.httpStatus() + " "
                        + FailureKind.VALIDATION.errorCode() + " … "
                        + FailureKind.INTERNAL.httpStatus() + " " + FailureKind.INTERNAL.errorCode()
                        + "); every failure renders {error:{code,message,requestId,…}}"));
        items.add(verify("api", "thin-controllers", "Controllers parse, call one use case, map the answer",
                "an unrouted path still answers the envelope (404, not a stack trace)",
                "curl -si localhost:8080/api/content/nope | head -4"));

        // ---- security ----
        items.add(verify("security", "hash-and-forget", "Passwords are hashed, never stored or logged",
                "the hashing lab: bcrypt-family cost, verify-against-the-hash, timings measured",
                "curl -s localhost:8080/api/demo/auth/hashing/compare"));

        // ---- data ----
        items.add(verify("data", "parameterized-sql", "Values bind; only structure is composed SQL",
                "one payload through both doors — bind parameter vs concatenation — against a sandbox table",
                "curl -s -X POST localhost:8080/api/demo/db/sql/safety -H 'content-type: application/json' -d '{}'"));
        items.add(verify("data", "ports-and-adapters", "The dependency rule holds: presentation → domain ← data",
                "the layer map the architecture demo walks from the real bytecode-free import scan",
                "curl -s localhost:8080/api/demo/arch/map | head -20"));

        // ---- reliability ----
        items.add(verify("reliability", "breaker-behind-port", "Resilience machinery hides behind a domain port",
                "the breaker's live state machine, served through BreakerPort vocabulary",
                "curl -s localhost:8080/api/demo/reliability/breaker"));

        // ---- observability ----
        items.add(live("observability", "probes-and-metrics", "Health probes split, metrics exposed",
                facts.healthProbesEnabled() && facts.prometheusExposed(),
                "management.endpoint.health.probes.enabled=" + facts.healthProbesEnabled()
                        + " (liveness/readiness at /actuator/health/*), exposure includes "
                        + (facts.prometheusExposed() ? "prometheus" : "nothing")
                        + " — the k8s pair and the graphs, on purpose"));
        items.add(verify("observability", "structured-logs", "Logs are fields: one JSON line per request, id joined",
                "the log viewer renders recent request lines as structured events",
                "curl -s localhost:8080/api/demo/obs/logs | head -30"));

        // ---- performance ----
        items.add(live("performance", "virtual-threads", "Request threads are virtual",
                facts.virtualThreadsEnabled(),
                "spring.threads.virtual.enabled=" + facts.virtualThreadsEnabled()
                        + " — one yaml line, twelve-factor overridable; parked request threads "
                        + "hold no OS thread while they wait"));
        items.add(verify("performance", "measured-not-guessed", "Performance claims are measured, medians not samples",
                "the compression benchmark: warm-up, two gzip levels, medians over passes",
                "curl -s 'localhost:8080/api/demo/perf/bench?payload=medium&passes=16' | head -20"));

        // ---- operations ----
        items.add(live("operations", "graceful-shutdown", "Shutdown is graceful and time-bounded",
                facts.gracefulShutdown(),
                "server.shutdown=" + (facts.gracefulShutdown() ? "graceful" : "immediate")
                        + ", phase budget " + facts.shutdownPhaseTimeout()
                        + " — SIGTERM drains in-flight work; k8s's grace period is deliberately longer"));

        // ---- process ----
        items.add(verify("process", "ci-green", "The suite is the definition of done, and it runs on every push",
                "the workflow file runs mvn test + npm ci/test/build per package",
                "curl -s 'https://api.github.com/repos/Mayank-UK/spring_boot_developers_guide/actions/runs?per_page=1'"));

        // ---- humans ----
        items.add(verify("humans", "review-the-practice", "Small PRs, reviewed, against the checklist",
                "the repo's own pull-request history is the practice made visible",
                "curl -s 'https://api.github.com/repos/Mayank-UK/spring_boot_developers_guide/pulls?state=all&per_page=5'"));

        int liveChecks = (int) items.stream().filter(item -> !VERDICT_VERIFY.equals(item.verdict())).count();
        int livePasses = (int) items.stream()
                .filter(item -> VERDICT_PASS.equals(item.verdict())).count();
        int verifyPointers = (int) items.stream()
                .filter(item -> VERDICT_VERIFY.equals(item.verdict())).count();
        List<String> reading = List.of(
                livePasses + " of " + liveChecks + " live checks pass: those verdicts were read from "
                        + "THIS process — the taxonomy, the registry, the effective properties — "
                        + "so they cannot flake.",
                verifyPointers + " items are VERIFY by design: their truth is environmental (a "
                        + "database, a cluster, a CI run), so the checklist hands you the curl and "
                        + "never claims the pass for them.",
                "the categories mirror the topic's sections — read the section, then find its row "
                        + "here and run it against your own app.");

        return new ChecklistReport(List.copyOf(items),
                new Counts(liveChecks, livePasses, verifyPointers), reading);
    }

    // ---- item builders -------------------------------------------------------

    private static CheckItem live(String category, String id, String title,
            boolean verdict, String evidence) {
        return new CheckItem(category, id, title, verdict ? VERDICT_PASS : "fail", evidence, null);
    }

    private static CheckItem verify(String category, String id, String title,
            String whatToLookFor, String verifyCommand) {
        return new CheckItem(category, id, title, VERDICT_VERIFY, whatToLookFor, verifyCommand);
    }

    /**
     * The taxonomy is sound when every kind carries a status and a code and
     * neither repeats — one kind, one meaning, checked from the registered
     * enum itself rather than a hardcoded copy.
     */
    private static boolean failureTaxonomyIsSound() {
        Set<Integer> statuses = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (FailureKind kind : FailureKind.values()) {
            if (!statuses.add(kind.httpStatus()) || !codes.add(kind.errorCode())) {
                return false;
            }
        }
        return !statuses.isEmpty();
    }
}
