package guide.domain.files;

import java.io.IOException;

/** A consumer that may throw {@link IOException} — the produce-side seam of the FileStore port. */
@FunctionalInterface
public interface IOConsumer<T> {

    void accept(T value) throws IOException;
}
