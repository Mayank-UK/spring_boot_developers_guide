package guide.domain.aop;

import guide.core.aop.InvocationLog;
import guide.core.result.Result;

/**
 * Use case behind GET /api/demo/aop/trace: run one traced step that succeeds
 * and one that fails, then report the InvocationLog's evidence for both —
 * method, args summary, outcome, duration — proving the aspect recorded the
 * real calls and rethrew the real exception.
 *
 * The log is cleared first so the visible tail is exactly this
 * demonstration's own records (an ops tool would never clear; a demo must,
 * or the reader cannot tell this run's evidence from earlier traffic). The
 * expected exception from the failing step is CAUGHT here on purpose — the
 * demo's failure mode is not a failed Result, it is a successfully
 * demonstrated exception outcome.
 */
public class ShowTraceEvidence {

    private static final String SEED = "demo-seed";

    private final TracedSampleSteps steps;
    private final InvocationLog invocationLog;

    public ShowTraceEvidence(TracedSampleSteps steps, InvocationLog invocationLog) {
        this.steps = steps;
        this.invocationLog = invocationLog;
    }

    public Result<TraceEvidenceExhibit> execute() {
        invocationLog.clear();
        String stepResult = steps.succeedingStep(SEED);
        String failureMessage;
        try {
            steps.failingStep();
            failureMessage = "(no failure — the demo's failing step did not throw)";
        } catch (IllegalStateException expected) {
            failureMessage = expected.getMessage();
        }
        return Result.success(TraceEvidenceExhibit.of(stepResult, failureMessage,
                invocationLog.tail(16)));
    }
}
