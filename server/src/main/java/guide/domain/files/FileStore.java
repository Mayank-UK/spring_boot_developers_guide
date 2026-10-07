package guide.domain.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;

/**
 * The files area's port: what "a place files live" means to the domain.
 * The adapter (data/files/DiskFileStore) owns the disk; production would
 * write a second adapter over object storage (S3 and kin) without the
 * domain or controllers changing at all — that is the whole point of the
 * seam.
 *
 * Three disciplines are part of the CONTRACT, not the implementation:
 * - READS hand out FRESH streams every call (a defensive copy of the
 *   resource, never a shared handle) — two concurrent downloads must never
 *   see each other's read positions.
 * - WRITE paths take streams and writers, never byte[] — a caller cannot
 *   hand this port a buffer, so no adapter could ever be handed one either.
 * - METADATA is written separately from bytes, AFTER the caller's
 *   enrichment (digest, sniffed type) completes: save/produce persist
 *   bytes and report the observed size; saveMetadata persists the record.
 *   A rejected upload (cap crossed, empty stream) therefore leaves only a
 *   cleaned-up bytes target behind — no orphaned metadata.
 */
public interface FileStore {

    /**
     * Persists {@code record}'s bytes by draining {@code content} fully to
     * the file named by the record's id; the returned record is the given
     * one with the OBSERVED size set. Implementations must clean up their
     * partial target when the stream fails mid-copy.
     */
    FileRecord save(FileRecord record, InputStream content) throws IOException;

    /**
     * Persists {@code record}'s bytes by letting {@code producer} write
     * into the store's output stream — the derived-variant path (the
     * thumbnail encoder streams pixels straight to disk). Same size and
     * cleanup contract as {@link #save}.
     */
    FileRecord produce(FileRecord record, IOConsumer<OutputStream> producer) throws IOException;

    /** Persists the record's metadata (sidecar/row/whatever the adapter chose) for an already-stored id. */
    FileRecord saveMetadata(FileRecord record) throws IOException;

    /** Removes bytes and metadata for one id — the rejection cleanup path. */
    void delete(String id) throws IOException;

    /** The record's metadata, empty when the id is unknown (or malformed — never resolved as a path). */
    Optional<FileRecord> find(String id);

    /** Every stored record, originals and deriveds, newest first. */
    List<FileRecord> list();

    /** A fresh stream over the file's full bytes; caller owns and closes it. */
    InputStream open(String id) throws IOException;

    /** A fresh stream over exactly {@code length} bytes starting at {@code offset}. */
    InputStream openRange(String id, long offset, long length) throws IOException;
}
