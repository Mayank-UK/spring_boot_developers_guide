package guide.domain.thirdparty;

/**
 * The scenario-control surface every third-party port carries beside its
 * operations. The split is deliberate and teachable:
 *
 * <ul>
 *   <li>{@link #posture()} reports how the wired adapter is answering —
 *       fake (in-process, no provider), sandbox (provider test semantics,
 *       still in-process here), or live (the real thing).</li>
 *   <li>{@link #adoptPosture(Posture)} is the DEMO dial: fake adapters
 *       flip between fake and sandbox so a client can watch the behavior
 *       change; a real adapter REFUSES (returning false) because live is a
 *       key-and-wiring decision made at deploy time, never a runtime
 *       switch someone trips from a demo panel.</li>
 *   <li>{@link #reset()} returns the adapter to its constructed state so
 *       demo tours and tests are deterministic. Real adapters have no
 *       interesting state; theirs is a no-op.</li>
 * </ul>
 */
public sealed interface IntegrationPort permits MailPort, PaymentPort, StoragePort {

    /** The posture the adapter is currently answering in. */
    Posture posture();

    /**
     * Scenario dial. Returns whether the posture changed — fake adapters
     * accept fake/sandbox; real adapters refuse everything (live is not a
     * dial).
     */
    boolean adoptPosture(Posture next);

    /** Back to constructed state (counters, recorded traffic, deferral memory). */
    void reset();
}
