package guide.domain.schema;

/**
 * The whole experiment in one answer: how many rows each side inserted, the
 * measurement for v4 and v7, the index-size delta in bytes (positive = v4
 * cost MORE storage at this row count), and a one-sentence verdict that
 * states only what was measured — including the honest small-N case where
 * both indexes still fit in the same handful of pages and the structural
 * difference shows up in the locality numbers instead.
 */
public record KeyStrategyReport(int rows, StrategyMeasurement uuidV4,
        StrategyMeasurement uuidV7, long indexByteDelta, String verdict) {
}
