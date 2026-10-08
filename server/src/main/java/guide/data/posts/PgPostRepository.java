package guide.data.posts;

import guide.domain.posts.PagedPosts;
import guide.domain.posts.Post;
import guide.domain.posts.PostDraft;
import guide.domain.posts.PostFilter;
import guide.domain.posts.PostRepository;
import guide.domain.posts.PostSort;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * PostgreSQL PostRepository ADAPTER — the LIVE implementation behind the same
 * five-method port the in-memory fake answers, so every use case, controller,
 * GraphQL resolver, and test above guide.domain.posts works unchanged (the
 * port/adapter payoff, now demonstrated rather than promised).
 *
 * Teaching-load-bearing choices, all deliberate:
 *
 * - EVERY value travels as a bind parameter. The only string-composed SQL is
 *   STRUCTURE: which fixed filter clause, which whitelisted ORDER BY column
 *   (the column name comes from the PostSort.Field enum, never from the
 *   request — the client's ?sort= was already parsed into that enum or
 *   rejected). There is no third kind of SQL text in this class.
 *
 * - Pagination is TWO statements by design: a COUNT(*) over the same WHERE,
 *   then LIMIT/OFFSET for the slice. The one-statement alternatives
 *   (COUNT(*) OVER() window, or a count-in-CTE) share a subtle flaw: the
 *   total rides RESULT ROWS, so an empty page — a beyond-range ?page=99 —
 *   reports no total at all, drifting from the fake adapter's honest
 *   "0 items, 12 total". Two queries is the shape that cannot lie; topic 17
 *   shows the window version and exactly where it breaks.
 *
 * - Optimistic concurrency is enforced IN the UPDATE's WHERE clause
 *   (version = ?), never by a Java check-then-act: the row lock the statement
 *   takes makes read-decide-write one atomic step, and rows-affected 0 means
 *   the version moved. The follow-up SELECT only CLASSIFIES the loss (404 vs
 *   409) — it guards nothing.
 *
 * - Tags live in a text[] column (V1 migration documents why not jsonb);
 *   ? = ANY(tags) is the exact-match filter, java.sql.Array the mapping.
 *
 * - Identity is minted HERE (UUID.randomUUID), version starts at 1 — same
 *   birth semantics as Post.initial, restated in SQL rather than trusted.
 */
public class PgPostRepository implements PostRepository {

    private static final String SELECT_COLUMNS =
            "id, title, body, status, version, tags, created_at, updated_at";

    private static final String FIND_BY_ID_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM posts WHERE id = ?";

    private static final String INSERT_SQL =
            "INSERT INTO posts (" + SELECT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE posts SET title = ?, body = ?, status = ?, tags = ?, updated_at = ?,"
                    + " version = version + 1"
                    + " WHERE id = ? AND version = ?"
                    + " RETURNING " + SELECT_COLUMNS;

    private static final String DELETE_SQL =
            "DELETE FROM posts WHERE id = ?";

    private static final String SEED_UPSERT_SQL =
            "INSERT INTO posts (" + SELECT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (id) DO NOTHING";

    private static final String CURRENT_VERSION_SQL =
            "SELECT version FROM posts WHERE id = ?";

    /** One row -> one immutable Post; the ONLY place columns become fields. */
    private static final RowMapper<Post> POST_MAPPER = (rs, rowNum) -> new Post(
            rs.getObject("id", UUID.class).toString(),
            rs.getString("title"),
            rs.getString("body"),
            tagsOf(rs.getArray("tags")),
            guide.domain.posts.PostStatus.fromWire(rs.getString("status")),
            rs.getLong("version"),
            rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public PgPostRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PagedPosts list(int page, int size, PostSort sort, PostFilter filter) {
        // Structure is composed (fixed fragments only); values always bind.
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        List<Object> filterArgs = new ArrayList<>();
        if (filter.status() != null) {
            where.append(" AND status = ?");
            filterArgs.add(filter.status().wireName());
        }
        if (filter.tag() != null) {
            where.append(" AND ? = ANY(tags)");
            filterArgs.add(filter.tag());
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM posts" + where, Long.class, filterArgs.toArray());
        long totalElements = total == null ? 0 : total;
        List<Post> items = totalElements == 0
                ? List.of()
                : jdbc.query("SELECT " + SELECT_COLUMNS + " FROM posts" + where
                                + " ORDER BY " + orderBy(sort) + " LIMIT ? OFFSET ?",
                        POST_MAPPER,
                        withPagination(filterArgs, size, (long) page * size));
        return PagedPosts.of(items, page, size, totalElements);
    }

    /** Filter args first, then the LIMIT/OFFSET pair — one array for the page query. */
    private static Object[] withPagination(List<Object> filterArgs, int size, long offset) {
        Object[] args = new Object[filterArgs.size() + 2];
        for (int i = 0; i < filterArgs.size(); i++) {
            args[i] = filterArgs.get(i);
        }
        args[filterArgs.size()] = size;
        args[filterArgs.size() + 1] = offset;
        return args;
    }

    @Override
    public Optional<Post> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException notAUuid) {
            // A hostile id is simply not found — never a 500 from the driver.
            return Optional.empty();
        }
        return jdbc.query(FIND_BY_ID_SQL, POST_MAPPER, uuid).stream().findFirst();
    }

    @Override
    public Post create(PostDraft draft) {
        // timestamptz stores MICROseconds and PG ROUNDS the nanos we send —
        // mint at micro resolution so what we return is exactly what is stored.
        Post born = Post.initial(UUID.randomUUID().toString(), draft,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        jdbc.update(INSERT_SQL, ps -> bindPost(ps, born));
        return born;
    }

    @Override
    public Update update(String id, long expectedVersion, PostDraft draft) {
        UUID uuid = parseOrNull(id);
        if (uuid == null) {
            return new NotFound();
        }
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        List<Post> updated = jdbc.query(UPDATE_SQL,
                ps -> {
                    ps.setString(1, draft.title());
                    ps.setString(2, draft.body());
                    ps.setString(3, draft.status().wireName());
                    ps.setArray(4, ps.getConnection()
                            .createArrayOf("text", draft.tags().toArray()));
                    ps.setObject(5, now.atOffset(ZoneOffset.UTC));
                    ps.setObject(6, uuid);
                    ps.setLong(7, expectedVersion);
                },
                POST_MAPPER);
        if (!updated.isEmpty()) {
            return new Updated(updated.getFirst());
        }
        // Zero rows: the version moved (or the post is gone). Classify only.
        return jdbc.query(CURRENT_VERSION_SQL,
                        (rs, rowNum) -> rs.getLong("version"), uuid).stream()
                .findFirst()
                .<Update>map(StaleVersion::new)
                .orElseGet(NotFound::new);
    }

    @Override
    public boolean delete(String id) {
        UUID uuid = parseOrNull(id);
        return uuid != null && jdbc.update(DELETE_SQL, uuid) > 0;
    }

    /**
     * Idempotent seed replay: every SeedPosts row upserted with ON CONFLICT (id)
     * DO NOTHING — a fresh database gains the twelve canonical posts, a
     * re-activation over an existing one changes nothing (live-mode edits
     * survive; seeding never clobbers). The lazy live activation calls this
     * after SchemaMigrator.migrate(); safe to call twice, safe under races
     * (the conflict target is the primary key).
     */
    public void ensureSeeded() {
        List<Post> seed = SeedPosts.list();
        jdbc.batchUpdate(SEED_UPSERT_SQL, seed, seed.size(), (ps, post) -> bindPost(ps, post));
    }

    // ---- SQL fragments and row plumbing ----

    /**
     * The whitelisted ORDER BY: column names come ONLY from the typed enum
     * (PostSort.parse already rejected anything else), and the id tie-break
     * flips WITH the direction — exactly what the in-memory comparator's
     * reversed() does — so fake and live paginate identically.
     *
     * COLLATE "C" is load-bearing, not decoration: the server's default
     * collation orders like a dictionary (spaces and punctuation ignored at
     * primary strength — "The uniform" sorts BEFORE "The URL"), while the
 * port's comparator is Java's case-insensitive CODE-POINT order (a space
     * sorts before letters). Without the pin, title order silently drifts
     * between the fake and the live adapter — the parity trap topic 16
     * teaches with this exact line.
     */
    private static String orderBy(PostSort sort) {
        String column = switch (sort.field()) {
            case TITLE -> "LOWER(title) COLLATE \"C\"";
            case CREATED_AT -> "created_at";
            case UPDATED_AT -> "updated_at";
        };
        String direction = sort.descending() ? "DESC" : "ASC";
        return column + " " + direction + ", id " + direction;
    }

    /** Binds one whole post (identity, content, version, timestamps, tags array). */
    private static void bindPost(PreparedStatement ps, Post post) throws SQLException {
        ps.setObject(1, UUID.fromString(post.id()));
        ps.setString(2, post.title());
        ps.setString(3, post.body());
        ps.setString(4, post.status().wireName());
        ps.setLong(5, post.version());
        ps.setArray(6, ps.getConnection().createArrayOf("text", post.tags().toArray()));
        ps.setObject(7, post.createdAt().atOffset(ZoneOffset.UTC));
        ps.setObject(8, post.updatedAt().atOffset(ZoneOffset.UTC));
    }

    private static java.sql.Array tagsArray(List<String> tags) throws SQLException {
        // The bind path used by update(); create()/seed use bindPost's setter
        // (it owns a PreparedStatement, hence a Connection to build arrays).
        throw new UnsupportedOperationException("replaced inline; see update()");
    }

    private static List<String> tagsOf(java.sql.Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return List.of(values);
    }

    private static UUID parseOrNull(String id) {
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
