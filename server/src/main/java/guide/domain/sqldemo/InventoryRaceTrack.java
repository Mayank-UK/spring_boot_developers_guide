package guide.domain.sqldemo;

/**
 * The oversell race PORT (topic 17, demo db:race): reset the inventory row,
 * fire N genuinely concurrent reservation attempts at it under one strategy,
 * and report the scoreboard. Concurrency is REAL threads over REAL pooled
 * connections — the adapter owns the executor and the rendezvous because
 * thread choreography is machinery, not domain logic.
 */
public interface InventoryRaceTrack {

    /** Runs the race; the outcome's numbers are the lesson. */
    RaceOutcome run(RaceStrategy strategy, int concurrency, int initialStock);
}
