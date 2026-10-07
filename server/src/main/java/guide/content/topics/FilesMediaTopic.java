package guide.content.topics;

import static guide.content.topics.TopicBlocks.code;
import static guide.content.topics.TopicBlocks.demo;
import static guide.content.topics.TopicBlocks.section;
import static guide.content.topics.TopicBlocks.text;

import guide.content.ContentSection;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;

/**
 * Topic 26 — files-media. Group files-media, order 1. Uploads as streams
 * (multipart anatomy, memory pressure, caps that fire mid-stream),
 * downloads done honestly (Content-Disposition, RFC 6266 filename*,
 * content-type authority), range requests (206/416 and every off-by-one
 * in the parser), streaming with backpressure (StreamingResponseBody, the
 * async dispatch story from topic 09), image processing as derived
 * variants (Thumbnailator, EXIF stripping by re-encode), and path
 * traversal — ../ anatomy, encoded variants, and the two-layer defense.
 * One live demo: files:workbench (upload → list → inline download →
 * range fetch → thumbnail).
 */
public final class FilesMediaTopic {

    private FilesMediaTopic() {
    }

    public static Topic topic() {
        return new Topic("files-media", "Files and Media",
                "Uploads are streams, not payloads — and downloads are "
                        + "conversations about bytes: multipart anatomy and why getBytes() is a "
                        + "memory incident, size caps that fire mid-stream with a typed 413, "
                        + "content-type authority and RFC 6266 filename* encodings, the full "
                        + "range-request story (206, 416, and every off-by-one trap in the "
                        + "parser), streaming bodies with backpressure, thumbnails as derived "
                        + "variants that strip EXIF by re-encoding, and path traversal — the "
                        + "../ anatomy, its encoded cousins, and the canonicalization defense.",
                TopicGroupId.FILES_MEDIA, 1,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("uploads-are-streams-not-payloads", "Uploads are streams, not payloads",
                        "Multipart anatomy, MultipartFile.getInputStream vs getBytes, and why the size cap must fire mid-stream — not after the bytes are already in your heap.",
                        text("upload:anatomy", """
                                A FILE UPLOAD arrives as a MULTIPART/FORM-DATA body: a Content-Type header carrying a random boundary token, then a sequence of PARTS, each with its own small header block (a Content-Disposition naming the form field and the filename) and its own bytes, with the boundary token on its own line separating them. The browser streams this envelope as it is built; nothing about the format requires the whole thing to exist in memory at once. Spring hands you each part as a MultipartFile, and that interface hides the entire lesson in two methods: getBytes() returns byte[] — the whole part, materialized in your heap, however big it is; getInputStream() returns a stream over the part, which with the servlet defaults (and with this app's deliberate threshold-0 configuration) is a stream over a TEMP FILE the parser already spooled. The first is a one-way ticket to an OutOfMemoryError under load: ten concurrent 500 MB uploads through getBytes() is 5 GB of heap gone. The second never holds more than a copy buffer. EVERY upload path in this topic is built on the second — the codebase grep for getBytes() on an upload path should always come back empty."""),
                        text("upload:cap-early", """
                                THE CAP MUST FIRE BEFORE THE BYTES ARRIVE — or rather, while they arrive, byte by byte, not after. A guard that checks the size AFTER reading the whole stream has already paid the memory cost it exists to prevent; a guard that trusts the Content-Length header has guarded nothing (the header lies). The honest shape is a WRAPPING STREAM: the upload drains through a guard that counts bytes as they flow and throws the moment the count crosses the cap — a hostile 2 GB upload costs the server 10 MB of disk spool, not 2 GB of anything. Two ceilings exist here, deliberately ordered. The DOMAIN cap (10 MB, configurable) is the one users meet: it fires mid-stream and answers the typed 413 envelope with evidence. The SERVLET ceiling (cap + headroom, wired as an explicit MultipartConfigElement bean in FilesDemoConfig) exists only as the container's last-resort parse limit — set comfortably ABOVE the domain cap so it never pre-empts it, because its failure mode (MaxUploadSizeExceededException before your code runs) would render as a generic 500 instead of the typed answer."""),
                        code("upload:handler", "java",
                                "guide/presentation/demo/FilesDemoController.java — the upload handler: getInputStream (never getBytes), the client's claims passed as data, the typed outcome mapped at the edge",
                                "server/src/main/java/guide/presentation/demo/FilesDemoController.java", """
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
                                """),
                        text("upload:guard-stream", """
                                The guard itself is the smallest class in the area and the one doing the most work: a FilterInputStream whose read() passes every byte through onByte(), and onByte() does three jobs in that single pass — it remembers the first 32 bytes (the head the content-type sniffer needs, captured while they are still in flight because a stream cannot be rewound), it counts the total (the record's size field is OBSERVED, never taken from a header), and it throws the typed Exceeded exception the instant the count crosses the cap. Notice what the exception is not: an IOException. It is a RuntimeException, so it punches through the plumbing unwrapped, and the store's own catch-all deletes the partial file before rethrowing — a rejected upload leaves no orphan on disk."""),
                        code("upload:onbyte", "java",
                                "guide/domain/files/GuardedUploadStream.java — the mid-stream cap: head capture, count, and the throw — one pass, three jobs",
                                "server/src/main/java/guide/domain/files/GuardedUploadStream.java", """
                                private void onByte(byte b) {
                                    if (headFilled < head.length) {
                                        head[headFilled++] = b;
                                    }
                                    received++;
                                    if (received > maxBytes) {
                                        throw new Exceeded(maxBytes, received);
                                    }
                                }
                                """)),
                section("receiving-uploads-safely", "Receiving uploads safely",
                        "The whole pipeline in one flow: sanitized names, magic-byte sniffing over client claims, sha256 digested while streaming — with the workbench demo inline.",
                        text("safe:pipeline", """
                                One use case, UploadFile, owns the flow, and its shape is the checklist this section teaches. The FILENAME is sanitized FIRST (next section has the full anatomy) — it is display data, never a path. The CONTENT is then drained exactly once through a pipeline: GuardedUploadStream (cap, count, head) wrapped by the JDK's DigestInputStream, so the SHA-256 hash is computed WHILE the bytes flow to disk — not after, not on a second read. The digest-while-streaming point matters more than it looks: the hash is this file's IDENTITY (it becomes the ETag, the dedup key, the integrity check), and computing it in the same pass is free; computing it after means reading 10 MB back off disk for every upload, or worse, holding the file in memory to hash it. The STORE receives the wrapped stream and persists bytes under a fresh UUID; only after the copy completes does the use case write the metadata sidecar — a rejected upload (bad name, empty stream, cap crossed) therefore never leaves metadata behind."""),
                        code("safe:usecase", "java",
                                "guide/domain/files/UploadFile.java — the single pass: guard wraps content, DigestInputStream wraps guard, sniffing decides the stored type, metadata last",
                                "server/src/main/java/guide/domain/files/UploadFile.java", """
                                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                                GuardedUploadStream guard = new GuardedUploadStream(content, maxBytes);
                                FileRecord bytes = store.save(FileRecord.pendingOriginal(filename, "application/octet-stream"),
                                        new DigestInputStream(guard, sha256));
                                // …
                                String servedType = SniffedContentType.sniff(guard.head(), claimedContentType);
                                FileRecord complete = new FileRecord(bytes.id(), bytes.filename(), servedType,
                                        bytes.size(), HexFormat.of().formatHex(sha256.digest()), bytes.uploadedAt(), null);
                                return new UploadOutcome.Stored(store.saveMetadata(complete),
                                        claimedContentType == null ? "(none claimed)" : claimedContentType, servedType);
                                """),
                        text("safe:sniff", """
                                CONTENT-TYPE AUTHORITY: the client's claim about a file's type is an assertion, not evidence, and the classic exploit is uploading an HTML or SVG payload labeled image/png so a victim's browser renders it inline IN YOUR ORIGIN — stored XSS delivered by content type (topic 25's origin lesson, weaponized through files). The defense is to SNIFF from the leading bytes: magic signatures are bytes the attacker cannot change without changing what the file IS — a PNG that does not start with the PNG signature is not a PNG. The rule this store implements: magic wins, always; the claim is believed only when the bytes corroborate it (the single fallback is text/plain, and only for plausible text); everything else is stored as application/octet-stream, the inert type browsers download rather than execute. The cost of that strictness is real and worth paying knowingly: a plain .txt with no magic becomes octet-stream and downloads instead of rendering — honest bytes over convenient labels. Production systems reach for Apache Tika's much larger signature table; the DISCIPLINE is the lesson, not the table size."""),
                        code("safe:sniff-impl", "java",
                                "guide/domain/files/SniffedContentType.java — magic wins; the claim survives only as the corroborated text/plain fallback; octet-stream otherwise",
                                "server/src/main/java/guide/domain/files/SniffedContentType.java", """
                                public static String sniff(byte[] head, String claimedType) {
                                    if (startsWith(head, new byte[]{(byte) 0x89, 'P', 'N', 'G'})) {
                                        return PNG;
                                    }
                                    // …
                                    if (TEXT.equalsIgnoreCase(claimedType == null ? "" : claimedType.trim()) && looksLikeText(head)) {
                                        return TEXT;
                                    }
                                    return BINARY;
                                }
                                """),
                        demo("safe:workbench-demo", "files:workbench",
                                "The files workbench — upload, list, inline download, range fetch, thumbnail",
                                "Pick any file and watch the upload card show the SNIFFED type beside what your browser claimed (rename an .html to .png and watch the claim lose). List the store, select a record, then fetch it inline (Content-Disposition made visible), fetch a slice with an editable Range header (the card reports the 206, the Content-Range, and how many bytes actually arrived), and derive a thumbnail variant server-side."),
                        text("safe:413", """
                                The 413 is this page's one status outside the app's eight-kind error taxonomy (topic 30 teaches those eight as the app's core), and the split is deliberate: TOO_LARGE can only ever be produced by THIS endpoint, so growing the global enum for it would tax every other error path. Instead the DOMAIN types the decision — UploadOutcome.Rejected TOO_LARGE, carrying maxBytes and receivedBytes — and the edge renders it in the app's SAME envelope ({error: {code: \"payload_too_large\", message, requestId, maxBytes, receivedBytes}}) with the 413 status. The pattern generalizes: domain-specific statuses stay typed in the domain, the shared envelope stays shared, and nothing global changes. The workbench demo shows it live — its upload cap here is the configured default, and the terminal companion curls a >cap payload to see the envelope whole.""")),
                section("downloads-and-content-disposition", "Downloads and Content-Disposition",
                        "attachment vs inline, the RFC 6266 filename* encoding that keeps unicode names intact, validators on every response — and who owns the content type.",
                        text("down:disposition", """
                                A download is two decisions wearing one header. ATTACHMENT tells the browser "save this; do not render it" — the only safe default for bytes you do not fully control, and the right answer for octet-stream and everything untrusted. INLINE tells the browser "render this in the page" — EARNED, never assumed: this endpoint grants it only for sniffed image/*, text/plain, and application/pdf, and an explicit ?disposition= override exists for the demonstration (the workbench's inline button rides it). The second decision is the FILENAME, and RFC 6266 gives it two spellings that must both be present: the legacy filename=\"…\" parameter, which is ASCII-only (non-ASCII dropped, quotes escaped) and exists for ancient clients; and filename*=UTF-8''…, the RFC 5987 percent-encoding of the real UTF-8 name. That star parameter is why "résumé–final.png" arrives at the user's Downloads folder with its accents intact — modern browsers prefer it, and a server that sends only the ASCII fallback quietly mangles every non-English filename it serves."""),
                        code("down:header-encoder", "java",
                                "guide/domain/files/DownloadDisposition.java — both spellings in one value: the escaped ASCII fallback, then the RFC 5987 star parameter",
                                "server/src/main/java/guide/domain/files/DownloadDisposition.java", """
                                public static String header(String disposition, String filename) {
                                    String fallback = asciiFallback(filename);
                                    return disposition
                                            + "; filename=\\\"" + fallback.replace("\\\\", "\\\\\\\\").replace("\\\"", "\\\\\\\"") + "\\\""
                                            + "; filename*=UTF-8''" + encode(filename);
                                }
                                """),
                        text("down:validators", """
                                Every download response carries its VALIDATORS, because a file is the most cacheable thing an API serves: its bytes never change (the id is the address, and a different upload is a different id). The ETag here is STRONG — the quoted sha256 itself — which is a promise this endpoint can actually keep: byte-identical content for the same tag, because the tag IS the content's hash (topic 21's specimen used a weak W/\"v{version} tag for a JSON representation it could not promise byte-stable; files are the honest case weak etags were apologizing for). The conditional dance is done BY HAND here rather than by Spring's built-in short-circuit, because this handler needs to answer THREE questions before any byte ships — 304 (If-None-Match matches), 206/416 (the Range header), 200 (the rest) — and the ordering is the contract: validators first, ranges second, because an unchanged file answers 304 regardless of which slice you asked about. Topic 21 tells the caching half of this story (Cache-Control, shared caches, the round trip's economics); this page owns the bytes half."""),
                        code("down:handler", "java",
                                "guide/presentation/demo/FilesDemoController.java — validators first, ranges second, full body last; Content-Length exact in every branch",
                                "server/src/main/java/guide/presentation/demo/FilesDemoController.java", """
                                Downloadable downloadable = unwrap(downloadFile.execute(id));
                                FileRecord record = downloadable.record();

                                if (ifNoneMatch != null && ifNoneMatch.contains(record.etag())) {
                                    return ResponseEntity.status(304)
                                            .eTag(record.etag())
                                            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                                            .build();
                                }
                                HttpRange range = ParseRange.parse(rangeHeader, record.size());
                                """),
                        text("down:authority", """
                                WHO OWNS THE CONTENT TYPE on the way out: the STORE, not the request. The download response's Content-Type is the sniffed type recorded at upload time — the client downloading gets no vote, and neither did the client uploading. The Content-Length is set EXPLICITLY in every branch (full size for 200, exactly the slice length for 206) because the body is a StreamingResponseBody — Spring cannot infer a length from a stream, and a length-less response invites chunked transfer where the client cannot verify completeness; with the length set, a truncated connection is detectable, which is what resumable downloads (next section) are built on. One quiet detail worth noticing: the handler streams through transferTo() over a FRESH InputStream per request — the store hands out a new handle every call (the port's defensive-copy rule), so two concurrent downloads of one file never share read positions and never corrupt each other.""")),
                section("range-requests", "Range requests",
                        "206 Partial Content, Content-Range, Accept-Ranges, 416 with the star-slash size — video seeking and resumable downloads, and the parser's off-by-one traps, exhaustively tested.",
                        text("range:why", """
                                RANGE REQUESTS are how HTTP moves PART of a representation: the client sends Range: bytes=0-99, the server answers 206 Partial Content with exactly those bytes and a Content-Range header saying which slice of what total. Two use cases carry the whole feature. VIDEO SEEKING: a browser playing a 2 GB file sends bytes=1073741824- the moment you scrub past the first gigabyte — without ranges it would have to download everything up to the seek point first. RESUMABLE DOWNLOADS: a connection that dies at 900 MB of 1 GB restarts with bytes=943718400- and finishes instead of starting over — which only works because the response carried Accept-Ranges: bytes and an exact Content-Length, the two headers every full 200 from the previous section sets. The server OPTS IN by sending Accept-Ranges; the client ASKS with Range; the negotiation is one header each way."""),
                        text("range:grammar", """
                                THE GRAMMAR IS SMALL AND EVERY EDGE IS A TRAP. Byte positions are zero-based and the end bound is INCLUSIVE: bytes=0-99 asks for 100 bytes. An END past EOF is legal and CLAMPED (bytes=0-999999 of a 100-byte file is 0-99) — that leniency is load-bearing, because a resuming client working from a stale total size must receive the remainder, not an error. A START at-or-past EOF is UNSATISFIABLE (416) — the mirror image of the clamp, and the single most mis-implemented line in naive parsers. A SUFFIX range (bytes=-50) means the LAST 50 bytes; a suffix longer than the file is the whole file; the degenerate bytes=-0 addresses nothing. REVERSED bounds (bytes=50-10) are unsatisfiable. And multi-range lists (bytes=0-1,5-6) ask for multipart/byteranges responses — legitimate, rarely worth it; this endpoint declines them outright rather than silently serving only the first slice, and says so. The RFC's own fallback for nonsense ranges is to IGNORE the header and answer 200; a teaching endpoint prefers the 416 so the refusal is visible — a documented divergence, not an accident."""),
                        code("range:parser", "java",
                                "guide/domain/files/ParseRange.java — the trap-dense core: EOF clamp for ends, 416 for starts, reversed bounds, the inclusive math",
                                "server/src/main/java/guide/domain/files/ParseRange.java", """
                                long start = Long.parseLong(firstText);
                                if (start >= size) {
                                    return new HttpRange.Unsatisfiable(header); // past EOF — NOT clamped (see class doc)
                                }
                                long lastInclusive = lastText.isEmpty()
                                        ? size - 1 // open-ended: through the final byte
                                        : Math.min(Long.parseLong(lastText), size - 1); // over-long end is clamped
                                if (start > lastInclusive) {
                                    return new HttpRange.Unsatisfiable(header); // reversed (or end clamped below start)
                                }
                                return new HttpRange.Single(start, lastInclusive);
                                """),
                        code("range:suffix", "java",
                                "the same file — suffix semantics: last N bytes, whole file when N exceeds it, nothing when N is zero",
                                "server/src/main/java/guide/domain/files/ParseRange.java", """
                                private static HttpRange suffixRange(long suffixLength, long size, String header) {
                                    if (suffixLength <= 0) {
                                        return new HttpRange.Unsatisfiable(header); // "bytes=-0" addresses nothing
                                    }
                                    // A suffix at least as long as the file addresses the whole file.
                                    long start = Math.max(0, size - suffixLength);
                                    return new HttpRange.Single(start, size - 1);
                                }
                                """),
                        text("range:serving", """
                                SERVING the slice is three headers and a bounded stream: 206, Content-Range: bytes START-END/SIZE (the star-less form — real numbers, real total), and Content-Length set to EXACTLY the slice length (END-START+1, the inclusive-end arithmetic again), with the body a stream over just those bytes (the store's openRange: skip to offset, read at most N). The 416 answer carries Content-Range in its star form — bytes */SIZE — which is not decoration: it is the header's way of saying "unsatisfiable, and HERE is the total you were wrong about," and it is how a client that assumed a stale size learns the real one. IF-RANGE deserves its one paragraph: a client that stored half a download can make its resume request conditional — If-Range: <etag> means "send the range only if the file is still the version I think it is; otherwise send me the whole 200." It closes the race where the file changed between the first half and the resume; this endpoint teaches it and leaves it unimplemented, because its store is immutable-by-id and the race cannot arise — the honest reason, not an excuse."""),
                        code("range:edge", "java",
                                "guide/presentation/demo/FilesDemoController.java — the two non-200 answers side by side: the star-slash refusal and the exact slice",
                                "server/src/main/java/guide/presentation/demo/FilesDemoController.java", """
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
                                            // …
                                            .contentLength(slice.length())
                                            .body(transfer(() -> downloadable.range().open(slice.start(), slice.length())));
                                }
                                """)),
                section("streaming-with-backpressure", "Streaming with backpressure",
                        "StreamingResponseBody, the async dispatch story (topic 09's machinery, reused), write pacing, and why buffering betrays you exactly when the payload is large.",
                        text("stream:body", """
                                Some bodies do not exist yet — they are GENERATED as they are written. This area's specimen is GET /api/demo/files/generated/{bytes}?delayMs=: a deterministic 251-byte pattern, CHUNK_BYTES at a time, flushed per chunk, with an optional pause after each — pure theater that makes the pacing VISIBLE (pipe it through a hex dumper in the terminal companion and watch the chunks tick in at the delayMs cadence). The Spring type is StreamingResponseBody — a lambda handed the response's raw OutputStream — and the mechanics are topic 09's story retold for files: the servlet request goes ASYNC, the handler thread is released the moment the lambda is scheduled, a worker from the async pool drives the writes, and the DISPATCH back through the framework happens when the lambda completes. StreamingResponseBody is the MVC-native answer (Reactive WebFlux, topic 41, is the other house); on virtual threads the blocking-write style is exactly right."""),
                        code("stream:generator", "java",
                                "guide/domain/files/GeneratedBytePattern.java — the write discipline: fixed chunk, write, flush, pause; never more produced than handed off",
                                "server/src/main/java/guide/domain/files/GeneratedBytePattern.java", """
                                byte[] chunk = new byte[CHUNK_BYTES];
                                long written = 0;
                                while (written < totalBytes) {
                                    int filling = (int) Math.min(chunk.length, totalBytes - written);
                                    for (int i = 0; i < filling; i++) {
                                        chunk[i] = (byte) ((written + i) % 251);
                                    }
                                    output.write(chunk, 0, filling);
                                    output.flush();
                                    written += filling;
                                    // …
                                }
                                """),
                        text("stream:backpressure", """
                                BACKPRESSURE is the paragraph the express guide wrote a whole section about, and it translates directly. A blocking OutputStream write does not return until the bytes have moved into the socket's send buffer — and when the CLIENT reads slowly (a phone on a train, a script that sleeps between reads), that buffer fills, and the write BLOCKS. That block is not a malfunction; it is the FLOW-CONTROL SIGNAL propagating upstream: the server cannot produce faster than the consumer consumes, and its memory stays FLAT no matter how slow the reader, because the unconsumed bytes live in the socket buffer, not the heap. The express guide met the same signal as res.write() returning false plus a drain event; a servlet OutputStream IS the drain discipline, built in. This is also precisely where BUFFERING BETRAYS YOU: anything that accumulates the body before writing it (a ByteArrayOutputStream, getBytes(), a "just build the whole string" convenience) has silently converted the socket's backpressure into heap growth — one slow client becomes one resident copy, a thousand become an outage. The flush-per-chunk in the generator is the positive half of the same rule: hand every chunk to the socket the moment it exists, so the client sees progress and the server holds at most one chunk."""),
                        code("stream:endpoint", "java",
                                "guide/presentation/demo/FilesDemoController.java — the endpoint: caps validated, exact Content-Length, the lambda IS the body",
                                "server/src/main/java/guide/presentation/demo/FilesDemoController.java", """
                                @GetMapping(value = "/api/demo/files/generated/{bytes}",
                                        produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
                                ResponseEntity<StreamingResponseBody> generated(@PathVariable long bytes,
                                        @RequestParam(name = "delayMs", defaultValue = "0") int delayMs) {
                                    // … the two validation caps (bytes, delayMs) elided — same guard shape as every endpoint
                                    return ResponseEntity.ok()
                                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                            .contentLength(bytes)
                                            .body(output -> GeneratedBytePattern.writeTo(output, bytes, delayMs));
                                }
                                """),
                        code("stream:client", "ts",
                                "client/src/ui/demos/files/workbench_demo.tsx — the client half of the same lesson: the range fetch READS AT ITS OWN PACE, and the received count is the proof",
                                "client/src/ui/demos/files/workbench_demo.tsx", """
                                async function fetchRange(
                                  config: AppConfig,
                                  id: string,
                                  range: string,
                                ): Promise<{ status: number; contentRange: string; received: number }> {
                                  const response = await fetch(`${config.apiBaseUrl}/demo/files/${id}`, {
                                    headers: { range },
                                  });
                                  return {
                                    status: response.status,
                                    contentRange: response.headers.get('content-range') ?? '(none)',
                                    received: (await response.arrayBuffer()).byteLength,
                                  };
                                }
                                """),
                        text("stream:pacing", """
                                The read side deserves its own sentence: a fetch's arrayBuffer() resolves when the LAST byte lands, and the browser's network stack is doing its own buffered reading underneath — the demo's received byte count proves the slice was exact, while the PACING story is one the terminal companion shows better than the browser can (curl -N plus a hex dumper shows the chunks arriving at the server's delayMs cadence, one flush at a time). For real client-side pacing you consume the response as a STREAM (response.body.getReader(), read() in a loop) — the browser-era spelling of exactly the discipline above: take a chunk, process it, pull the next, and never buffer what you have not processed. The generated endpoint exists so this lesson can be FELT: delayMs=100 with a megabyte asked is a 4-minute drip the server survives with flat memory and an idle request thread pool.""")),
                section("image-processing", "Image processing",
                        "Thumbnailator as the derived-variant engine: the real call, streams vs loading bitmaps, EXIF stripping by re-encoding, and where deriveds live.",
                        text("image:call", """
                                A THUMBNAIL is a DERIVED VARIANT: bytes computed from stored bytes, stored as a first-class record of its own — its own id, its own sha256, its own size — whose derivedFrom field names the source. The engine is Thumbnailator (net.coobird:thumbnailator, new dependency, pinned 0.4.21 from Central — the pom comment carries the choice: single jar, no framework coupling, fluent over BufferedImage where imgscalr is dormant and hand-rolled java.awt scaling is a quality trap). The call itself is one line of fluent chain: read from the FRESH stream the store hands out, fit within a size×size BOX preserving aspect, re-encode as PNG, write straight into the store's output stream — source and sink are both streams, the disk adapter owns both ends, and the use case never sees a buffer. The digest rides along exactly like the upload's: the target is wrapped in a DigestOutputStream, so the variant's sha256 is computed as the pixels are encoded."""),
                        code("image:usecase", "java",
                                "guide/domain/files/CreateThumbnail.java — source stream in, digested sink out: the store feeds itself through the use case",
                                "server/src/main/java/guide/domain/files/CreateThumbnail.java", """
                                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                                FileRecord pending = FileRecord.pendingDerived(
                                        variantName(source.filename(), size), SniffedContentType.PNG, source.id());
                                FileRecord produced = store.produce(pending, target -> {
                                    DigestOutputStream digested = new DigestOutputStream(target, sha256);
                                    resizeInto(store.open(source.id()), size, digested);
                                });
                                """),
                        code("image:real-call", "java",
                                "the same file — the one-line engine, quoted verbatim: Thumbnails.of(stream).size(w, h).outputFormat(...).toOutputStream(...)",
                                "server/src/main/java/guide/domain/files/CreateThumbnail.java", """
                                private static void resizeInto(java.io.InputStream source, int size, OutputStream target)
                                        throws IOException {
                                    Thumbnails.of(source).size(size, size).outputFormat("png").toOutputStream(target);
                                }
                                """),
                        code("image:pom", "xml",
                                "server/pom.xml — the flagged new dependency: single jar, no starter, no native code",
                                "server/pom.xml", """
                                <dependency>
                                    <groupId>net.coobird</groupId>
                                    <artifactId>thumbnailator</artifactId>
                                    <version>0.4.21</version>
                                </dependency>
                                """),
                        text("image:truths", """
                                TWO TRUTHS about image work that the streaming habit must not obscure. First: DECODING IS LOADING A BITMAP — there is no stream-decode shortcut; Thumbnails.of(stream) reads the whole image into a BufferedImage in memory, because resampling needs every pixel. That is not a failure of the discipline, it is the NATURE of the operation — and it is exactly why uploads carry size caps, why the thumbnail size is bounded (16..512 here), and why real systems validate pixel DIMENSIONS at upload time (the decompression-bomb guard: a 20 KB PNG can decode to a 20,000×20,000 bitmap and a gigabyte of heap). Second: RE-ENCODING IS THE EXIF STRIP. A JPEG straight off a camera carries EXIF — camera model, timestamp, and sometimes GPS coordinates; the classic privacy leak is a "resized" avatar that copied the metadata along with the pixels. A decode/encode round trip does not carry metadata over by default: the thumbnail this endpoint produces is fresh PNG pixels with no EXIF to leak (Thumbnailator can explicitly copy EXIF when wanted — orientation being the legitimate reason — and the default remains strip, which is the avatar-safe choice). Deriveds live in the SAME store as originals, which makes the provenance chain inspectable — list the workbench after making a thumbnail and the derived row sits beside its source, labeled.""")),
                section("path-traversal-and-safe-paths", "Path traversal and safe paths",
                        "The ../ anatomy, its percent-encoded cousins, canonicalization — and the two-layer defense: names never become paths, ids are never client-chosen.",
                        text("trav:anatomy", """
                                PATH TRAVERSAL is the attack where a client-supplied filename escapes the directory you meant to confine it to: upload with filename \"../../etc/cron.d/evil\" against a naive store that does root.resolve(filename), and the write lands outside the store — or on the download side, GET /files/..%2F..%2Fsecrets reads something it never should. The ../ is the anatomy; the backslash ..\\ is its Windows twin (both separators travel over HTTP, and a store that strips only / is one portability bug from compromise); and the PERCENT-ENCODED variants — ..%2F, %2e%2e%2f — are the same attack arriving pre-obfuscated for servers that decode-then-use. The canonical defense is CANONICALIZATION plus CONTAINMENT: resolve the path, normalize it (collapse the ../), and verify the result is still INSIDE the store's root — Java's Path.toRealPath() against root.toRealPath() with startsWith is the textbook check, and it is the right answer when a client-supplied name must genuinely become a filesystem path."""),
                        text("trav:two-layers", """
                                THIS STORE plays a stronger game: the client's filename NEVER becomes a path at all. Bytes land under a minted UUID the store generated; the filename is sanitized into DISPLAY DATA (Content-Disposition, JSON listings) and nothing else — there is no path to traverse because no client string ever reaches Path.resolve on the write side. Layer two guards the READ side: an id off the wire is validated against a strict UUID pattern before the adapter touches the filesystem — ../../pom is refused at the gate as a malformed id, never discovered by the filesystem. The sanitizer still earns its keep on the display path: decode once (defusing ..%2F..%2F spellings), strip to the basename (both separators), reject a residue that is empty or pure traversal, drop control characters (a CRLF in a filename is a header-injection attempt when that name lands in Content-Disposition — same class of bug as topic 25's log-injection lesson). Belt and suspenders, and each layer testable in isolation."""),
                        code("trav:sanitizer", "java",
                                "guide/domain/files/SanitizedFilename.java — decode once, basename-strip, reject the pure-traversal residue, strip controls",
                                "server/src/main/java/guide/domain/files/SanitizedFilename.java", """
                                public static Optional<String> sanitize(String raw) {
                                    if (raw == null || raw.isBlank()) {
                                        return Optional.empty();
                                    }
                                    String decoded = decodeOnce(raw.trim());
                                    String basename = stripToBasename(decoded);
                                    if (basename.isBlank() || basename.equals(".") || basename.equals("..")) {
                                        return Optional.empty();
                                    }
                                    return Optional.of(stripControlChars(basename));
                                }
                                """),
                        code("trav:id-gate", "java",
                                "guide/data/files/DiskFileStore.java — the read-side gate: an id that is not a minted UUID is unknown, NEVER resolved",
                                "server/src/main/java/guide/data/files/DiskFileStore.java", """
                                private static final Pattern VALID_ID = Pattern.compile(
                                        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

                                // …
                                @Override
                                public Optional<FileRecord> find(String id) {
                                    if (!VALID_ID.matcher(id).matches()) {
                                        return Optional.empty(); // malformed id = unknown file; NEVER resolved as a path
                                    }
                                """),
                        text("trav:tests", """
                                The battery is in the suite and the shapes are worth naming because they generalize: \"../secrets.txt\" sanitizes to basename \"secrets.txt\" (components gone, honest remainder kept); \"/etc/passwd\" becomes \"passwd\"; the percent-encoded \"..%2F..%2Fetc%2Fpasswd\" decodes and then strips to the same \"passwd\"; and the names that are NOTHING BUT traversal — \"..\", \"../\", \"..\\\\..\\\\\", the encoded \"%2e%2e%2f\" — reject outright, because no honest display name remains. The rule under all of it, the one to carry past this topic: VALIDATE BEFORE YOU RESOLVE, and where the design allows, never resolve client strings at all — an opaque server-minted id as the only path-bearing key (UUIDs here, ULIDs and content hashes in the wild) deletes the attack class instead of fighting it. Object-storage backends make the same move: S3 keys are strings, but the discipline of server-minted keys is what keeps a PUT from overwriting another tenant's prefix.""")),
                section("terminal-companion", "Terminal companion",
                        "The curl tour: upload with -F, watch the headers, dance the 304, slice with Range, refuse with 416, generate with pacing.",
                        text("term:tour", """
                                Everything on this page is plain HTTP, which means curl can drive the whole story. Upload with -F (curl builds the multipart envelope), read the headers with -D - while discarding the body with -o /dev/null, then push each button the browser pushed by hand: the 304 dance (capture the ETag, send it back as If-None-Match), the slice (Range with a bounded, then a suffix, then an impossible range), the derived thumbnail, and the generated stream with pacing you can watch. Replace {id} with a real id from the upload's answer."""),
                        code("term:upload", "bash",
                                "upload, list, and the honest answer — try renaming an .html to .png and watch the sniffed type beat the claim",
                                null, """
                                # upload (curl builds the multipart envelope; the server streams it to disk)
                                curl -s -F "file=@pom.xml" http://localhost:8080/api/demo/files/upload
                                curl -s -F "file=@photo.png;type=text/html" http://localhost:8080/api/demo/files/upload

                                # everything stored, originals and deriveds together
                                curl -s http://localhost:8080/api/demo/files
                                """),
                        code("term:download", "bash",
                                "the download's headers, then the 304 dance — validators first, ranges next",
                                null, """
                                # headers only: ETag (the sha256), Accept-Ranges, exact Content-Length, disposition
                                curl -s -D - -o /dev/null http://localhost:8080/api/demo/files/{id}

                                # capture the etag, send it back — 304, no body, the round trip that saves the bytes
                                ETAG=$(curl -s -D - -o /dev/null http://localhost:8080/api/demo/files/{id} | awk 'tolower($1)=="etag:"{print $2}' | tr -d '\\r')
                                curl -s -D - -o /dev/null -H "If-None-Match: $ETAG" http://localhost:8080/api/demo/files/{id}

                                # force the save-as dialog spelling (RFC 6266 both parameters)
                                curl -s -D - -o /dev/null "http://localhost:8080/api/demo/files/{id}?disposition=attachment"
                                """),
                        code("term:ranges", "bash",
                                "three ranges: bounded 206, suffix 206, impossible 416 — then the slice saved to disk",
                                null, """
                                # bounded: exactly bytes 0-99 (100 bytes), Content-Range says the total
                                curl -s -D - -H "Range: bytes=0-99" -o slice.bin http://localhost:8080/api/demo/files/{id}

                                # suffix: the last 50 bytes, whatever the total is
                                curl -s -D - -H "Range: bytes=-50" -o /dev/null http://localhost:8080/api/demo/files/{id}

                                # unsatisfiable: past EOF — 416 with Content-Range: bytes */<size>
                                curl -s -D - -o /dev/null -H "Range: bytes=99999999-" http://localhost:8080/api/demo/files/{id}
                                """),
                        code("term:derived", "bash",
                                "the derived variant, then the paced generator — -N keeps curl from buffering, so the delay is visible",
                                null, """
                                # one thumbnail variant, stored as its own record with derivedFrom provenance
                                curl -s -X POST "http://localhost:8080/api/demo/files/{id}/thumbnail?size=64"
                                curl -s -o thumb.png http://localhost:8080/api/demo/files/{thumbId}

                                # the generated stream: 200 chunks of 4KB at 100ms — watch them tick in
                                curl -s -N "http://localhost:8080/api/demo/files/generated/819200?delayMs=100" | xxd | head -20

                                # and the cap's typed answer (a payload past 10MB)
                                head -c 10485761 /dev/zero > big.bin
                                curl -s -F "file=@big.bin" http://localhost:8080/api/demo/files/upload
                                """)));
    }
}
