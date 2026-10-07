package guide.core.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class whose public method invocations should be recorded by the
 * {@link InvocationTraceAspect} — topic 06's (aop) answer to the cross-cutting
 * instrumentation question: instead of pasting timing/logging/try-finally
 * ceremony into every use case, a class opts in with ONE annotation and the
 * aspect adds the ceremony around every external call into it.
 *
 * Deliberately annotation-driven rather than a package-wide pointcut: an
 * explicit marker on the class reads as an intentional contract ("calls into
 * this type are part of the operational record"), survives renames and
 * package moves, and never sweeps up a class the author did not mean to
 * instrument. Retention is RUNTIME because proxy matching happens at runtime,
 * not at compile time; target is TYPE because the contract is per-class.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Traceable {
}
