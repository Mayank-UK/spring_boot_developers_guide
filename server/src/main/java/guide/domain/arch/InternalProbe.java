package guide.domain.arch;

/**
 * Port: fire one REAL internal HTTP request against this very server and
 * report what came back — status, the inner request's correlation id (from
 * the response header RequestIdFilter echoes), the body's size, and the
 * wall time the round trip took. The tracer's whole story is anchored on
 * this being a genuine hop through the servlet stack, not a method call:
 * the implementation lives in data/ and knows about RestClient; the domain
 * only knows the contract.
 */
public interface InternalProbe {

    /** One completed internal call; never throws — failure is a runtime
     * exception the traced use case above this port translates. */
    InternalCall get(String path);

    /**
     * The measured outcome of one internal call. {@code elapsedNanos} is the
     * full round trip measured at the adapter — HTTP + servlet + handler +
     * serialization — which is why the traced use-case duration around it is
     * always slightly larger.
     */
    record InternalCall(int status, String requestId, long bodyBytes, long elapsedNanos) {
    }
}
