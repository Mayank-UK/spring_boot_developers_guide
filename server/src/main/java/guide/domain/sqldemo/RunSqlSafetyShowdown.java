package guide.domain.sqldemo;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.sqldemo.SqlSafetySandbox.LookupOutcome;

/**
 * Topic 17's injection showdown (demo db:sql-safety): one hostile payload,
 * two query paths, one verdict. The use case's whole job is the COMPARISON —
 * it never sanitizes, never repairs, never excuses the payload; it runs the
 * exact bytes through both doors and hands back what each did.
 *
 * Default payload is the classic authentication bypass — {@code ' OR '1'='1}
 * — because it needs no creativity to be devastating: appended after a
 * username it turns {@code WHERE username = '<input>'} into
 * {@code WHERE username = '' OR '1'='1'} — always true, every row returned,
 * the fake 'secret' column included. A custom payload lets the reader try
 * UNION escapes, comment truncation, or their own spelling of malice; the
 * vulnerable path's restricted role answers those with permission denied
 * instead of data (containment, the part sanitization can never promise).
 */
public final class RunSqlSafetyShowdown {

    /** The textbook authentication-bypass payload — taught since the first SQL book. */
    public static final String CLASSIC_PAYLOAD = "' OR '1'='1";

    /** Payloads are capped for honesty, not safety: 200 chars is room for any lesson. */
    private static final int MAX_PAYLOAD = 200;

    private final SqlSafetySandbox sandbox;

    public RunSqlSafetyShowdown(SqlSafetySandbox sandbox) {
        this.sandbox = sandbox;
    }

    /** Both doors' outcomes plus the verdict; a blank payload runs the classic attack. */
    public Showdown run(String payload) {
        String attack = payload == null || payload.isBlank() ? CLASSIC_PAYLOAD : payload;
        if (attack.length() > MAX_PAYLOAD) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "payload must be at most " + MAX_PAYLOAD + " characters (got " + attack.length() + ")"));
        }
        LookupOutcome parameterized = sandbox.lookupParameterized(attack);
        LookupOutcome vulnerable = sandbox.lookupVulnerable(attack);
        return new Showdown(attack, parameterized, vulnerable, verdict(parameterized, vulnerable));
    }

    /** The one-sentence moral, computed from what actually happened. */
    private static String verdict(LookupOutcome parameterized, LookupOutcome vulnerable) {
        if (vulnerable.rowCount() > parameterized.rowCount()) {
            return "the SAME payload returned " + vulnerable.rowCount() + " row(s) via concatenation and "
                    + parameterized.rowCount() + " via bind parameter: concatenation let input become SQL "
                    + "structure — a bind parameter can only ever be a value";
        }
        if (vulnerable.containmentError() != null) {
            return "the attack left the SELECT's shape but the restricted role stopped it at the table "
                    + "boundary — least privilege capped the blast radius the bug already bought";
        }
        return "no structural difference this time — the payload behaved as a value in both paths "
                + "(not every input is an attack; every input still deserves a bind parameter)";
    }

    /** The two outcomes, the payload, and the verdict — the demo's whole response. */
    public record Showdown(String payload, LookupOutcome parameterized, LookupOutcome vulnerable,
            String verdict) {
    }
}
