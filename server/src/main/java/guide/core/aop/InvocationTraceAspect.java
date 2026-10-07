package guide.core.aop;

import guide.core.logging.RequestIdFilter;
import java.util.Collection;
import java.util.StringJoiner;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;

/**
 * The guide's first real aspect (topic 06, aop): around-advice that records
 * every external call into a {@link Traceable} class as an
 * {@link InvocationRecord} in the {@link InvocationLog}, and logs the same
 * facts at debug. This is the whole cross-cutting ceremony — entry/exit
 * logging, timing, exception recording, request correlation — written ONCE,
 * applied everywhere the annotation appears.
 *
 * The production rules it must never break:
 * - {@code proceed()} is called EXACTLY once, on the normal path only
 * - exceptions are rethrown UNTOUCHED (no wrap, no swallow, no logging at a
 *   level that duplicates the error taxonomy's own handler)
 * - the record is written in a finally block, so an outcome exists for every
 *   completed invocation, success or failure
 * - argument rendering is capped and defensive: never a full payload, never
 *   an unbounded toString, and a toString that itself throws must not take
 *   the traced business call down with it
 *
 * Registered as an explicit @Bean in config/AopDemoConfig (composition-root
 * discipline — the aspect is constructed like every other bean, injected
 * with its log), NOT @Component-scanned. It is a plain class plus AspectJ
 * annotations; Spring's AOP autoconfiguration (spring-boot-starter-aop) is
 * what turns @Aspect beans into advisors and proxies.
 */
@Aspect
@Order(0)
public class InvocationTraceAspect {

    /** Outcome recorded when the target returned normally. */
    public static final String OUTCOME_OK = "ok";

    /** Outcome prefix recorded when the target threw; suffix is the simple type name. */
    public static final String OUTCOME_EXCEPTION_PREFIX = "exception:";

    /** Never render more than this many arguments per record. */
    static final int MAX_ARGS = 4;

    /** Never render more than this many characters of one argument. */
    static final int MAX_ARG_LENGTH = 60;

    private static final Logger log = LoggerFactory.getLogger(InvocationTraceAspect.class);

    private final InvocationLog invocationLog;

    public InvocationTraceAspect(InvocationLog invocationLog) {
        this.invocationLog = invocationLog;
    }

    /**
     * What to trace: any method execution on a class marked {@link Traceable}.
     * The {@code !execution(* java.lang.Object.*(..))} arm excludes the
     * methods every Java object inherits — without it a traced bean's
     * toString()/hashCode() would pollute the log whenever the proxy renders
     * itself.
     */
    @Pointcut("execution(* *(..)) && !execution(* java.lang.Object.*(..)) && @within(guide.core.aop.Traceable)")
    public void traceableMethods() {
    }

    /**
     * The advice: time the call, run it exactly once, classify the outcome,
     * and in finally file the record (with the request id the
     * RequestIdFilter already parked in the MDC — null off the HTTP path).
     */
    @Around("traceableMethods()")
    public Object aroundTraceable(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAt = System.nanoTime();
        String method = joinPoint.getSignature().getDeclaringTypeName()
                + "." + joinPoint.getSignature().getName();
        String argsSummary = summarizeArguments(joinPoint.getArgs());
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        String outcome = OUTCOME_OK;
        try {
            return joinPoint.proceed();
        } catch (Throwable failure) {
            outcome = OUTCOME_EXCEPTION_PREFIX + failure.getClass().getSimpleName();
            throw failure;
        } finally {
            long durationNanos = System.nanoTime() - startedAt;
            invocationLog.record(new InvocationRecord(
                    method, argsSummary, outcome, durationNanos, requestId));
            log.debug("invocation method={} args={} outcome={} durationNanos={} requestId={}",
                    method, argsSummary, outcome, durationNanos, requestId);
        }
    }

    /**
     * Renders "(arg, arg, …)" for the intercepted call — capped at
     * {@value #MAX_ARGS} arguments and {@value #MAX_ARG_LENGTH} characters
     * each, with collections and arrays summarized by SIZE instead of
     * content: a trace line must hint at the input, never become a second
     * copy of the payload. A toString() that throws degrades to the class's
     * simple name, because rendering diagnostics may never break the
     * diagnosed call.
     */
    static String summarizeArguments(Object[] args) {
        if (args == null || args.length == 0) {
            return "()";
        }
        StringJoiner summary = new StringJoiner(", ", "(", ")");
        int shown = Math.min(args.length, MAX_ARGS);
        for (int index = 0; index < shown; index++) {
            summary.add(safeArgument(args[index]));
        }
        if (args.length > MAX_ARGS) {
            summary.add(args.length - MAX_ARGS + " more");
        }
        return summary.toString();
    }

    private static String safeArgument(Object argument) {
        if (argument == null) {
            return "null";
        }
        String rendered;
        if (argument instanceof Collection<?> collection) {
            rendered = argument.getClass().getSimpleName() + "[size=" + collection.size() + "]";
        } else if (argument instanceof Object[] array) {
            rendered = argument.getClass().getSimpleName() + "[length=" + array.length + "]";
        } else {
            rendered = safeToString(argument);
        }
        if (rendered.length() > MAX_ARG_LENGTH) {
            return rendered.substring(0, MAX_ARG_LENGTH) + "...";
        }
        return rendered;
    }

    private static String safeToString(Object argument) {
        try {
            return String.valueOf(argument);
        } catch (RuntimeException toStringThrew) {
            return argument.getClass().getSimpleName() + "(toString failed)";
        }
    }
}
