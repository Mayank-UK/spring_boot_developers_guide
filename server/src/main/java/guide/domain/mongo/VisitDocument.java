package guide.domain.mongo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One page-view event — the guide_visits collection's document, as a PLAIN
 * record with ZERO MongoDB imports. That absence is the layering lesson:
 * the domain owns the shape and its rules; the data/mongo adapter owns the
 * mapping to BSON (@Document/@Field live in VisitDoc, not here), so the
 * storage technology remains a swappable detail the domain never sees.
 *
 * Why a document and not a posts-style table: a visit is EVENT-SHAPED — it
 * is written once, never updated, never joined across relationships, and
 * read exclusively through aggregate questions ("how many", "which topics
 * most", "how long on average"). The read pattern, not fashion, picks the
 * store (the document-thinking section makes the honest case).
 *
 * The compact constructor is the schema MongoDB will not enforce: MongoDB
 * validates types loosely and rules not at all by default, so every
 * invariant the API promises lives HERE, in front of every door that can
 * build a document (the same defense-in-depth stance Post's constructor
 * takes where PostgreSQL's CHECKs provide the second wall).
 */
public record VisitDocument(String id, String topicId, String path, String visitorHash,
        long durationMs, Instant occurredAt, String locale) {

    /** Upper bound on one visit's duration: a day, past which the number is noise, not signal. */
    public static final long MAX_DURATION_MS = 24L * 60 * 60 * 1000;

    /** Topic ids are the guide's kebab-case slugs (e.g. "jdbc-sql"); anything else is a bug upstream. */
    private static final String TOPIC_ID_PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";

    /** Locales are BCP-47-shaped language tags this app actually serves: "en", "de-DE". */
    private static final String LOCALE_PATTERN = "^[a-z]{2}(-[A-Z]{2})?$";

    /** Path problems as reusable predicates — the same rules VisitDraft enforces at birth. */
    public static String topicIdProblem(String topicId) {
        if (topicId == null || !topicId.matches(TOPIC_ID_PATTERN)) {
            return "must be a kebab-case topic slug (e.g. \"jdbc-sql\")";
        }
        return null;
    }

    public static String pathProblem(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/") || path.length() > 200) {
            return "must be an absolute path of at most 200 characters";
        }
        return null;
    }

    public static String durationProblem(long durationMs) {
        if (durationMs < 0 || durationMs > MAX_DURATION_MS) {
            return "must be between 0 and " + MAX_DURATION_MS + " ms";
        }
        return null;
    }

    public static String localeProblem(String locale) {
        if (locale == null || !locale.matches(LOCALE_PATTERN)) {
            return "must be a BCP-47-style tag like \"en\" or \"de-DE\"";
        }
        return null;
    }

    public static String visitorHashProblem(String visitorHash) {
        if (visitorHash == null || !visitorHash.matches("^[0-9a-f]{64}$")) {
            return "must be 64 lowercase hex characters (sha-256)";
        }
        return null;
    }

    public VisitDocument {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("visit id must be a non-blank string");
        }
        String problem = topicIdProblem(topicId);
        if (problem != null) {
            throw new IllegalArgumentException("visit topicId " + problem);
        }
        problem = pathProblem(path);
        if (problem != null) {
            throw new IllegalArgumentException("visit path " + problem);
        }
        problem = visitorHashProblem(visitorHash);
        if (problem != null) {
            throw new IllegalArgumentException("visit visitorHash " + problem);
        }
        problem = durationProblem(durationMs);
        if (problem != null) {
            throw new IllegalArgumentException("visit durationMs " + problem);
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        problem = localeProblem(locale);
        if (problem != null) {
            throw new IllegalArgumentException("visit locale " + problem);
        }
    }

    /**
     * The privacy seam: callers hand over a raw visitor token (IP, session
     * id, header) and the store keeps only its sha-256 — pseudonymization at
     * the boundary, so no raw identifier ever reaches persistence. Pure and
     * deterministic, which is exactly what the fake seed needs.
     */
    public static String sha256Hex(String token) {
        Objects.requireNonNull(token, "token");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            // SHA-256 is mandated by the Java platform spec — unreachable.
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
