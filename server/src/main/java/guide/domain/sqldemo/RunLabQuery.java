package guide.domain.sqldemo;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.domain.sqldemo.SqlLabQueries.LabResult;
import java.util.Arrays;
import java.util.List;

/**
 * Topic 17's SQL lab use case (demo db:sql-lab): parse the wire name against
 * the whitelist — a typed validation failure for anything unknown — and run
 * the one named query it names. The catalog call exists so a client can list
 * what it may ask for before asking.
 */
public final class RunLabQuery {

    private final SqlLabQueries lab;

    public RunLabQuery(SqlLabQueries lab) {
        this.lab = lab;
    }

    /** Runs the whitelisted query; unknown names are validation failures, never errors into SQL. */
    public LabResult run(String wireName) {
        LabQueryName name;
        try {
            name = LabQueryName.fromWire(wireName);
        } catch (IllegalArgumentException unknown) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION, unknown.getMessage()));
        }
        return lab.run(name);
    }

    /** The catalog the client may choose from — names, titles, what each teaches. */
    public List<LabQueryName> catalog() {
        return Arrays.asList(LabQueryName.values());
    }
}
