package guide.domain.reliability;

/**
 * The domain's signal that the breaker refused the call at the door. The
 * adapter translates the library's CallNotPermittedException into THIS
 * type, so the caller, the use cases, and the tests speak one vocabulary —
 * and swapping the breaker library changes one catch clause's origin, not
 * its meaning. Rejection is not an error condition to log loudly: it is
 * the breaker doing its job.
 */
public final class BreakerOpenException extends RuntimeException {

    public BreakerOpenException(Throwable cause) {
        super("the breaker is OPEN — the call was rejected at the door", cause);
    }
}
