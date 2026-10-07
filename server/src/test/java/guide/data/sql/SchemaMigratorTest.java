package guide.data.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Failed;
import guide.core.result.Success;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SchemaMigrator against REAL PostgreSQL (Testcontainers; postgres:17, the
 * version the compose stack runs — one dialect is a project decision, no H2
 * anywhere). Docker is REQUIRED for this class: with no reachable Docker
 * environment the Testcontainers extension fails every test loudly (that is
 * deliberate — silently skipping would fake a green suite over zero
 * coverage).
 *
 * Contract under test: an up-to-date database re-migrates as a recorded
 * no-op (Flyway's schema history IS the idempotence), appliedVersions reads
 * the history back, freshInfo summarizes it, and an UNREACHABLE PostgreSQL
 * classifies as the typed UNAVAILABLE failure — never an exception, never
 * INTERNAL: "dependency down" and "our script broke" are different facts.
 *
 * The migration set is SHARED across the data topics (V1 posts foundation,
 * V2-V4 sql-schema, V10-V12 jdbc-sql), so the version assertions track the
 * classpath's real V<n>__*.sql list instead of pinning exact versions —
 * updated with topic 16, and safe for the next topic's migrations too.
 */
@Testcontainers
class SchemaMigratorTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    private static SchemaMigrator migrator;

    @BeforeAll
    static void buildMigrator() {
        migrator = new SchemaMigrator(dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    void migratingAnUpToDateDatabaseIsARecordedNoOp() {
        // Order-independent: whatever earlier tests did, this call leaves the
        // schema fully migrated, and the NEXT one must execute zero migrations.
        assertTrue(switch (migrator.migrate()) {
            case Success<SchemaMigrator.MigrationSummary> ok -> true;
            case Failed<SchemaMigrator.MigrationSummary> failed -> false;
        }, "migration against a live PG succeeds");

        int secondRun = switch (migrator.migrate()) {
            case Success<SchemaMigrator.MigrationSummary> ok -> ok.value().migrationsApplied();
            case Failed<SchemaMigrator.MigrationSummary> failed -> Assertions.fail(
                    "re-running is recorded, not an error: " + failed.failure().message());
        };
        assertEquals(0, secondRun,
                "the second migrate() executes nothing — schema history is complete");
    }

    @Test
    void appliedVersionsRecordTheHistory() {
        assertTrue(switch (migrator.migrate()) {
            case Success<SchemaMigrator.MigrationSummary> ok -> true;
            case Failed<SchemaMigrator.MigrationSummary> failed -> false;
        });

        List<String> versions = switch (migrator.appliedVersions()) {
            case Success<List<String>> ok -> ok.value();
            case Failed<List<String>> failed -> Assertions.fail(
                    "the history read failed: " + failed.failure().message());
        };
        // The migration set is SHARED across topics (each data topic owns its
        // own V<n> files), so this test tracks the classpath's real list
        // rather than pinning version "1": every V<n>__*.sql resource must
        // be applied, in numeric order.
        List<String> expected = expectedVersions();
        if (expected.isEmpty()) {
            // Classpath not explorable (jar run) — assert the invariant shape.
            assertEquals("1", versions.getFirst(), "V1 is applied and reported first");
        } else {
            assertEquals(expected, versions,
                    "every V<n> migration on the classpath is applied, numerically ordered");
        }
    }

    @Test
    void freshInfoSummarizesStateInOneSentence() {
        assertTrue(switch (migrator.migrate()) {
            case Success<SchemaMigrator.MigrationSummary> ok -> true;
            case Failed<SchemaMigrator.MigrationSummary> failed -> false;
        });

        String sentence = switch (migrator.freshInfo()) {
            case Success<String> ok -> ok.value();
            case Failed<String> failed -> Assertions.fail(
                    "the summary read failed: " + failed.failure().message());
        };
        List<String> expected = expectedVersions();
        if (expected.isEmpty()) {
            assertTrue(sentence.matches("\\d+ applied, 0 pending: \\d+"),
                    "sentence shape holds even when the classpath is not explorable: " + sentence);
            return;
        }
        String newest = expected.getLast();
        assertTrue(sentence.contains(expected.size() + " applied"),
                "sentence names applied count: " + sentence);
        assertTrue(sentence.contains("0 pending"), "nothing pending after a full run: " + sentence);
        assertTrue(sentence.endsWith(": " + newest),
                "sentence names the newest version: " + sentence);
    }

    /** The V<n>__*.sql files on the classpath, numerically ordered; empty when not explorable. */
    private static List<String> expectedVersions() {
        try {
            java.net.URL location = Thread.currentThread().getContextClassLoader()
                    .getResource("db/migration");
            if (location == null || !"file".equals(location.getProtocol())) {
                return List.of();
            }
            File[] files = new File(location.toURI()).listFiles(
                    (dir, name) -> name.matches("V\\d+__.*\\.sql"));
            if (files == null) {
                return List.of();
            }
            return Arrays.stream(files)
                    .map(file -> file.getName().replaceFirst("^V(\\d+)__.*$", "$1"))
                    .sorted(java.util.Comparator.comparingLong(Long::parseLong))
                    .toList();
        } catch (java.net.URISyntaxException unreadable) {
            return List.of();
        }
    }

    @Test
    void unreachablePostgresIsTheTypedUnavailableFailureNotACrash() {
        // Port 1: nothing listens there — connection refused without a timeout wait.
        SchemaMigrator dead = new SchemaMigrator(
                dataSource("jdbc:postgresql://127.0.0.1:1/guide", "guide", "guide"));

        Failure migration = switch (dead.migrate()) {
            case Success<SchemaMigrator.MigrationSummary> ok ->
                Assertions.fail("an unreachable DB is a failure value, not a success");
            case Failed<SchemaMigrator.MigrationSummary> failed -> failed.failure();
        };
        assertEquals(FailureKind.UNAVAILABLE, migration.kind(),
                "dependency down maps to the taxonomy's unavailable kind (503 on the wire)");
        assertTrue(migration.message().toLowerCase().contains("unreachable"),
                "message says what is wrong: " + migration.message());

        Failure history = switch (dead.appliedVersions()) {
            case Success<List<String>> ok ->
                Assertions.fail("reading history off a dead DB cannot succeed");
            case Failed<List<String>> failed -> failed.failure();
        };
        assertEquals(FailureKind.UNAVAILABLE, history.kind(),
                "every method classifies the same outage the same way");
    }

    private static DataSource dataSource(String url, String username, String password) {
        return new DriverManagerDataSource(url, username, password);
    }
}
