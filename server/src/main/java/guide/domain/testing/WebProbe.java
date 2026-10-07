package guide.domain.testing;

import java.util.Optional;

/**
 * Port for the web-layer check of the test-runner demo (topic 34): "does
 * this app, over real HTTP, answer a request?" The use case stays free of
 * HTTP machinery; the adapter makes the app call itself on its live port.
 *
 * Absence is a first-class answer, not an error: contexts without a live
 * web server (a MockMvc slice) report {@link Optional#empty()} so the
 * battery can record an honest SKIPPED instead of a fabricated verdict —
 * the same degrade-honestly rule every optional-service adapter follows.
 */
public interface WebProbe {

    /** HTTP status of a self-call to the scenario endpoint; empty when this context has no live server. */
    Optional<Integer> selfCallScenarioStatus();
}
