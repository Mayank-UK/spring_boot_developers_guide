package guide.domain.aop;

import guide.core.aop.InvocationRecord;
import java.util.List;

/**
 * The aop:self-invocation demo's response: the trap, proven from the log.
 * The outer method's record is present, the inner method's is absent — even
 * though the inner demonstrably RAN, because its result is inside the order
 * summary. Derivation lives here (pure, testable): presence of each method
 * is computed from whatever records the caller hands over, so the same logic
 * that reports the trap with a proxied workflow also reports BOTH recorded
 * when the workflow is invoked method-by-method from outside — the control
 * case that shows the inner method is not invisible, only UNREACHED through
 * the proxy.
 */
public record SelfInvocationExhibit(
        String orderSummary,
        String outerMethod,
        String innerMethod,
        boolean outerRecorded,
        boolean innerRecorded,
        List<InvocationRecord> exhibitLog) {

    public SelfInvocationExhibit {
        exhibitLog = exhibitLog == null ? List.of() : List.copyOf(exhibitLog);
    }

    /** Computes both presence flags from the log records of one demo run. */
    public static SelfInvocationExhibit of(String orderSummary, List<InvocationRecord> records) {
        List<InvocationRecord> safe = records == null ? List.of() : List.copyOf(records);
        boolean outer = safe.stream().anyMatch(record ->
                record.method() != null && record.method().endsWith(OUTER_SUFFIX));
        boolean inner = safe.stream().anyMatch(record ->
                record.method() != null && record.method().endsWith(INNER_SUFFIX));
        return new SelfInvocationExhibit(orderSummary, OUTER_SUFFIX, INNER_SUFFIX,
                outer, inner, safe);
    }

    static final String OUTER_SUFFIX = "placeOrder";
    static final String INNER_SUFFIX = "reserveStock";
}
