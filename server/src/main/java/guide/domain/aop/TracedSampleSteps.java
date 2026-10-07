package guide.domain.aop;

import guide.core.aop.Traceable;
import java.util.Locale;

/**
 * Topic 06's instrumented sample: two small steps — one that succeeds, one
 * that throws — both on one {@link Traceable} class so the aop:trace demo
 * can show BOTH outcomes the InvocationTraceAspect records (ok and
 * exception:…) from real calls. The steps are deliberately trivial: the
 * lesson is the record around them, not the work inside them. The failing
 * step throws IllegalStateException because that is what the demo's use case
 * catches and displays; the aspect's contract (rethrow untouched, record the
 * outcome in finally) is exactly what the exhibit proves.
 */
@Traceable
public class TracedSampleSteps {

    /** A step that completes normally — recorded with outcome "ok". */
    public String succeedingStep(String seed) {
        return "processed[" + seed.toLowerCase(Locale.ROOT) + "]";
    }

    /** A step that always fails — recorded with outcome "exception:IllegalStateException". */
    public String failingStep() {
        throw new IllegalStateException("deliberate failure to demonstrate outcome=exception");
    }
}
