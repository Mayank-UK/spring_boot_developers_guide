package guide.presentation.demo;

import guide.core.errors.AppException;
import guide.core.errors.ErrorResponse;
import guide.core.errors.ValidationException;
import guide.core.logging.RequestIdFilter;
import guide.core.result.Failed;
import guide.core.result.Result;
import guide.core.result.Success;
import guide.domain.files.CreateThumbnail;
import guide.domain.files.DownloadDisposition;
import guide.domain.files.DownloadFile;
import guide.domain.files.DownloadFile.Downloadable;
import guide.domain.files.DownloadFile.Opener;
import guide.domain.files.FileRecord;
import guide.domain.files.GeneratedBytePattern;
import guide.domain.files.HttpRange;
import guide.domain.files.ParseRange;
import guide.domain.files.UploadFile;
import guide.domain.files.UploadOutcome;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Demo endpoints for topic 26 (files-media), one handler per section:
 * - POST /api/demo/files/upload — multipart in, STREAM to the store:
 *   getInputStream (never getBytes), sanitized name, sniffed type, sha256
 *   digested while streaming, typed 413 when the cap crosses mid-stream
 * - GET  /api/demo/files — the stored records (list)
 * - GET  /api/demo/files/{id} — the download: strong ETag (the sha256) →
 *   conditional 304; Accept-Ranges + hand-rolled Range parsing → 206
 *   partial / 416 unsatisfiable; Content-Disposition with RFC 6266
 *   filename* for unicode names; Content-Length exact in every branch
 * - POST /api/demo/files/{id}/thumbnail?size= — the derived variant
 * - GET  /api/demo/files/generated/{bytes}?delayMs= — a StreamingResponseBody
 *   generating its body per chunk (the backpressure exhibit; the async
 *   dispatch machinery is topic 09's story, cross-quoted there)
 *
 * The 413 is the one status on this page the app's eight-kind taxonomy
 * does not carry: the domain TYPES the decision (UploadOutcome.Rejected
 * TOO_LARGE), and this edge renders it in the app's SAME envelope — the
 * pattern for domain-specific statuses without growing the global enum.
 */
@RestController
public class FilesDemoController {

    private final UploadFile uploadFile;
    private final DownloadFile downloadFile;
    private final CreateThumbnail createThumbnail;

    public FilesDemoController(UploadFile uploadFile, DownloadFile downloadFile,
            CreateThumbnail createThumbnail) {
        this.uploadFile = uploadFile;
        this.downloadFile = downloadFile;
        this.createThumbnail = createThumbnail;
    }

    // ---- upload: a stream from the first byte to the last ----

    /** The workbench's upload target: the part is drained ONCE, through the guard and the digest. */
    @PostMapping(value = "/api/demo/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) throws IOException {
        UploadOutcome outcome = uploadFile.execute(file.getOriginalFilename(), file.getContentType(),
                file.getInputStream());
        return switch (outcome) {
            case UploadOutcome.Stored stored -> ResponseEntity.status(201).body(UploadView.of(stored));
            case UploadOutcome.Rejected rejected -> {
                if (rejected.reason() == UploadOutcome.Rejection.TOO_LARGE) {
                    yield tooLarge(rejected.message(), rejected.maxBytes(), rejected.receivedBytes());
                }
                throw new ValidationException(rejected.message(),
                        Map.of("reason", rejected.reason().toString().toLowerCase(Locale.ROOT)));
            }
        };
    }

    // ---- the stored records ----

    /** Every record the store holds, newest first. */
    @GetMapping(value = "/api/demo/files", produces = MediaType.APPLICATION_JSON_VALUE)
    List<FileView> list() {
        return downloadFile.listAll().stream().map(FileView::of).toList();
    }

    // ---- the download: validators, ranges, disposition ----

    /**
     * One file, four answers: 304 when the ETag matches, 206 when the
     * Range is satisfiable, 416 when it is not, 200 with the full bytes
     * otherwise. Every branch sets Accept-Ranges, the strong ETag, the
     * sniffed Content-Type, and the RFC 6266 Content-Disposition.
     */
    @GetMapping("/api/demo/files/{id}")
    ResponseEntity<StreamingResponseBody> download(@PathVariable String id,
            @RequestParam(name = "disposition", required = false) String disposition,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) String rangeHeader) {
        Downloadable downloadable = unwrap(downloadFile.execute(id));
        FileRecord record = downloadable.record();

        if (ifNoneMatch != null && ifNoneMatch.contains(record.etag())) {
            return ResponseEntity.status(304)
                    .eTag(record.etag())
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                    .build();
        }
        HttpRange range = ParseRange.parse(rangeHeader, record.size());
        String contentDisposition = DownloadDisposition.header(
                dispositionToken(disposition, record.contentType()), record.filename());

        if (range instanceof HttpRange.Unsatisfiable) {
            return ResponseEntity.status(416)
                    .header(HttpHeaders.CONTENT_RANGE,
                            HttpRange.Unsatisfiable.CONTENT_RANGE_PREFIX + record.size())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .build();
        }
        if (range instanceof HttpRange.Single slice) {
            return ResponseEntity.status(206)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + slice.start() + "-"
                            + slice.endInclusive() + "/" + record.size())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentType(MediaType.parseMediaType(record.contentType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                    .eTag(record.etag())
                    .contentLength(slice.length())
                    .body(transfer(() -> downloadable.range().open(slice.start(), slice.length())));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.parseMediaType(record.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .eTag(record.etag())
                .contentLength(record.size())
                .body(transfer(downloadable.full()));
    }

    // ---- derived variants ----

    /** One thumbnail variant, streamed from the store and back into it. */
    @PostMapping(value = "/api/demo/files/{id}/thumbnail", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<FileView> thumbnail(@PathVariable String id,
            @RequestParam(name = "size", defaultValue = "128") int size) {
        return switch (createThumbnail.execute(id, size)) {
            case Success<FileRecord> created -> ResponseEntity.status(201).body(FileView.of(created.value()));
            case Failed<FileRecord> failed -> throw AppException.of(failed.failure());
        };
    }

    // ---- the generated stream: backpressure made visible ----

    /**
     * A body that never existed as a file: CHUNK_BYTES at a time, flushed
     * as produced, delayMs of theater per chunk. The client's read pace
     * IS the backpressure story — the write blocks when the socket is
     * full, and the server's memory stays flat however slow the reader.
     */
    @GetMapping(value = "/api/demo/files/generated/{bytes}",
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<StreamingResponseBody> generated(@PathVariable long bytes,
            @RequestParam(name = "delayMs", defaultValue = "0") int delayMs) {
        if (bytes < 1 || bytes > GeneratedBytePattern.MAX_BYTES) {
            throw new ValidationException("bytes must be between 1 and " + GeneratedBytePattern.MAX_BYTES,
                    Map.of("bytes", bytes, "max", GeneratedBytePattern.MAX_BYTES));
        }
        if (delayMs < 0 || delayMs > GeneratedBytePattern.MAX_DELAY_MS) {
            throw new ValidationException("delayMs must be between 0 and " + GeneratedBytePattern.MAX_DELAY_MS,
                    Map.of("delayMs", delayMs, "max", GeneratedBytePattern.MAX_DELAY_MS));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bytes)
                .body(output -> GeneratedBytePattern.writeTo(output, bytes, delayMs));
    }

    // ---- views: what crosses the wire ----

    /** The upload's honest 201: served type beside claimed type, observed size, digest, ETag. */
    record UploadView(String id, String filename, String contentType, String claimedContentType,
            long size, String sha256, String etag, String uploadedAt, String derivedFrom) {

        static UploadView of(UploadOutcome.Stored stored) {
            FileRecord record = stored.record();
            return new UploadView(record.id(), record.filename(), record.contentType(),
                    stored.claimedContentType(), record.size(), record.sha256(), record.etag(),
                    record.uploadedAt().toString(), record.derivedFrom());
        }
    }

    /** The list/thumbnail view: the record, provenance included. */
    record FileView(String id, String filename, String contentType, long size, String sha256,
            String etag, String uploadedAt, String derivedFrom) {

        static FileView of(FileRecord record) {
            return new FileView(record.id(), record.filename(), record.contentType(), record.size(),
                    record.sha256(), record.etag(), record.uploadedAt().toString(), record.derivedFrom());
        }
    }

    // ---- assembly helpers ----

    /** Inline only for types this origin is willing to render (sniffed, never claimed); attachment otherwise. */
    private static String dispositionToken(String requested, String contentType) {
        if ("inline".equals(requested) || "attachment".equals(requested)) {
            return requested;
        }
        boolean renderable = contentType.startsWith("image/") || contentType.equals("text/plain")
                || contentType.equals("application/pdf");
        return renderable ? "inline" : "attachment";
    }

    /** The typed 413: same envelope, domain-typed decision (see class doc). */
    private static ResponseEntity<ErrorResponse> tooLarge(String message, long maxBytes, long receivedBytes) {
        return ResponseEntity.status(413).body(ErrorResponse.of("payload_too_large",
                message + " (max " + maxBytes + " bytes, received " + receivedBytes + ")",
                currentRequestId(), Map.of("maxBytes", maxBytes, "receivedBytes", receivedBytes)));
    }

    /** Same request-id resolution the global handler uses, so the envelope carries a real id. */
    private static String currentRequestId() {
        String mdc = MDC.get(RequestIdFilter.MDC_KEY);
        return mdc != null && !mdc.isEmpty() ? mdc : "n/a";
    }

    /** Wraps an opener into the StreamingResponseBody that drains and closes it. */
    private static StreamingResponseBody transfer(Opener opener) {
        return output -> {
            try (InputStream content = opener.open()) {
                content.transferTo(output);
            }
        };
    }

    private static <T> T unwrap(Result<T> result) {
        return switch (result) {
            case Success<T> success -> success.value();
            case Failed<T> failed -> throw AppException.of(failed.failure());
        };
    }
}
