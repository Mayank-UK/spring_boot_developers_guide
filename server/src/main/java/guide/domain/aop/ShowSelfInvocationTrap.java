package guide.domain.aop;

import guide.core.aop.InvocationLog;
import guide.core.aop.InvocationRecord;
import guide.core.result.Result;
import guide.domain.teaching.ExhibitSelfInvocation;
import java.util.List;

/**
 * Use case behind GET /api/demo/aop/self-invocation: invoke the exhibit's
 * OUTER method once (through the injected bean — which, when the aspect
 * machinery is active, IS the proxy), then inspect the InvocationLog for
 * both methods. The honest outcomes:
 * - proxied bean: outer recorded, inner absent — the internal delegation
 *   went through the raw target, so no advice ran for it (the trap)
 * - unproxied bean (plain unit test): neither recorded — no proxy, no
 *   interception at all
 * The report derives from the log, never from assumptions: whatever the
 * machinery did is what the exhibit says it did.
 */
public class ShowSelfInvocationTrap {

    private static final String DEMO_ITEM = "book";

    private final ExhibitSelfInvocation exhibit;
    private final InvocationLog invocationLog;

    public ShowSelfInvocationTrap(ExhibitSelfInvocation exhibit, InvocationLog invocationLog) {
        this.exhibit = exhibit;
        this.invocationLog = invocationLog;
    }

    public Result<SelfInvocationExhibit> execute() {
        invocationLog.clear();
        String orderSummary = exhibit.placeOrder(DEMO_ITEM);
        List<InvocationRecord> records = invocationLog.snapshot();
        return Result.success(SelfInvocationExhibit.of(orderSummary, records));
    }
}
