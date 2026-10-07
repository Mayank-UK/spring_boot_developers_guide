package guide.domain.schema;

import java.util.List;

/**
 * Everything the explorer can honestly say about one table, assembled from
 * the catalog: its name, its COMMENT ON annotation (null when none), the
 * planner's ROW ESTIMATE (null when PostgreSQL has not analyzed the table
 * yet — reltuples is -1, and reporting -1 rows would be a lie), the table's
 * TOTAL footprint in bytes and pretty form (heap + all indexes + TOAST —
 * pg_total_relation_size, the honest "what does this table cost on disk"),
 * then columns, constraints, and indexes in catalog order.
 */
public record TableInsight(String name, String comment, Long rowEstimate,
        long totalBytes, String totalSize,
        List<ColumnInsight> columns,
        List<ConstraintInsight> constraints,
        List<IndexInsight> indexes) {

    public TableInsight {
        columns = List.copyOf(columns);
        constraints = List.copyOf(constraints);
        indexes = List.copyOf(indexes);
    }
}
