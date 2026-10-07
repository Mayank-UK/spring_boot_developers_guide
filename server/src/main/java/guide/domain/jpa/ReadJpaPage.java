package guide.domain.jpa;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.posts.PostStatus;
import java.util.Map;
import java.util.Objects;

/**
 * The pagination probe's use case: bounds the page request, parses the
 * optional status filter from its wire word, and returns the page report —
 * metadata the client can trust plus the count-query cost the section
 * teaches. page defaults to 0, size to 10 (bounds 1..50).
 */
public final class ReadJpaPage {

    public static final int DEFAULT_SIZE = 10;

    private final JpaProbePort port;

    public ReadJpaPage(JpaProbePort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    /** statusWire and projectionWire optional; class projection spells "class". */
    public JpaProbePort.PageReport read(Integer page, Integer size, String statusWire, String projectionWire) {
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 0 || resolvedPage > 99) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "page must be between 0 and 99", Map.of("parameter", "page")));
        }
        if (resolvedSize < 1 || resolvedSize > 50) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "size must be between 1 and 50", Map.of("parameter", "size")));
        }
        PostStatus status = null;
        if (statusWire != null && !statusWire.isBlank()) {
            try {
                status = PostStatus.fromWire(statusWire.trim().toLowerCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException bad) {
                throw AppException.of(Failure.of(FailureKind.VALIDATION,
                        bad.getMessage(), Map.of("parameter", "status")));
            }
        }
        boolean classProjection = projectionWire != null
                && projectionWire.trim().equalsIgnoreCase("class");
        return port.page(resolvedPage, resolvedSize, status, classProjection);
    }
}
