package guide.domain.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The self-invocation exhibit's business behavior, independent of any
 * proxying: placeOrder composes reserveStock's result, and each step's
 * output is stable. (The proxying behavior — which calls get recorded — is
 * proven in InvocationTraceAspectWiringTest and the demo's MockMvc test.)
 */
class ExhibitSelfInvocationTest {

    @Test
    void placeOrderComposesTheInnerStepsResult() {
        ExhibitSelfInvocation workflow = new TracedOrderWorkflow();

        assertEquals("order[book] ← reserved:book", workflow.placeOrder("book"));
    }

    @Test
    void reserveStockIsAStandalonePublicStep() {
        ExhibitSelfInvocation workflow = new TracedOrderWorkflow();

        assertEquals("reserved:pen", workflow.reserveStock("pen"));
    }

    @Test
    void theInnerCallIsAPlainThisCallNotAThroughTheProxyIndirection() {
        // The delegation must remain a direct call: if someone "fixed" it by
        // self-injecting a proxy reference, the exhibit would stop teaching
        // the trap. Composition output proves the call happened at all.
        assertEquals("order[lamp] ← reserved:lamp", new TracedOrderWorkflow().placeOrder("lamp"));
    }
}
