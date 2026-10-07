package guide.domain.scaling;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Result;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The scaling:stateless demo's engine (topic 40) — statelessness,
 * MEASURED rather than asserted. Every hit routes to one of the personas
 * (a sticky pick like a session cookie would force, or the router's
 * round-robin like a load balancer with no affinity), bumps that
 * persona's PRIVATE counter (server-local state, diverging by
 * construction), and then answers the one question that separates the two
 * worlds: where did the value that matters come from?
 *
 * - store=external (the default): the shared {@link ExternalCounterStore}
 *   increments — the redis/PostgreSQL arrangement. Any persona, any
 *   routing order, the caller sees one monotonic truth.
 * - store=local: the persona's own memory is the value — the session-in-
 *   heap arrangement. Route the same caller to the other persona and the
 *   answer FORGETS everything the first one saw: the report shows the
 *   loss as a number (lostIfLocal), not a vibe.
 *
 * Deterministic by construction: no clock reads, no randomness, a
 * resettable routing cursor, and every number derived from the request
 * sequence the tests (and the reader) drive.
 */
public final class ProbeStatelessness {

    /** Wire view of one simulated replica: its id and its private counter. */
    public record InstanceView(String id, long localState) {
    }

    /**
     * The measured answer. reportedValue is what THIS caller was told;
     * lostIfLocal is what a local-memory world would have forgotten
     * (total requests minus this persona's own count) — the whole
     * statelessness argument in two fields.
     */
    public record Probe(
            String processInstanceId,
            Instant processStartedAt,
            long totalRequests,
            String routedTo,
            String routing,
            String stateHome,
            List<InstanceView> instances,
            long externalState,
            long reportedValue,
            long lostIfLocal,
            List<String> reading) {
    }

    private static final String LOCAL = "local";
    private static final String EXTERNAL = "external";

    private final List<InstancePersona> instances;
    private final ExternalCounterStore externalStore;
    private final ProcessIdentity processIdentity;
    private long routingCursor;

    public ProbeStatelessness(List<InstancePersona> instances, ExternalCounterStore externalStore,
            ProcessIdentity processIdentity) {
        if (instances == null || instances.size() < 2) {
            throw new IllegalArgumentException("the statelessness probe needs at least two personas");
        }
        this.instances = List.copyOf(instances);
        this.externalStore = externalStore;
        this.processIdentity = processIdentity;
    }

    /**
     * One request through the simulated fleet. Parameters arrive as raw
     * strings (the controller passes them through untouched): instance
     * "a"/"b" pins the persona like a sticky session would, omitted means
     * round-robin; store "local"/"external" picks where the durable value
     * lives, external by default.
     */
    public synchronized Result<Probe> hit(String instanceParam, String storeParam) {
        String stateHome = normalizeChoice(storeParam, EXTERNAL);
        if (stateHome == null) {
            return new Failed<>(Failure.of(FailureKind.VALIDATION,
                    "store must be one of local, external (external — the shared store — is the default)",
                    Map.of("parameter", "store", "allowed", List.of("local", "external"))));
        }
        boolean sticky = instanceParam != null && !instanceParam.isBlank();
        if (sticky && !"a".equals(instanceParam.trim().toLowerCase(Locale.ROOT))
                && !"b".equals(instanceParam.trim().toLowerCase(Locale.ROOT))) {
            return new Failed<>(Failure.of(FailureKind.VALIDATION,
                    "instance must be one of a, b (or omitted — the router round-robins)",
                    Map.of("parameter", "instance", "allowed", List.of("a", "b"))));
        }
        InstancePersona served = sticky ? personaByShortId(instanceParam.trim().toLowerCase(Locale.ROOT))
                : roundRobinPick();
        long servingLocal = served.incrementLocal();
        long reported = LOCAL.equals(stateHome) ? servingLocal : externalStore.increment();
        return Result.success(build(served, sticky, stateHome, reported));
    }

    /** Zeroes both personas' memories, the shared store, and the routing cursor. */
    public synchronized Probe reset() {
        instances.forEach(InstancePersona::reset);
        externalStore.reset();
        routingCursor = 0;
        return new Probe(processIdentity.instanceId(), processIdentity.startedAt(), 0, null,
                "round-robin", EXTERNAL,
                instances.stream().map(persona -> new InstanceView(persona.id(), 0L)).toList(),
                0, 0, 0,
                List.of("all counters reset — both personas' private memories, the shared store, and the routing cursor.",
                        "the next round-robin request lands on " + instances.getFirst().id() + "."));
    }

    private InstancePersona roundRobinPick() {
        InstancePersona picked = instances.get((int) (routingCursor % instances.size()));
        routingCursor++;
        return picked;
    }

    private InstancePersona personaByShortId(String shortId) {
        return instances.stream()
                .filter(persona -> persona.id().equals("server-" + shortId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("persona server-" + shortId + " missing"));
    }

    private Probe build(InstancePersona served, boolean sticky, String stateHome, long reported) {
        long total = instances.stream().mapToLong(InstancePersona::localState).sum();
        long servingLocal = served.localState();
        long lostIfLocal = total - servingLocal;
        List<InstanceView> views = instances.stream()
                .map(persona -> new InstanceView(persona.id(), persona.localState()))
                .toList();
        InstancePersona other = instances.stream().filter(persona -> persona != served).findFirst().orElse(served);
        List<String> reading = new ArrayList<>();
        reading.add("request #" + total + " routed to " + served.id() + " (" + (sticky ? "sticky pick" : "round-robin")
                + ") — " + served.id() + "'s own memory holds " + servingLocal + " request(s), "
                + other.id() + "'s holds " + other.localState() + ".");
        if (EXTERNAL.equals(stateHome)) {
            reading.add("the value that matters lives OUTSIDE both replicas: the shared store reports " + reported
                    + " — any instance, any routing order, the same monotonic answer.");
        } else {
            reading.add("the value lives INSIDE " + served.id() + " only: this answer says " + reported + ", but "
                    + total + " request(s) have been made — routing just forgot " + lostIfLocal
                    + " of your history.");
        }
        reading.add("server-local memories have diverged (" + views.getFirst().localState() + " vs "
                + views.get(1).localState() + ") — harmless, because they are ephemeral; "
                + "statelessness never claimed instances share a heap.");
        reading.add("both replicas are simulated inside this one JVM (process " + processIdentity.instanceId()
                + ", up since " + processIdentity.startedAt() + "); in production each is a pod and the shared "
                + "store is redis or PostgreSQL over the network.");
        return new Probe(processIdentity.instanceId(), processIdentity.startedAt(), total, served.id(),
                sticky ? "sticky" : "round-robin", stateHome, views, externalStore.value(), reported, lostIfLocal,
                List.copyOf(reading));
    }

    /** null-safe choice normalizer: absent/blank → the default; unknown → null (the caller's 400). */
    private static String normalizeChoice(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return LOCAL.equals(value) || EXTERNAL.equals(value) ? value : null;
    }
}
