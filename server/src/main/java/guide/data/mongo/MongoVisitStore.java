package guide.data.mongo;

import com.mongodb.MongoException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import guide.core.result.Result;
import guide.domain.mongo.LocaleTally;
import guide.domain.mongo.ReadVisitStats;
import guide.domain.mongo.TopicTally;
import guide.domain.mongo.VisitDocument;
import guide.domain.mongo.VisitDraft;
import guide.domain.mongo.VisitStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperationContext;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The LIVE VisitStore adapter: every port method as MongoTemplate work —
 * Criteria for shaped finds and counts, Aggregation pipelines for the
 * $group math. This is the class the queries section quotes pipeline by
 * pipeline: the fluent API is a THIN veneer over server-side JSON stages,
 * and the /explain endpoint serves exactly the Documents these builders
 * produce, so the console can show what the server will run.
 *
 * Connection failures are VALUES, never crashes — the SchemaMigrator
 * pattern, one store over. activate() pings and creates indexes BEFORE the
 * first real query, returning a Failed Result (kind UNAVAILABLE,
 * dependency mongo) when the server cannot be reached, so the lazy
 * activation seam can refuse to flip live without anyone throwing through
 * a boot path. Per-method classification walks the driver's exception
 * family — server-selection timeouts and socket failures are the Mongo
 * spelling of SQLSTATE class 08: dependency-down (503); anything else is
 * our bug (500) and must not masquerade as an outage.
 *
 * Mapping note: aggregation output rows are read as raw Documents and
 * converted explicitly ($group's _id IS the grouped key — reading it by
 * hand says so), because record-property binding through reflection would
 * hide the exact shape the pipeline emits. Explicit over magical at the
 * seam, same stance as VisitDoc's field-by-field conversion.
 */
public final class MongoVisitStore implements VisitStore {

    /** How long a visit lives: 30 days, then the TTL index deletes it (the canonical TTL use case). */
    public static final long TTL_SECONDS = Duration.ofDays(30).toSeconds();

    private final MongoTemplate template;

    public MongoVisitStore(MongoTemplate template) {
        this.template = Objects.requireNonNull(template, "template");
    }

    /**
     * The lazy-activation seam's payload: prove the server answers, then
     * create the collection's indexes (idempotent — ensureIndex maps to
     * createIndex, a no-op for an identically-shaped existing index). ONE
     * place pays the schema bill, on first live use, never at boot.
     */
    public Result<String> activate() {
        try {
            template.getDb().runCommand(new Document("ping", 1));
            IndexOperations indexes = template.indexOps(VisitDoc.class);
            // The query shape's index: topic equality + recency (compound
            // order load-bearing — the same leftmost-prefix rule as SQL's).
            indexes.ensureIndex(new Index()
                    .on("topicId", Sort.Direction.ASC)
                    .on("occurredAt", Sort.Direction.DESC)
                    .named("topic_recency_idx"));
            // The TTL index: Mongo's background sweeper deletes documents whose
            // occurredAt is older than expireAfterSeconds — analytics data that
            // expires itself, no cron, no delete job.
            indexes.ensureIndex(new Index()
                    .on("occurredAt", Sort.Direction.DESC)
                    .expire(Duration.ofSeconds(TTL_SECONDS))
                    .named("occurredAt_ttl_idx"));
            return Result.success(template.getDb().getName());
        } catch (RuntimeException failure) {
            return Result.failed(unavailableOrInternal(
                    "the mongo area could not activate", failure));
        }
    }

    @Override
    public VisitDocument record(VisitDraft draft) {
        // Identity minted by the adapter (ObjectId hex) + the boundary's
        // pseudonymization: the raw visitor token never crosses this line.
        VisitDocument visit = new VisitDocument(
                new ObjectId().toHexString(),
                draft.topicId(), draft.path(),
                VisitDocument.sha256Hex(draft.visitorToken()),
                draft.durationMs(), Instant.now(), draft.locale());
        return guarded(() -> {
            template.insert(VisitDoc.from(visit));
            return visit;
        });
    }

    @Override
    public List<VisitDocument> recent(int limit) {
        return guarded(() -> {
            Query newestFirst = Query.query(Criteria.where("occurredAt").exists(true))
                    .with(Sort.by(Sort.Direction.DESC, "occurredAt"))
                    .limit(limit);
            return template.find(newestFirst, VisitDoc.class).stream()
                    .map(VisitDoc::toDomain)
                    .toList();
        });
    }

    @Override
    public long countAll() {
        return guarded(() -> template.count(new Query(), VisitDoc.class));
    }

    @Override
    public long countByTopic(String topicId) {
        return guarded(() -> template.count(
                Query.query(Criteria.where("topicId").is(topicId)), VisitDoc.class));
    }

    @Override
    public List<TopicTally> topTopics(int limit) {
        return guarded(() -> {
            AggregationResults<Document> results = template.aggregate(
                    Aggregation.newAggregation(topTopicsStages(limit)),
                    VisitDoc.class, Document.class);
            return results.getMappedResults().stream()
                    // $sum emits int32 for small counts — read as Number, never assume Long.
                    .map(row -> new TopicTally(
                            row.getString("_id"), ((Number) row.get("visits")).longValue()))
                    .toList();
        });
    }

    @Override
    public long avgDurationMs(String topicId) {
        return guarded(() -> {
            Aggregation pipeline = topicId == null || topicId.isBlank()
                    ? Aggregation.newAggregation(Aggregation.group().avg("durationMs").as("avgMs"))
                    : Aggregation.newAggregation(
                            Aggregation.match(Criteria.where("topicId").is(topicId)),
                            Aggregation.group().avg("durationMs").as("avgMs"));
            Document only = template.aggregate(pipeline, VisitDoc.class, Document.class)
                    .getUniqueMappedResult();
            // Empty population → no group row → the honest 0, not a failure.
            return only == null ? 0 : Math.round(only.getDouble("avgMs"));
        });
    }

    @Override
    public List<LocaleTally> byLocaleHistogram() {
        return guarded(() -> {
            AggregationResults<Document> results = template.aggregate(
                    Aggregation.newAggregation(histogramStages()),
                    VisitDoc.class, Document.class);
            return results.getMappedResults().stream()
                    .map(row -> new LocaleTally(
                            row.getString("_id"), ((Number) row.get("visits")).longValue()))
                    .toList();
        });
    }

    /**
     * The transparent artifact behind GET /explain: the stats pipelines
     * RENDERED AS THE SERVER SEES THEM — each fluent stage converted to its
     * BSON Document, exactly the array the driver sends. No server round
     * trip, no dependency on liveness: what Mongo WILL run, inspectable
     * from the console, in fake mode too.
     */
    public List<Document> explainPipelines() {
        AggregationOperationContext context = Aggregation.DEFAULT_CONTEXT;
        return List.of(
                new Document("name", "topTopics").append("stages",
                        topTopicsStages(ReadVisitStats.TOP_TOPICS_LIMIT).stream()
                                .map(stage -> stage.toDocument(context))
                                .toList()),
                new Document("name", "byLocaleHistogram").append("stages",
                        histogramStages().stream()
                                .map(stage -> stage.toDocument(context))
                                .toList()),
                new Document("name", "avgDurationMs").append("stages",
                        List.of(Aggregation.group().avg("durationMs").as("avgMs")
                                .toDocument(context))));
    }

    /** The topTopics pipeline, shared by the real query and the explain artifact. */
    private List<AggregationOperation> topTopicsStages(int limit) {
        return List.of(
                Aggregation.group("topicId").count().as("visits"),
                // ONE compound $sort, not two adjacent sorts: a $sort is a
                // blocking stage, so a second sort re-sorts the first one's
                // output completely — last sort wins, ties broken by nothing.
                // The parity test caught exactly this mistake.
                Aggregation.sort(Sort.by(Sort.Order.desc("visits"), Sort.Order.asc("_id"))),
                Aggregation.limit(limit));
    }

    /** The histogram pipeline: group, then the deterministic row order. */
    private List<AggregationOperation> histogramStages() {
        return List.of(
                Aggregation.group("locale").count().as("visits"),
                Aggregation.sort(Sort.Direction.ASC, "_id"));
    }

    /** Runs a store call, classifying driver failures into the taxonomy as typed exceptions. */
    private <T> T guarded(StoreCall<T> call) {
        try {
            return call.get();
        } catch (RuntimeException failure) {
            throw AppException.of(unavailableOrInternal("the visits store call failed", failure));
        }
    }

    @FunctionalInterface
    private interface StoreCall<T> {
        T get();
    }

    /**
     * The classifier, mirroring SchemaMigrator's SQLSTATE walk: the driver's
     * timeout and socket families are dependency-down (UNAVAILABLE, 503);
     * everything else stays INTERNAL (500) — a malformed query is our bug
     * and must not wear an outage's clothes.
     */
    private static Failure unavailableOrInternal(String context, RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoTimeoutException || cause instanceof MongoSocketException) {
                return unavailable(context, cause);
            }
            String message = cause.getMessage();
            if (cause instanceof MongoException && message != null
                    && message.toLowerCase(Locale.ROOT).contains("connection")) {
                return unavailable(context, cause);
            }
        }
        return Failure.of(FailureKind.INTERNAL,
                context + ": " + failure.getMessage(),
                Map.of("cause", String.valueOf(failure.getClass().getSimpleName())));
    }

    private static Failure unavailable(String context, Throwable cause) {
        return Failure.of(FailureKind.UNAVAILABLE,
                context + ": MongoDB is unreachable (" + cause.getMessage() + ")",
                Map.of("dependency", "mongo"));
    }
}
