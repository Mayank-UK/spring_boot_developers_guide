package guide.config;

import guide.data.files.DiskFileStore;
import guide.domain.files.CreateThumbnail;
import guide.domain.files.DownloadFile;
import guide.domain.files.FileStore;
import guide.domain.files.UploadFile;
import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Topic 26 (files-media) wiring — the files area's composition root. THREE
 * decisions live here, all of them the topic's teaching:
 *
 * 1. THE STORE'S HOME: a repo-local data/files directory (created at
 *    runtime by the adapter, gitignored — uploads are runtime data, not
 *    source). The property default keeps uploads PERSISTENT across
 *    restarts, which is what a user's mental model of "I uploaded that"
 *    demands; the ephemeral alternative (java.io.tmpdir) is one property
 *    away and is taught as the scratch-upload choice. Tests point the
 *    property at a throwaway directory — same seam, different policy.
 *
 * 2. TWO CEILINGS, DELIBERATELY APART: the app's TYPED cap (10 MB, the
 *    UploadFile guard → the 413 envelope with evidence) sits BELOW the
 *    servlet container's multipart limit (cap + 1 MB, the
 *    MultipartConfigElement below). Order matters: the servlet parses the
 *    multipart envelope first, so its ceiling must never fire before the
 *    domain's guard does — otherwise oversized uploads die as the
 *    container's generic MaxUploadSizeExceededException (a 500 through
 *    the global handler) instead of the typed 413. Declaring the bean
 *    here is also what replaces Boot's property-driven
 *    MultipartAutoConfiguration (it backs off on an existing
 *    MultipartConfigElement) — the composition-root way to configure
 *    multipart without touching application.yml.
 *
 * 3. fileSizeThreshold 0: every part spools to a temp FILE during
 *    parsing, so a MultipartFile's getInputStream() is always a file
 *    stream — the in-memory tier of the servlet's threshold ladder is
 *    switched off entirely, and no upload of any size is ever a heap
 *    buffer.
 */
@Configuration
public class FilesDemoConfig {

    /** The app's typed cap: 10 MB (the property default), overridable twelve-factor style. */
    public static final long DEFAULT_MAX_UPLOAD_BYTES = 10485760;

    /** Headroom above the typed cap so the servlet ceiling never pre-empts the domain guard. */
    static final long SERVLET_HEADROOM_BYTES = 1024 * 1024;

    @Bean
    FileStore fileStore(
            @Value("${guide.demo.files.storage-dir:data/files}") String storageDir,
            ObjectMapper mapper) throws IOException {
        return new DiskFileStore(Path.of(storageDir), mapper);
    }

    /**
     * The container's multipart ceiling — ABOVE the domain cap on purpose
     * (see class doc point 2), so raising the typed cap keeps the same
     * ordering. The request ceiling adds room for the multipart envelope's
     * own overhead; the zero threshold switches off the in-memory tier.
     */
    @Bean
    MultipartConfigElement multipartConfigElement(
            @Value("${guide.demo.files.max-upload-bytes:10485760}") long maxUploadBytes) {
        long ceiling = maxUploadBytes + SERVLET_HEADROOM_BYTES;
        long requestCeiling = ceiling + 64 * 1024;
        return new MultipartConfigElement(null, ceiling, requestCeiling, 0);
    }

    @Bean
    UploadFile uploadFile(FileStore store,
            @Value("${guide.demo.files.max-upload-bytes:" + DEFAULT_MAX_UPLOAD_BYTES + "}") long maxUploadBytes) {
        return new UploadFile(store, maxUploadBytes);
    }

    @Bean
    DownloadFile downloadFile(FileStore store) {
        return new DownloadFile(store);
    }

    @Bean
    CreateThumbnail createThumbnail(FileStore store) {
        return new CreateThumbnail(store);
    }
}
