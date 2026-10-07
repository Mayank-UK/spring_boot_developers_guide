package guide.data.jpa;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

/**
 * The text[] bridge. The posts table stores tags as a PostgreSQL array
 * (topic 16's choice: exact membership, no join table, no marshalling) — and
 * since Hibernate 6, the ORM maps JAVA ARRAYS to SQL arrays natively on
 * PostgreSQL, but knows nothing about {@code List<String>}. This converter
 * sits between the two: the entity field is a List (the domain shape
 * everywhere else in this codebase), and the DATABASE REPRESENTATION is
 * {@code String[]} — a type Hibernate maps to text[] on its own. The
 * converter is the only place the two shapes meet.
 *
 * Null and empty are deliberately conflated: the column is NOT NULL DEFAULT
 * '{}', so an absent tag list persists as an empty array and an empty array
 * reads back as an empty list — never null on either side of the bridge.
 */
@Converter
public final class StringListConverter implements AttributeConverter<List<String>, String[]> {

    @Override
    public String[] convertToDatabaseColumn(List<String> attribute) {
        return attribute == null ? new String[0] : attribute.toArray(new String[0]);
    }

    @Override
    public List<String> convertToEntityAttribute(String[] dbData) {
        return dbData == null ? List.of() : List.of(dbData);
    }
}
