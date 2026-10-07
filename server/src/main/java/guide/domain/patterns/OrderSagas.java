package guide.domain.patterns;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE SAGA ENGINE — topic 44's answer to "a business transaction that spans
 * more work than one database commit can cover". An ORDER steps through three
 * forward actions (reserve stock, charge payment, ship), and when a step
 * fails, the orchestrator does the thing a distributed system must do instead
 * of ROLLING BACK: it COMPENSATES — running a business-meaningful undo for
 * every step that already succeeded, in REVERSE completion order, until the
 * world looks as if the saga never started.
 *
 * Everything here is deliberately honest about what a real saga costs:
 * - a compensation is NOT a rollback. release-stock, refund-payment and
 *   recall-shipment are actions the world can OBSERVE — a customer sees the
 *   refund arrive, a warehouse sees the stock return. The reading lines say
 *   this out loud because it is the pattern's defining trade.
 * - failure arrives two ways: the DIAL (failAtStep 1..3 — deterministic
 *   injected failure, the demo's and the tests' knob) and the WORLD refusing
 *   on its own (not enough stock, not enough balance) — a semantic failure no
 *   dial caused, which is the failure mode production actually serves you.
 * - the trail is the saga's audit log: every forward step with its outcome,
 *   every compensation in the order it ran, and the world's final state.
 *
 * Deterministic by construction: ids are sequential (saga-1, saga-2, …),
 * there is no clock, no thread beyond the caller, no randomness — reset()
 * returns the engine to constructed state so the curl tour's comments and
 * the MockMvc tests' expectations hold for anyone who runs them.
 *
 * Pure domain (the dependency rule holds here as everywhere): JDK and
 * guide.core only — a saga engine is ORCHESTRATION, not infrastructure, and
 * the real world's queues and brokers plug in behind exactly this shape.
 */
public final class OrderSagas {

    /** Edge-validation bounds; the controller validates, the domain re-checks. */
    public static final int MIN_ITEMS = 1;
    public static final int MAX_ITEMS = 5;
    public static final long MIN_AMOUNT_CENTS = 1;
    public static final long MAX_AMOUNT_CENTS = 100_000;
    public static final int MAX_FAIL_AT_STEP = 3;

    /** The world's constructed state — reset() restores exactly this. */
    static final int STARTING_STOCK = 6;
    static final long STARTING_BALANCE_CENTS = 6_000;

    /** One forward step or one compensation on the trail: what ran, and what it did. */
    public record StepEntry(String name, String outcome, String detail) {
    }

    /** The shared world the sagas mutate — the observable state compensations restore. */
    public record WorldView(int stockOnHand, long balanceCents, List<String> shipments) {
    }

    /** A finished saga: parameters, final state, the full trail, the world it left behind. */
    public record SagaReport(String sagaId, String state, int items, long amountCents, int failAtStep,
            List<StepEntry> steps, List<StepEntry> compensations, WorldView world, List<String> reading) {
    }

    private final Map<String, SagaReport> sagas = new LinkedHashMap<>();
    private final World world = new World();
    private int nextSagaNumber = 1;

    /**
     * Runs one order saga to completion — forward until a step fails, then
     * compensating every completed step in reverse. The answer is the whole
     * trail; the saga is stored under its sequential id for read-back.
     */
    public synchronized SagaReport start(int items, long amountCents, int failAtStep) {
        require(MIN_ITEMS <= items && items <= MAX_ITEMS,
                "items must be between " + MIN_ITEMS + " and " + MAX_ITEMS);
        require(MIN_AMOUNT_CENTS <= amountCents && amountCents <= MAX_AMOUNT_CENTS,
                "amountCents must be between " + MIN_AMOUNT_CENTS + " and " + MAX_AMOUNT_CENTS);
        require(0 <= failAtStep && failAtStep <= MAX_FAIL_AT_STEP,
                "failAtStep must be between 0 (never) and " + MAX_FAIL_AT_STEP);

        String sagaId = "saga-" + (nextSagaNumber++);
        List<StepEntry> steps = new ArrayList<>();
        List<StepEntry> compensations = new ArrayList<>();
        List<String> completed = new ArrayList<>();

        // ---- step 1: reserve stock (compensable — release) ----
        if (failAtStep == 1 || items > world.stockOnHand) {
            steps.add(new StepEntry("reserve-stock", "failed",
                    failAtStep == 1
                            ? "injected failure at step 1 — the dial, not the world"
                            : "needs " + items + " of a stock of " + world.stockOnHand));
            return finishByCompensating(sagaId, items, amountCents, failAtStep, steps, compensations, completed);
        }
        world.stockOnHand -= items;
        steps.add(new StepEntry("reserve-stock", "completed",
                "stock " + (world.stockOnHand + items) + " → " + world.stockOnHand));
        completed.add("reserve-stock");

        // ---- step 2: charge payment (compensable — refund) ----
        if (failAtStep == 2 || amountCents > world.balanceCents) {
            steps.add(new StepEntry("charge-payment", "failed",
                    failAtStep == 2
                            ? "injected failure at step 2 — the dial, not the world"
                            : "needs " + amountCents + "¢ of a balance of " + world.balanceCents + "¢"));
            return finishByCompensating(sagaId, items, amountCents, failAtStep, steps, compensations, completed);
        }
        world.balanceCents -= amountCents;
        steps.add(new StepEntry("charge-payment", "completed",
                "balance " + (world.balanceCents + amountCents) + "¢ → " + world.balanceCents + "¢"));
        completed.add("charge-payment");

        // ---- step 3: ship (compensable — recall) ----
        if (failAtStep == 3) {
            steps.add(new StepEntry("ship-order", "failed", "injected failure at step 3 — the dial, not the world"));
            return finishByCompensating(sagaId, items, amountCents, failAtStep, steps, compensations, completed);
        }
        world.shipments.add(sagaId);
        steps.add(new StepEntry("ship-order", "completed", "shipment " + sagaId + " dispatched"));
        completed.add("ship-order");

        SagaReport report = new SagaReport(sagaId, "completed", items, amountCents, failAtStep,
                List.copyOf(steps), List.copyOf(compensations), world.view(), List.of(
                        sagaId + ": all three steps completed in order — the happy path leaves the world "
                                + "changed with nothing to unwind.",
                        "one saga, one world change per step: stock " + world.stockOnHand
                                + ", balance " + world.balanceCents + "¢, shipments " + world.shipments.size()
                                + " — a second saga would see this one's effects."));
        sagas.put(sagaId, report);
        return report;
    }

    /**
     * The orchestrator's other half — the part a database never has: mark the
     * remaining steps skipped, undo the completed ones in REVERSE order, and
     * record the whole thing as a compensated saga.
     */
    private SagaReport finishByCompensating(String sagaId, int items, long amountCents, int failAtStep,
            List<StepEntry> steps, List<StepEntry> compensations, List<String> completed) {
        int failedAt = steps.size();
        // the steps after the failure never executed — say so, in canonical order
        for (String neverRan : List.of("reserve-stock", "charge-payment", "ship-order")) {
            if (steps.stream().noneMatch(step -> step.name().equals(neverRan))) {
                steps.add(new StepEntry(neverRan, "skipped",
                        "never attempted — forward progress stopped at step " + failedAt));
            }
        }

        for (int index = completed.size() - 1; index >= 0; index--) {
            switch (completed.get(index)) {
                case "ship-order" -> {
                    world.shipments.remove(world.shipments.size() - 1);
                    compensations.add(new StepEntry("recall-shipment", "compensated",
                            "shipment recalled before it left the building"));
                }
                case "charge-payment" -> {
                    world.balanceCents += amountCents;
                    compensations.add(new StepEntry("refund-payment", "compensated",
                            "balance restored to " + world.balanceCents + "¢ — a refund the world OBSERVED"));
                }
                case "reserve-stock" -> {
                    world.stockOnHand += items;
                    compensations.add(new StepEntry("release-stock", "compensated",
                            "stock restored to " + world.stockOnHand));
                }
                default -> throw new IllegalStateException("no compensation defined for " + completed.get(index));
            }
        }

        SagaReport report = new SagaReport(sagaId, "compensated", items, amountCents, failAtStep,
                List.copyOf(steps), List.copyOf(compensations), world.view(), List.of(
                        sagaId + ": step " + failedAt + " (" + steps.get(failedAt - 1).name()
                                + ") failed — the orchestrator stops forward progress"
                                + (failAtStep == failedAt ? " (the dial made it fail)" : " (the world refused it)")
                                + " and unwinds what already succeeded.",
                        "compensations run in REVERSE completion order: "
                                + (compensations.isEmpty()
                                        ? "nothing to unwind — the first step failed"
                                        : compensations.stream().map(StepEntry::name).toList()
                                        + " — last completed, first undone."),
                        "world after unwind: stock " + world.stockOnHand + ", balance " + world.balanceCents
                                + "¢, shipments " + world.shipments.size()
                                + " — identical to before the saga started; no partial state survives.",
                        "a compensation is a business action, not a rollback: the refund really happened, "
                                + "and anyone watching the world saw BOTH the charge and the refund."));
        sagas.put(sagaId, report);
        return report;
    }

    /** Reads one saga's trail back — the audit view, served as it was recorded. */
    public synchronized SagaReport trail(String sagaId) {
        SagaReport report = sagas.get(sagaId);
        if (report == null) {
            throw AppException.of(Failure.of(FailureKind.NOT_FOUND,
                    "no saga named '" + sagaId + "' — start one first (ids are saga-1, saga-2, …)", null));
        }
        return report;
    }

    /** The most recent sagas, newest first — the demo's history rail. */
    public synchronized List<SagaReport> recent() {
        List<SagaReport> order = new ArrayList<>(sagas.values());
        java.util.Collections.reverse(order);
        return order.size() > 12 ? List.copyOf(order.subList(0, 12)) : List.copyOf(order);
    }

    /** The world as it stands right now — the state every future saga starts from. */
    public synchronized WorldView world() {
        return world.view();
    }

    /** Back to constructed state: no sagas, no history, fresh world. */
    public synchronized void reset() {
        sagas.clear();
        nextSagaNumber = 1;
        world.reset();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION, message, null));
        }
    }

    /** The tiny shared world: stock, a balance, a shipment list. In-memory on purpose. */
    private static final class World {

        private int stockOnHand = STARTING_STOCK;
        private long balanceCents = STARTING_BALANCE_CENTS;
        private final List<String> shipments = new ArrayList<>();

        WorldView view() {
            return new WorldView(stockOnHand, balanceCents, List.copyOf(shipments));
        }

        void reset() {
            stockOnHand = STARTING_STOCK;
            balanceCents = STARTING_BALANCE_CENTS;
            shipments.clear();
        }
    }
}
