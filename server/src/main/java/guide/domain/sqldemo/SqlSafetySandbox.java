package guide.domain.sqldemo;

import java.util.List;

/**
 * The SQL-injection sandbox PORT (topic 17, demo db:sql-safety): one table,
 * one lookup, implemented twice — once the right way (bind parameters) and
 * once the deliberately-wrong way (string concatenation) — so the same
 * hostile payload can be run through BOTH and the outcomes compared.
 *
 * Blast radius is capped at the port's contract: every lookup returns ONLY
 * sandbox rows (username, role, secret) and is read-only by shape — no
 * method here can write anything, which is the least-privilege half of the
 * lesson. The adapter's job is to make even the VULNERABLE path obey that
 * cap: see PgSqlSafetySandbox for the restricted-role trick that keeps a
 * UNION-escape from reading anything beyond the sandbox table.
 */
public interface SqlSafetySandbox {

    /** One row of the sandbox table — the 'secret' is fake and ON PURPOSE: it is the loot. */
    record SandboxRow(String username, String role, String secret) {
    }

    /**
     * What one lookup path did with the payload: the SQL text the database
     * actually saw (structure vs value, side by side), the rows that came
     * back, a one-sentence note, and — vulnerable path only — the containment
     * error when an escape attempt hit the role wall (null when none).
     */
    record LookupOutcome(String sqlShown, List<SandboxRow> rows, String note, String containmentError) {

        public LookupOutcome {
            rows = List.copyOf(rows);
        }

        public int rowCount() {
            return rows.size();
        }
    }

    /** The payload arrives as a VALUE compared against usernames — it can never be structure. */
    LookupOutcome lookupParameterized(String payload);

    /**
     * teaching-exception: the payload is concatenated into SQL text — REAL
     * string concatenation, so the attack is real; only the blast radius is
     * capped (read-only column set, sandbox-table-scoped role).
     */
    LookupOutcome lookupVulnerable(String payload);
}
