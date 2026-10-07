package guide.core.result;

import java.util.List;

/** The success arm of {@link Result}: a value plus (possibly empty) warnings. */
public record Success<T>(T value, List<String> warnings) implements Result<T> {

    public Success {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
