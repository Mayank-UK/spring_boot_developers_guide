package guide.data.files;

import guide.domain.files.FileRecord;
import guide.domain.files.FileStore;
import guide.domain.files.IOConsumer;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.ObjectMapper;

/**
 * The FileStore port's disk adapter: one directory, two files per record —
 * {id}.bin for the bytes, {id}.json for the metadata sidecar. The design
 * keeps the topic's persistence lesson honest: BOTH halves live on disk,
 * so a restart finds every upload intact. (The alternative this topic
 * teaches against-by-default — java.io.tmpdir — is ephemeral by OS
 * contract: fine for scratch uploads, a quiet data-loss bug for anything
 * a user expects to come back to. A production store splits the halves:
 * bytes to object storage, metadata to the database, and gains the
 * transactionality this two-file shape deliberately does not pretend to
 * have.)
 *
 * The load-bearing rule is the ID GATE: an id off the wire is validated
 * against a strict UUID pattern BEFORE it ever touches {@link Path#resolve}
 * — a lookup for {@code ../../pom} is refused at the gate, not discovered
 * by the filesystem. Defense in depth with the filename sanitizer: names
 * are display data, ids are the only thing that becomes a path, and ids
 * are ours, never the client's.
 */
public final class DiskFileStore implements FileStore {

    /** Ids are UUIDs we minted; nothing else is ever resolved as a path. */
    private static final Pattern VALID_ID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final Path root;
    private final ObjectMapper mapper;

    public DiskFileStore(Path root, ObjectMapper mapper) throws IOException {
        this.root = root;
        this.mapper = mapper;
        Files.createDirectories(root); // runtime-created: the store bootstraps its own directory
    }

    @Override
    public FileRecord save(FileRecord record, InputStream content) throws IOException {
        Path target = bytesAt(record.id());
        try {
            Files.copy(content, target); // platform copy: 8 KB buffer, never more of the stream in memory
            return record.withSize(Files.size(target));
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(target); // a failed copy leaves no orphan bytes
            throw failure;
        }
    }

    @Override
    public FileRecord produce(FileRecord record, IOConsumer<OutputStream> producer) throws IOException {
        Path target = bytesAt(record.id());
        try (OutputStream output = Files.newOutputStream(target)) {
            producer.accept(output);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(target);
            throw failure;
        }
        return record.withSize(Files.size(target));
    }

    @Override
    public FileRecord saveMetadata(FileRecord record) throws IOException {
        Path sidecar = metadataAt(record.id());
        Path temp = sidecar.resolveSibling(record.id() + ".json.tmp");
        Files.write(temp, mapper.writeValueAsBytes(record));
        Files.move(temp, sidecar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return record;
    }

    @Override
    public void delete(String id) throws IOException {
        if (VALID_ID.matcher(id).matches()) {
            Files.deleteIfExists(bytesAt(id));
            Files.deleteIfExists(metadataAt(id));
        }
    }

    @Override
    public Optional<FileRecord> find(String id) {
        if (!VALID_ID.matcher(id).matches()) {
            return Optional.empty(); // malformed id = unknown file; NEVER resolved as a path
        }
        Path sidecar = metadataAt(id);
        if (!Files.isRegularFile(sidecar)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(sidecar.toFile(), FileRecord.class));
        } catch (RuntimeException unreadable) {
            return Optional.empty(); // a corrupt sidecar reports absent — honest, not fatal
        }
    }

    @Override
    public List<FileRecord> list() {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> readRecord(path).orElse(null))
                    .filter(record -> record != null)
                    .sorted(Comparator.comparing(FileRecord::uploadedAt).reversed())
                    .toList();
        } catch (IOException failure) {
            return List.of();
        }
    }

    @Override
    public InputStream open(String id) throws IOException {
        return Files.newInputStream(bytesAt(id)); // a FRESH handle per call — the defensive-copy rule
    }

    @Override
    public InputStream openRange(String id, long offset, long length) throws IOException {
        InputStream source = open(id);
        long skipped = 0;
        while (skipped < offset) { // skip() may short-skip; loop until truly positioned
            long moved = source.skip(offset - skipped);
            if (moved <= 0) {
                source.close();
                throw new IOException("could not position the stream at offset " + offset);
            }
            skipped += moved;
        }
        return new LimitedInputStream(source, length);
    }

    private Optional<FileRecord> readRecord(Path sidecar) {
        try {
            return Optional.of(mapper.readValue(sidecar.toFile(), FileRecord.class));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private Path bytesAt(String id) {
        return root.resolve(id + ".bin");
    }

    private Path metadataAt(String id) {
        return root.resolve(id + ".json");
    }

    /** Reads at most {@code limit} bytes, then reports EOF — the range-slice stream. */
    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        LimitedInputStream(InputStream source, long limit) {
            super(source);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int readable = (int) Math.min(length, remaining);
            int read = super.read(buffer, offset, readable);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }

    /** Sanity for wiring: the id gate pattern is the same UUIDs {@link UUID#randomUUID} mints. */
    static boolean isValidId(String id) {
        return VALID_ID.matcher(id).matches();
    }
}
