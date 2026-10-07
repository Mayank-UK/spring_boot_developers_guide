package guide.domain.thirdparty;

/**
 * The payments port — Stripe-shaped by design, provider-agnostic by
 * construction. Charging a card is the highest-stakes integration most
 * teams take: money moves, idempotency is mandatory, and a decline is an
 * ANSWER from the provider rather than your outage. The port encodes that
 * split: a {@link ChargeReceipt} carries succeeded/declined outcomes as
 * VALUES, while transport-level refusal (network, auth, provider down)
 * THROWS the app's typed UNAVAILABLE failure — two very different failure
 * families that must never share an exception.
 *
 * The guide's adapter is an in-process fake whose test vectors are the
 * provider's own DOCUMENTED test-mode shapes (tok_visa, tok_chargeDeclined
 * — names any Stripe-literate reader already knows). No real charge can
 * occur: there is no provider, no key, and no network path at all.
 */
public non-sealed interface PaymentPort extends IntegrationPort {

    /**
     * Charge one token the given amount. Declines come BACK as receipts
     * (outcome=declined + the provider's decline code); only transport
     * trouble throws.
     */
    ChargeReceipt charge(String token, long amountCents, String currency, String description);

    /**
     * The provider's answer. chargeId is loudly fake (ch_demo_N) in every
     * adapter this guide ships; testMode flags that the answer came from a
     * test world, never a live ledger.
     */
    record ChargeReceipt(String outcome, String chargeId, String declineCode,
            boolean testMode, String detail) {
    }
}
