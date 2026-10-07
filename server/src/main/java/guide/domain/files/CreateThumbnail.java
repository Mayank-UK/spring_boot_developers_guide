package guide.domain.files;

import guide.core.errors.Failure;
import guide.core.result.Result;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import net.coobird.thumbnailator.Thumbnails;

/**
 * The derived-variant use case: one real image-processing call
 * (Thumbnailator) streaming pixels from the store straight back into the
 * store — the source is a fresh InputStream from the port, the sink is the
 * port's produce() output stream wrapped in a DigestOutputStream, so the
 * variant's sha256 is digested as it is encoded, same single-pass rule as
 * uploads. The variant is stored as a full FileRecord whose derivedFrom
 * names the source — provenance without a second table.
 *
 * Deliberate constraints, each teachable:
 * - only ORIGINALS can be thumbnailed (no thumbnail-of-thumbnail chains
 *   growing the store unboundedly);
 * - only image/* records (the sniffed type — a PDF named ".png" never
 *   reaches the decoder);
 * - re-encoded as PNG: deterministic-ish output, and RE-ENCODING IS THE
 *   EXIF STRIP — metadata does not survive a decode/encode round trip, so
 *   the thumbnail cannot leak the camera's GPS tags. (An explicit
 *   .exif(ExifInterface...) copy exists in Thumbnailator when you WANT to
 *   preserve orientation metadata; the default is strip.)
 */
public final class CreateThumbnail {

    /** The bounding box: the variant fits within size×size, aspect preserved. */
    public static final int MIN_SIZE = 16;
    public static final int MAX_SIZE = 512;

    private final FileStore store;

    public CreateThumbnail(FileStore store) {
        this.store = store;
    }

    /** Creates (and stores) one thumbnail variant of {@code id} at {@code size}×{@code size}. */
    public Result<FileRecord> execute(String id, int size) {
        if (size < MIN_SIZE || size > MAX_SIZE) {
            return Result.failed(Failure.of(guide.core.errors.FailureKind.VALIDATION,
                    "size must be between " + MIN_SIZE + " and " + MAX_SIZE,
                    Map.of("size", size, "min", MIN_SIZE, "max", MAX_SIZE)));
        }
        var original = store.find(id);
        if (original.isEmpty()) {
            return Result.failed(Failure.of(guide.core.errors.FailureKind.NOT_FOUND,
                    "no stored file with id \"" + id + "\"", Map.of("id", id)));
        }
        FileRecord source = original.get();
        if (source.isDerived()) {
            return Result.failed(Failure.of(guide.core.errors.FailureKind.VALIDATION,
                    "only originals can be thumbnailed — derived variants would chain forever",
                    Map.of("id", id, "derivedFrom", source.derivedFrom())));
        }
        if (!source.contentType().startsWith("image/")) {
            return Result.failed(Failure.of(guide.core.errors.FailureKind.VALIDATION,
                    "only image/* files can be thumbnailed (stored type is " + source.contentType() + ")",
                    Map.of("id", id, "contentType", source.contentType())));
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            FileRecord pending = FileRecord.pendingDerived(
                    variantName(source.filename(), size), SniffedContentType.PNG, source.id());
            FileRecord produced = store.produce(pending, target -> {
                DigestOutputStream digested = new DigestOutputStream(target, sha256);
                resizeInto(store.open(source.id()), size, digested);
            });
            FileRecord complete = new FileRecord(produced.id(), produced.filename(), produced.contentType(),
                    produced.size(), HexFormat.of().formatHex(sha256.digest()), produced.uploadedAt(),
                    source.id());
            return Result.success(store.saveMetadata(complete));
        } catch (IOException | NoSuchAlgorithmException failure) {
            return Result.failed(Failure.of(guide.core.errors.FailureKind.VALIDATION,
                    "the stored bytes are not a decodable image",
                    Map.of("id", id, "cause", String.valueOf(failure.getMessage()))));
        }
    }

    /**
     * The real call — quoted verbatim in the topic: decode from the fresh
     * stream, fit within the box preserving aspect, re-encode as PNG into
     * the digested sink. Decode loads the full bitmap into memory by
     * necessity (that is what image processing IS), which is exactly why
     * size caps and dimension bounds exist on the upload side.
     */
    private static void resizeInto(java.io.InputStream source, int size, OutputStream target)
            throws IOException {
        Thumbnails.of(source).size(size, size).outputFormat("png").toOutputStream(target);
    }

    /** "cat.jpg" at 128 → "cat-128.png" — sanitized again, since names are display data. */
    private static String variantName(String originalName, int size) {
        int dot = originalName.lastIndexOf('.');
        String base = dot > 0 ? originalName.substring(0, dot) : originalName;
        return SanitizedFilename.sanitize(base + "-" + size + ".png").orElse(size + ".png");
    }
}
