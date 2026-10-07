package guide.domain.sqldemo;

/**
 * One race run's scoreboard (topic 17, demo db:race). The numbers that matter:
 *
 * - attempted / succeeded / declined — every thread's verdict, counted.
 * - oversold — THE verdict: more reservations claim stock than existed
 *   (finalReserved &gt; initialStock). Stock alone cannot show this after a
 *   lost update — that is exactly why reserved exists.
 * - finalStock / finalReserved — the shelf and the history after the dust.
 * - story — the one-sentence autopsy the demo renders under the numbers.
 */
public record RaceOutcome(String strategy, int concurrency, int initialStock, int attempted,
        int succeeded, int declined, boolean oversold, int finalStock, int finalReserved, String story) {

    /** Defensive normalization: a run cannot report more successes than attempts. */
    public RaceOutcome {
        if (succeeded + declined > attempted) {
            throw new IllegalArgumentException("outcome counts exceed attempts");
        }
    }
}
