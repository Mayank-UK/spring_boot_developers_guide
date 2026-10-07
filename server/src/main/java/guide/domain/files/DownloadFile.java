package guide.domain.files;

import guide.core.errors.Failure;
import guide.core.result.Result;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * The download use case: resolve an id to its record AND the openers that
 * hand out fresh byte streams for it. The openers are lazy (no handle is
 * created until the edge asks) and every call opens a NEW stream — the
 * defensive-copy rule from the port, stated where the controller can see
 * it. Range slices are expressed as offset+length, not start+endInclusive,
 * because that is the stream vocabulary (skip N, read M) — the inclusive
 * bounds live in HttpRange where the header semantics live.
 */
public final class DownloadFile {

    /** Opens a byte stream; IOException travels to the edge, which owns the 500 mapping. */
    @FunctionalInterface
    public interface Opener {

        InputStream open() throws IOException;
    }

    /** The download plan the edge executes: the record plus its two stream openers. */
    public record Downloadable(FileRecord record, Opener full, RangeOpener range) {

        @FunctionalInterface
        public interface RangeOpener {

            InputStream open(long offset, long length) throws IOException;
        }
    }

    private final FileStore store;

    public DownloadFile(FileStore store) {
        this.store = store;
    }

    /** Resolves one id, or the app's typed not-found failure. */
    public Result<Downloadable> execute(String id) {
        Optional<FileRecord> found = store.find(id);
        if (found.isEmpty()) {
            return Result.failed(Failure.of(
                    guide.core.errors.FailureKind.NOT_FOUND,
                    "no stored file with id \"" + id + "\"",
                    java.util.Map.of("id", id)));
        }
        FileRecord record = found.get();
        return Result.success(new Downloadable(record,
                () -> store.open(record.id()),
                (offset, length) -> store.openRange(record.id(), offset, length)));
    }

    /** Every stored record, newest first — the workbench's listing. */
    public java.util.List<FileRecord> listAll() {
        return store.list();
    }
}
