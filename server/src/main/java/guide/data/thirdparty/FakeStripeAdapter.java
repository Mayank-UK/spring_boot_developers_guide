package guide.data.thirdparty;

import guide.domain.thirdparty.PaymentPort;
import guide.domain.thirdparty.Posture;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adapter: the in-process payments provider — Stripe-shaped semantics
 * with ZERO provider. The test vectors are the provider's own documented
 * TEST-MODE names, which is the whole point of a sandbox: tok_visa and
 * tok_chargeDeclined behave exactly as their docs promise, so the code
 * paths a checkout exercises in CI are the same ones production runs —
 * only the world behind the API is fake.
 *
 * The decline taxonomy is DATA, not exceptions: card_declined and
 * insufficient_funds come back as receipts, because a decline is the
 * provider ANSWERING the question you asked (can this card be charged?
 * no — here is why). Only transport-level trouble (provider unreachable,
 * auth broken) would be an outage, and those throw in the REAL adapter,
 * not here — a fake that models outages deterministically belongs to the
 * reliability topic's failure injection.
 */
public final class FakeStripeAdapter implements PaymentPort {

    /** The documented test vectors the adapter understands. */
    public static final String TOKEN_SUCCEEDS = "tok_visa";
    public static final String TOKEN_DECLINED = "tok_chargeDeclined";
    public static final String TOKEN_INSUFFICIENT = "tok_insufficientFunds";

    private final AtomicLong sequence = new AtomicLong();
    private volatile Posture posture = Posture.FAKE;

    @Override
    public ChargeReceipt charge(String token, long amountCents, String currency,
            String description) {
        Objects.requireNonNull(token, "token");
        String chargeId = "ch_demo_" + sequence.incrementAndGet();
        boolean testMode = posture == Posture.SANDBOX;
        String world = testMode
                ? "stripe test mode — these ARE the documented test vectors"
                : "in-process fake — no provider, no ledger, no money";
        return switch (token) {
            case TOKEN_SUCCEEDS -> new ChargeReceipt("succeeded", chargeId, null, testMode,
                    "charged " + amountCents + " " + currency + " (" + description + ") — " + world);
            case TOKEN_DECLINED -> new ChargeReceipt("declined", chargeId, "card_declined",
                    testMode, "the provider declined the card — " + world);
            case TOKEN_INSUFFICIENT -> new ChargeReceipt("declined", chargeId,
                    "insufficient_funds", testMode,
                    "the provider declined for funds — " + world);
            default -> new ChargeReceipt("declined", chargeId, "invalid_token", testMode,
                    "unknown token '" + token + "' — a real provider rejects unknown tokens "
                            + "the same way; try " + TOKEN_SUCCEEDS + ", " + TOKEN_DECLINED
                            + " or " + TOKEN_INSUFFICIENT);
        };
    }

    @Override
    public Posture posture() {
        return posture;
    }

    @Override
    public boolean adoptPosture(Posture next) {
        if (next == Posture.FAKE || next == Posture.SANDBOX) {
            posture = next;
            return true;
        }
        return false;
    }

    @Override
    public void reset() {
        sequence.set(0);
        posture = Posture.FAKE;
    }
}
