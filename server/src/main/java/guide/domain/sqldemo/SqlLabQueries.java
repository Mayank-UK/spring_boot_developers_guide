package guide.domain.sqldemo;

import java.util.List;
import java.util.Map;

/**
 * The SQL lab PORT (topic 17, demo db:sql-lab): named queries over the V11
 * lab dataset. Every result carries its own SQL — the teaching point is
 * reading the query BESIDE its result, so the adapter returns the exact text
 * that ran. Rows are plain string-keyed maps (dates pre-cast to text in the
 * SQL): the lab's shapes differ per query, and inventing a record per query
 * would bury the rows under plumbing the demo does not need.
 */
public interface SqlLabQueries {

    /** One named query's execution: which, what it teaches, its SQL, and its rows. */
    record LabResult(LabQueryName name, String title, String description, String sql,
            List<Map<String, Object>> rows) {

        public LabResult {
            rows = List.copyOf(rows);
        }
    }

    /** Runs one whitelisted named query — the only entry point, by design. */
    LabResult run(LabQueryName name);
}
