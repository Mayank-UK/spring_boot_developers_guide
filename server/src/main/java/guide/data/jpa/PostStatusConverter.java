package guide.data.jpa;

import guide.domain.posts.PostStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Why the status column is NOT @Enumerated(EnumType.STRING): the posts table
 * (V1) stores the WIRE words — 'draft'/'published', lowercase — under a CHECK
 * constraint that allows nothing else, while the enum's Java constants are
 * DRAFT/PUBLISHED. @Enumerated(EnumType.STRING) would write the constant
 * names and the database would reject every insert; @Enumerated(ORDINAL)
 * would store 0/1 and silently reorder the world if anyone ever re-sequenced
 * the enum. A three-line converter keeps the enum and the wire spelling
 * aligned — the same mapping PgPostRepository's RowMapper made by hand
 * (PostStatus.fromWire), stated once for the whole entity.
 */
@Converter(autoApply = false)
public final class PostStatusConverter implements AttributeConverter<PostStatus, String> {

    @Override
    public String convertToDatabaseColumn(PostStatus attribute) {
        return attribute == null ? null : attribute.wireName();
    }

    @Override
    public PostStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : PostStatus.fromWire(dbData);
    }
}
