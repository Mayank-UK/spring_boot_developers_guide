package guide.data.mongo;

import guide.domain.mongo.VisitDocument;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * The guide_visits collection's MAPPING record — the ONE class in the
 * MongoDB adapter that knows what BSON looks like. @Document names the
 * collection; @Field spells each property's stored key (short, snake-y,
 * mongo-idiomatic — wire names are storage's business, not the domain's);
 * @Id marks the string key stored as _id (an ObjectId hex minted by the
 * adapter, so documents stay addressable by their own id in both adapters).
 *
 * The mapping lives HERE and not on VisitDocument on purpose: the domain
 * record stays free of every org.springframework and org.mongodb import —
 * storage is an adapter concern, and the domain would notice a storage
 * swap exactly never (the layering section quotes this seam in full).
 * Conversion is explicit, field by field, both directions — no reflection
 * magic crossing the seam, nothing accidental leaking either way.
 *
 * @CompoundIndex documents (and creates, via the starter's index builder
 * IF spring.data.mongodb.auto-index-creation is on) the collection's query
 * shape: topic equality + recency. This app creates its indexes
 * PROGRAMMATICALLY at live activation instead (MongoVisitStore.activate —
 * same lazy-once discipline as Flyway), because index creation must not
 * gate boot; the annotation stays as the schema's in-code documentation.
 */
@Document(collection = "guide_visits")
@CompoundIndex(name = "topic_recency_idx", def = "{'topicId': 1, 'occurredAt': -1}")
public record VisitDoc(

        @Id
        String id,

        @Field("topicId")
        String topicId,

        @Field("path")
        String path,

        @Field("visitorHash")
        String visitorHash,

        @Field("durationMs")
        long durationMs,

        @Field("occurredAt")
        Instant occurredAt,

        @Field("locale")
        String locale) {

    /** Domain → BSON. */
    public static VisitDoc from(VisitDocument visit) {
        return new VisitDoc(visit.id(), visit.topicId(), visit.path(), visit.visitorHash(),
                visit.durationMs(), visit.occurredAt(), visit.locale());
    }

    /** BSON → domain. */
    public VisitDocument toDomain() {
        return new VisitDocument(id, topicId, path, visitorHash, durationMs, occurredAt, locale);
    }
}
