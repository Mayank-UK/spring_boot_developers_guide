package guide.domain.reliability;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the demo's downstream dial: healthy → flaky → down → slow. Input
 * arrives as a RAW string (the controller stays thin) and is validated
 * here — a typo is the typed 400 envelope, never a 500. Returns the
 * canonical lowercase mode so the client can echo what took effect.
 */
public final class SetDownstreamMode {

    private final DownstreamTheater theater;

    public SetDownstreamMode(DownstreamTheater theater) {
        this.theater = theater;
    }

    public Result<String> execute(String rawMode) {
        Optional<RetryPolicy.DownstreamMode> parsed = RetryPolicy.DownstreamMode.parse(rawMode);
        if (parsed.isEmpty()) {
            return Result.failed(Failure.of(FailureKind.VALIDATION,
                    "mode must be one of " + RetryPolicy.DownstreamMode.names(),
                    Map.of("parameter", "mode", "allowed", RetryPolicy.DownstreamMode.names())));
        }
        RetryPolicy.DownstreamMode mode = parsed.get();
        theater.setMode(mode);
        return Result.success(mode.name().toLowerCase());
    }
}
