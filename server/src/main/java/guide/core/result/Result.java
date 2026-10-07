package guide.core.result;

/**
 * The Result type used by every use case. Domain and data layers signal
 * failures as VALUES (never by throwing), so control flow stays explicit and
 * the presentation edge decides how failures map onto HTTP (see
 * core/errors/AppException — the bridge from a Failed Result to the typed
 * exception the handler advice renders).
 *
 * Success may carry warnings: non-fatal notes (degradation, fallbacks) that
 * travel WITH the value instead of aborting the call.
 */
public sealed interface Result<T> permits Success, Failed {

    static <T> Result<T> success(T value) {
        return new Success<>(value, java.util.List.of());
    }

    static <T> Result<T> success(T value, java.util.List<String> warnings) {
        return new Success<>(value, warnings);
    }

    static <T> Result<T> failed(guide.core.errors.Failure failure) {
        return new Failed<>(failure);
    }

    default boolean isSuccess() {
        return this instanceof Success<T>;
    }

    /** Transforms the success value (warnings travel along); failures pass through untouched. */
    default <U> Result<U> map(java.util.function.Function<? super T, ? extends U> mapper) {
        return switch (this) {
            case Success<T> success -> new Success<>(mapper.apply(success.value()), success.warnings());
            case Failed<T> failed -> new Failed<>(failed.failure());
        };
    }
}
