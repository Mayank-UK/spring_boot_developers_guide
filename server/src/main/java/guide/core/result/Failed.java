package guide.core.result;

import guide.core.errors.Failure;

/** The failure arm of {@link Result}: exactly one typed {@link Failure}. */
public record Failed<T>(Failure failure) implements Result<T> {

    public Failed {
        java.util.Objects.requireNonNull(failure, "failure");
    }
}
