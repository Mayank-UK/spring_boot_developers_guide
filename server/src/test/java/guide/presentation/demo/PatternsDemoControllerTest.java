package guide.presentation.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import guide.config.PatternsDemoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc over topic 44's demo surfaces with the topic's own wiring —
 * deterministic facts only: sequential saga/event ids, no clock, every
 * expectation exact. Pinned: the happy-path saga completes all three steps
 * and moves the shared world; a dialed failure at step 2 compensates
 * exactly the completed steps (release-stock, and nothing else) with the
 * failed step marked failed, the rest skipped, and the world restored to
 * constructed state; a failure at the LAST step unwinds all three
 * compensations in REVERSE completion order; the world can refuse on its
 * own (semantic stock exhaustion, no dial); the trail reads back unchanged
 * and unknown ids are the 404 envelope. The event store: appends fold
 * deterministically (positions, ids, totals), removal is validated against
 * the stream's own history, checkout seals the stream (a later append is
 * the 409 CONFLICT envelope), the raw stream and the replayed projection
 * answer consistently, and bad input is always the typed 400 envelope —
 * never a 500. @BeforeEach resets both engines: they are stateful and the
 * slice context is shared.
 */
@WebMvcTest(PatternsDemoController.class)
@Import(PatternsDemoConfig.class)
class PatternsDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetEngines() throws Exception {
        mockMvc.perform(post("/api/demo/arch/saga/reset")).andExpect(status().isOk());
        mockMvc.perform(post("/api/demo/arch/eventstore/reset")).andExpect(status().isOk());
    }

    // ---- the saga ------------------------------------------------------------

    @Test
    void theHappyPathCompletesAllThreeStepsAndMovesTheWorld() throws Exception {
        mockMvc.perform(post("/api/demo/arch/saga/start")
                        .queryParam("items", "2").queryParam("amountCents", "1999").queryParam("failAtStep", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sagaId").value("saga-1"))
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[0].name").value("reserve-stock"))
                .andExpect(jsonPath("$.steps[0].outcome").value("completed"))
                .andExpect(jsonPath("$.steps[1].name").value("charge-payment"))
                .andExpect(jsonPath("$.steps[1].outcome").value("completed"))
                .andExpect(jsonPath("$.steps[2].name").value("ship-order"))
                .andExpect(jsonPath("$.steps[2].outcome").value("completed"))
                .andExpect(jsonPath("$.compensations.length()").value(0))
                .andExpect(jsonPath("$.world.stockOnHand").value(4))
                .andExpect(jsonPath("$.world.balanceCents").value(4001))
                .andExpect(jsonPath("$.world.shipments.length()").value(1))
                .andExpect(jsonPath("$.world.shipments[0]").value("saga-1"));
    }

    @Test
    void aDialedFailureAtStepTwoCompensatesExactlyTheCompletedSteps() throws Exception {
        mockMvc.perform(post("/api/demo/arch/saga/start")
                        .queryParam("items", "3").queryParam("amountCents", "2500").queryParam("failAtStep", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sagaId").value("saga-1"))
                .andExpect(jsonPath("$.state").value("compensated"))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[0].outcome").value("completed"))
                .andExpect(jsonPath("$.steps[1].name").value("charge-payment"))
                .andExpect(jsonPath("$.steps[1].outcome").value("failed"))
                .andExpect(jsonPath("$.steps[2].name").value("ship-order"))
                .andExpect(jsonPath("$.steps[2].outcome").value("skipped"))
                .andExpect(jsonPath("$.compensations.length()").value(1))
                .andExpect(jsonPath("$.compensations[0].name").value("release-stock"))
                .andExpect(jsonPath("$.compensations[0].outcome").value("compensated"))
                // the world is exactly its constructed state — no partial state survives
                .andExpect(jsonPath("$.world.stockOnHand").value(6))
                .andExpect(jsonPath("$.world.balanceCents").value(6000))
                .andExpect(jsonPath("$.world.shipments.length()").value(0));
    }

    @Test
    void aFailureAtTheLastStepUnwindsTheCompletedStepsInReverseOrder() throws Exception {
        mockMvc.perform(post("/api/demo/arch/saga/start").queryParam("failAtStep", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("compensated"))
                .andExpect(jsonPath("$.steps[2].name").value("ship-order"))
                .andExpect(jsonPath("$.steps[2].outcome").value("failed"))
                // ship failed BEFORE its effect existed, so only the two completed
                // steps unwind — in REVERSE completion order (charge was done last)
                .andExpect(jsonPath("$.compensations.length()").value(2))
                .andExpect(jsonPath("$.compensations[0].name").value("refund-payment"))
                .andExpect(jsonPath("$.compensations[1].name").value("release-stock"))
                .andExpect(jsonPath("$.world.stockOnHand").value(6))
                .andExpect(jsonPath("$.world.balanceCents").value(6000))
                .andExpect(jsonPath("$.world.shipments.length()").value(0))
                .andExpect(jsonPath("$.reading[1]").value(
                        org.hamcrest.Matchers.containsString("REVERSE")));
    }

    @Test
    void theWorldCanRefuseOnItsOwn_semanticFailureWithoutADial() throws Exception {
        // stock 6: items=5 completes once (stock drops to 1); the second
        // identical saga needs 5 of a stock of 1 — the WORLD refuses it
        mockMvc.perform(post("/api/demo/arch/saga/start")
                        .queryParam("items", "5").queryParam("amountCents", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.world.stockOnHand").value(1));

        mockMvc.perform(post("/api/demo/arch/saga/start")
                        .queryParam("items", "5").queryParam("amountCents", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sagaId").value("saga-2"))
                .andExpect(jsonPath("$.state").value("compensated"))
                .andExpect(jsonPath("$.steps[0].name").value("reserve-stock"))
                .andExpect(jsonPath("$.steps[0].outcome").value("failed"))
                .andExpect(jsonPath("$.steps[0].detail").value(
                        org.hamcrest.Matchers.containsString("stock of 1")))
                .andExpect(jsonPath("$.compensations.length()").value(0))
                .andExpect(jsonPath("$.world.stockOnHand").value(1));
    }

    @Test
    void theTrailReadsBackUnchangedAndUnknownIdsAreThe404Envelope() throws Exception {
        mockMvc.perform(post("/api/demo/arch/saga/start").queryParam("failAtStep", "0"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/demo/arch/saga/saga-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sagaId").value("saga-1"))
                .andExpect(jsonPath("$.state").value("completed"));

        mockMvc.perform(get("/api/demo/arch/saga/saga-99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        mockMvc.perform(get("/api/demo/arch/saga/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sagas.length()").value(1))
                .andExpect(jsonPath("$.sagas[0].sagaId").value("saga-1"))
                .andExpect(jsonPath("$.world.stockOnHand").value(4));
    }

    @Test
    void sagaInputOutsideTheBoundsLeavesAsTheValidationEnvelope() throws Exception {
        mockMvc.perform(post("/api/demo/arch/saga/start").queryParam("failAtStep", "4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value("failAtStep must be between 0 and 3"));

        mockMvc.perform(post("/api/demo/arch/saga/start").queryParam("items", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    // ---- the event store -------------------------------------------------------

    @Test
    void appendsFoldDeterministically_itemsTotalAndVersionPinned() throws Exception {
        mockMvc.perform(post("/api/demo/arch/eventstore/append")
                        .queryParam("stream", "cart-1").queryParam("type", "cart.item-added")
                        .queryParam("item", "mug").queryParam("priceCents", "1200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.position").value(1))
                .andExpect(jsonPath("$.event.eventId").value("evt-1"))
                .andExpect(jsonPath("$.event.streamId").value("cart-1"))
                .andExpect(jsonPath("$.after.version").value(1))
                .andExpect(jsonPath("$.after.items[0]").value("mug"))
                .andExpect(jsonPath("$.after.totalCents").value(1200))
                .andExpect(jsonPath("$.after.status").value("open"));

        mockMvc.perform(post("/api/demo/arch/eventstore/append")
                        .queryParam("stream", "cart-1").queryParam("type", "cart.item-added")
                        .queryParam("item", "book").queryParam("priceCents", "2500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.position").value(2))
                .andExpect(jsonPath("$.after.totalCents").value(3700));

        mockMvc.perform(post("/api/demo/arch/eventstore/append")
                        .queryParam("stream", "cart-1").queryParam("type", "cart.item-removed")
                        .queryParam("item", "mug"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.after.version").value(3))
                .andExpect(jsonPath("$.after.items.length()").value(1))
                .andExpect(jsonPath("$.after.items[0]").value("book"))
                .andExpect(jsonPath("$.after.totalCents").value(2500));
    }

    @Test
    void checkoutSealsTheStream_LaterAppendsAreTheConflictEnvelope() throws Exception {
        append("cart-1", "cart.item-added", "mug", 1200L);
        append("cart-1", "cart.item-added", "book", 2500L);

        mockMvc.perform(post("/api/demo/arch/eventstore/append")
                        .queryParam("stream", "cart-1").queryParam("type", "cart.checked-out"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.after.status").value("checked-out"))
                .andExpect(jsonPath("$.after.sealed").value(true))
                .andExpect(jsonPath("$.after.replayLog.length()").value(3));

        mockMvc.perform(post("/api/demo/arch/eventstore/append")
                        .queryParam("stream", "cart-1").queryParam("type", "cart.item-added")
                        .queryParam("item", "late-mug").queryParam("priceCents", "500"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));
    }

    @Test
    void factsAreValidatedAgainstTheStreamsOwnHistory() throws Exception {
        // removing an item the history doesn't contain is refused
        mockMvc.perform(post("/api/demo/arch/eventstore/append")
                        .queryParam("stream", "cart-2").queryParam("type", "cart.item-removed")
                        .queryParam("item", "ghost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("ghost")));

        // checkout of an empty cart is not a fact the store can own
        mockMvc.perform(post("/api/demo/arch/eventstore/append")
                        .queryParam("stream", "cart-2").queryParam("type", "cart.checked-out"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));

        // unknown vocabulary is refused at the edge
        mockMvc.perform(post("/api/demo/arch/eventstore/append")
                        .queryParam("stream", "cart-2").queryParam("type", "cart.exploded"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));

        // item-added without its price is a validation failure, never an NPE
        mockMvc.perform(post("/api/demo/arch/eventstore/append")
                        .queryParam("stream", "cart-2").queryParam("type", "cart.item-added")
                        .queryParam("item", "mug"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.message").value("priceCents is required for this event type"));
    }

    @Test
    void theRawStreamAndTheReplayedProjectionAnswerConsistently() throws Exception {
        append("cart-1", "cart.item-added", "mug", 1200L);
        append("cart-1", "cart.item-added", "book", 2500L);
        append("cart-1", "cart.item-removed", "mug", null);

        mockMvc.perform(get("/api/demo/arch/eventstore/stream/cart-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(3))
                .andExpect(jsonPath("$.events[0].position").value(1))
                .andExpect(jsonPath("$.events[0].type").value("cart.item-added"))
                .andExpect(jsonPath("$.events[0].payload.item").value("mug"))
                .andExpect(jsonPath("$.events[1].eventId").value("evt-2"))
                .andExpect(jsonPath("$.events[2].type").value("cart.item-removed"));

        mockMvc.perform(get("/api/demo/arch/eventstore/state/cart-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.items[0]").value("book"))
                .andExpect(jsonPath("$.totalCents").value(2500))
                .andExpect(jsonPath("$.replayLog[0]").value(
                        org.hamcrest.Matchers.containsString("evt-1 cart.item-added")));

        mockMvc.perform(get("/api/demo/arch/eventstore/state/never-appended"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        mockMvc.perform(get("/api/demo/arch/eventstore/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streams.length()").value(1))
                .andExpect(jsonPath("$.streams[0].streamId").value("cart-1"))
                .andExpect(jsonPath("$.streams[0].events").value(3))
                .andExpect(jsonPath("$.streams[0].sealed").value(false));
    }

    private void append(String stream, String type, String item, Long priceCents) throws Exception {
        var request = post("/api/demo/arch/eventstore/append")
                .queryParam("stream", stream).queryParam("type", type);
        if (item != null) {
            request = request.queryParam("item", item);
        }
        if (priceCents != null) {
            request = request.queryParam("priceCents", String.valueOf(priceCents));
        }
        mockMvc.perform(request).andExpect(status().isOk());
    }
}
