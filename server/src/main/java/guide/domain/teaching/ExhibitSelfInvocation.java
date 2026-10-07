package guide.domain.teaching;

/**
 * Teaching exhibit for topic 06 (aop), the self-invocation trap: the
 * interface HALF of a two-method workflow whose outer step delegates to its
 * inner step. The guide's own server code has no real counterpart yet — no
 * use case here calls a second @Traceable method on itself — so the exhibit
 * lives in domain/teaching/ under the documented exemption: it is real,
 * compiled, production-shaped code that the running demo and its tests
 * exercise; when a real self-delegating traced workflow lands in the app,
 * that code replaces this exhibit as the quoted source.
 *
 * The lesson needs the INTERFACE to exist: reserveStock is a public contract
 * method that WOULD be traced if invoked through the bean's proxy — which is
 * exactly what makes its absence from the invocation log a proof rather than
 * an accident. Impl: {@link TracedOrderWorkflow}.
 */
public interface ExhibitSelfInvocation {

    /**
     * The outer step — what callers invoke. Internally delegates to
     * {@link #reserveStock(String)}, which is where the trap lives: that
     * delegation goes through {@code this} (the raw target), not the proxy.
     */
    String placeOrder(String item);

    /**
     * The inner step — public, proxyable, and nonetheless invisible to any
     * aspect when reached from {@link #placeOrder(String)}.
     */
    String reserveStock(String item);
}
