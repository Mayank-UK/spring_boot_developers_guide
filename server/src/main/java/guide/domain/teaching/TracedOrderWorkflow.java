package guide.domain.teaching;

import guide.core.aop.Traceable;

/**
 * The implementation half of the self-invocation exhibit: a two-step
 * "order workflow" whose outer step calls its inner step directly. The class
 * is {@link Traceable}, so EVERY method reached through the Spring proxy
 * gets recorded by the InvocationTraceAspect — and that is precisely what
 * makes the demonstration honest: placeOrder appears in the invocation log,
 * reserveStock does not, even though reserveStock demonstrably RAN (its
 * result is inside the order summary).
 *
 * The delegation line is the whole trap, read it slowly:
 * {@code String reservation = reserveStock(item);} is sugar for
 * {@code this.reserveStock(item)} — and {@code this} inside the target is
 * the RAW object, never the proxy. Interception happens in the proxy, so the
 * internal call skips it. The fix patterns (and their trade-offs) are the
 * topic's subject; this class deliberately keeps the trap so the demo can
 * prove it.
 */
@Traceable
public class TracedOrderWorkflow implements ExhibitSelfInvocation {

    @Override
    public String placeOrder(String item) {
        // this.reserveStock(item) — a plain Java call on the raw target: the
        // proxy is not on this call path, so no advice runs for it.
        String reservation = reserveStock(item);
        return "order[" + item + "] ← " + reservation;
    }

    @Override
    public String reserveStock(String item) {
        return "reserved:" + item;
    }
}
