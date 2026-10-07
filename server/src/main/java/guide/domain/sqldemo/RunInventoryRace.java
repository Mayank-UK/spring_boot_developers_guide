package guide.domain.sqldemo;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;

/**
 * Topic 17's race use case (demo db:race): validate the knobs, run the race.
 *
 * The concurrency ceiling is the POOL: every racer holds one connection for
 * the span of its strategy (transactions pin a connection), so more racers
 * than HikariCP's maximum-pool-size (8 in application.yml) would not race —
 * they would QUEUE, some before the start gun even fires. The cap is the
 * demo's quietest and most practical lesson: your concurrency is your pool.
 */
public final class RunInventoryRace {

    /** HikariCP maximum-pool-size from application.yml — the hard ceiling on genuinely-concurrent racers. */
    public static final int MAX_CONCURRENCY = 8;

    private final InventoryRaceTrack track;

    public RunInventoryRace(InventoryRaceTrack track) {
        this.track = track;
    }

    /** Runs one race; concurrency 2..8, initialStock 1..99 — validation failures otherwise. */
    public RaceOutcome run(String strategyWire, Integer concurrency, Integer initialStock) {
        RaceStrategy strategy;
        try {
            strategy = RaceStrategy.fromWire(strategyWire);
        } catch (IllegalArgumentException unknown) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION, unknown.getMessage()));
        }
        int racers = concurrency == null ? 4 : concurrency;
        if (racers < 2 || racers > MAX_CONCURRENCY) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "concurrency must be between 2 and " + MAX_CONCURRENCY
                            + " — every racer holds a pooled connection for the whole race"));
        }
        int stock = initialStock == null ? 1 : initialStock;
        if (stock < 1 || stock > 99) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "initialStock must be between 1 and 99"));
        }
        return track.run(strategy, racers, stock);
    }
}
