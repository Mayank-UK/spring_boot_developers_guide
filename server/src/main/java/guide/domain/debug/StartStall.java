package guide.domain.debug;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.List;
import java.util.Locale;

/**
 * Use case: park a stall of the requested kind and answer the first
 * snapshot. Boundary discipline like everywhere else: the kind is validated
 * BEFORE the engine is touched (unknown kinds are typed validation
 * failures), and starting while a stall is already parked is a typed
 * CONFLICT — the engine's own IllegalStateException is the race backstop,
 * not the contract.
 */
public final class StartStall {

    /** The two parked shapes this demo teaches. */
    public static final List<String> KINDS = List.of("lock", "spin");

    private final StallTheater theater;
    private final ReadStallThreads read;

    public StartStall(StallTheater theater, ReadStallThreads read) {
        this.theater = theater;
        this.read = read;
    }

    public Result<StallReport> execute(String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(normalized)) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "unknown stall kind \"" + normalized + "\" — the two are " + String.join(", ", KINDS),
                    java.util.Map.of("field", "kind")));
        }
        try {
            theater.start(normalized);
        } catch (IllegalStateException failed) {
            if (theater.isRunning()) {
                return Result.failed(Failure.of(FailureKind.CONFLICT,
                        "a stall is already parked (" + theater.kind() + ") — stop it first", null));
            }
            return Result.failed(Failure.of(FailureKind.INTERNAL,
                    "the stall never settled: " + failed.getMessage(), null));
        }
        return Result.success(read.execute());
    }
}
