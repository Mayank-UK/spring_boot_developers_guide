package guide.domain.schema;

/**
 * One table constraint, classified: PRIMARY KEY, FOREIGN KEY, UNIQUE, or
 * CHECK, plus pg_get_constraintdef's rendering of the rule — the definition
 * is the authoritative text ("PRIMARY KEY (id)", "CHECK ((status)::text =
 * ANY ((ARRAY['draft'::character varying, ...])::text[]))"), quoted verbatim
 * from the catalog rather than reverse-engineered, because the catalog's own
 * words are the honest source.
 */
public record ConstraintInsight(String name, String kind, String definition) {
}
