package guide.data.messaging;

import guide.core.errors.UnavailableException;
import guide.data.redis.RedisNamespace;
import guide.domain.messaging.DomainEvent;
import guide.domain.messaging.EventStream;
import guide.domain.messaging.TeachingEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The stream console's LIVE adapter: one Redis stream, one consumer group,
 * and the six commands that make the machinery real — XADD to append,
 * XGROUP CREATE (idempotent, MKSTREAM) at activation, XREADGROUP with
 * offset "&gt;" (new) or "0" (this consumer's un-acked), XACK to finish,
 * XPENDING to inspect, and the claim takeover. KEYS carry the topic-20
 * namespace discipline ({@code guide:demo:stream:events} /
 * {@code guide:demo:stream:dead}): the prefix is owned by
 * guide.data.redis.RedisNamespace and NOTHING above this file ever spells
 * a stream key.
 *
 * THE CLAIM IMPLEMENTATION, flagged honestly: Redis 6.2's XAUTOCLAIM fuses
 * "scan the pending list for idle entries" and "claim them" into one
 * server-side command, but Spring Data Redis 4.1 does not surface it — so
 * this adapter performs the two-step form XAUTOCLAIM replaced:
 * XPENDING with an IDLE filter (only entries idle long enough), then XCLAIM
 * with the same MIN-IDLE (a stale list cannot steal an entry another worker
 * just re-delivered). Same semantics for this console's bounded batch; one
 * extra round trip; the topic's claim section says exactly this.
 *
 * Delivery counts: XPENDING answers each entry's times-delivered BEFORE the
 * claim; the claim itself is one more delivery, so claimed entries carry
 * pending-count + 1. That count is the poison arithmetic — the use case
 * dead-letters entries whose deliveries exceed the threshold.
 */
public final class RedisStreamMessaging implements EventStream {

    /** The main stream, namespaced (topic 20's discipline, reused). */
    static final String STREAM_KEY = RedisNamespace.PREFIX + "stream:events";

    /** The dead-letter stream beside it: poison parks here, readable forever. */
    static final String DEAD_KEY = RedisNamespace.PREFIX + "stream:dead";

    /** XPENDING/XCLAIM batch ceiling — a console, not a firehose. */
    private static final long PENDING_BATCH = 100;

    private final StringRedisTemplate template;
    private final ObjectMapper mapper;

    public RedisStreamMessaging(StringRedisTemplate template, ObjectMapper mapper) {
        this.template = Objects.requireNonNull(template, "template");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public TeachingEventPublisher.PublishedEvent append(DomainEvent event) {
        RecordId id = ops().add(STREAM_KEY, toFields(event));
        return new TeachingEventPublisher.PublishedEvent(
                id == null ? null : id.getValue(), STREAM_KEY, event);
    }

    @Override
    public String appendDead(DomainEvent event, String reason) {
        Map<String, String> fields = new LinkedHashMap<>(toFields(event));
        fields.put("reason", reason == null ? "unspecified" : reason);
        fields.put("deadAt", Instant.now().toString());
        RecordId id = ops().add(DEAD_KEY, fields);
        return id == null ? null : id.getValue();
    }

    @Override
    public void ensureGroup() {
        try {
            // MKSTREAM=true: the group exists from an empty stream, so a
            // fresh server needs no bootstrap XADD before the first read.
            template.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(STREAM_KEY.getBytes(StandardCharsets.UTF_8), GROUP,
                            ReadOffset.from("0"), true));
        } catch (DataAccessException e) {
            // BUSYGROUP: the group already exists — the idempotent-activation
            // answer, not an error. (Any genuinely broken server still throws.)
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")
                    && !String.valueOf(e.getCause()).contains("BUSYGROUP")) {
                throw unavailable(e);
            }
        }
    }

    @Override
    public List<Delivery> readGroup(String consumer, int count, boolean replayPending) {
        // ">" = entries never delivered to ANYONE in the group;
        // "0" = this consumer's own un-acked deliveries — the restart read.
        ReadOffset offset = replayPending ? ReadOffset.from("0") : ReadOffset.lastConsumed();
        List<MapRecord<String, Object, Object>> records = ops().read(
                Consumer.from(GROUP, consumer),
                StreamReadOptions.empty().count(count),
                StreamOffset.create(STREAM_KEY, offset));
        List<Delivery> deliveries = new ArrayList<>();
        if (records != null) {
            for (MapRecord<String, Object, Object> record : records) {
                String id = record.getId().getValue();
                deliveries.add(new Delivery(id, fromFields(record.getValue(), id), consumer, 1L));
            }
        }
        return deliveries;
    }

    @Override
    public long ack(List<String> ids) {
        if (ids.isEmpty()) {
            return 0L;
        }
        Long acked = ops().acknowledge(STREAM_KEY, GROUP, ids.toArray(String[]::new));
        return acked == null ? 0L : acked;
    }

    @Override
    public PendingView pending() {
        PendingMessagesSummary summary = ops().pending(STREAM_KEY, GROUP);
        PendingMessages entries = ops().pending(STREAM_KEY, GROUP,
                Range.unbounded(), PENDING_BATCH);
        List<PendingEntry> rows = new ArrayList<>();
        if (entries != null) {
            for (PendingMessage message : entries) {
                rows.add(new PendingEntry(message.getIdAsString(), message.getConsumerName(),
                        message.getElapsedTimeSinceLastDelivery().toMillis(),
                        message.getTotalDeliveryCount()));
            }
        }
        Map<String, Long> perConsumer = summary == null || summary.getPendingMessagesPerConsumer() == null
                ? Map.of()
                : summary.getPendingMessagesPerConsumer();
        // An EMPTY pending list has no min/max ids — the summary's accessors
        // throw NoSuchElementException on an empty Optional, so the id pair
        // is only read when there is anything pending at all.
        long total = summary == null ? rows.size() : summary.getTotalPendingMessages();
        String minId = total > 0 && summary != null ? summary.minMessageId() : null;
        String maxId = total > 0 && summary != null ? summary.maxMessageId() : null;
        return new PendingView(total, minId, maxId, perConsumer, rows);
    }

    @Override
    public ClaimedEntries claimIdle(String consumer, Duration minIdle) {
        PendingMessages idle = ops().pending(STREAM_KEY, GROUP,
                Range.unbounded(), PENDING_BATCH, minIdle);
        if (idle == null || idle.isEmpty()) {
            return new ClaimedEntries(List.of(), List.of(), consumer, minIdle.toMillis());
        }
        Map<String, Long> deliveryCounts = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (PendingMessage message : idle) {
            ids.add(message.getIdAsString());
            deliveryCounts.put(message.getIdAsString(), message.getTotalDeliveryCount());
        }
        // The two-step XAUTOCLAIM: XCLAIM the idle-filtered ids under the
        // same MIN-IDLE the pending scan used. Entries acked in the race
        // window simply do not come back — claim what exists, skip the rest.
        List<MapRecord<String, Object, Object>> claimed = ops().claim(STREAM_KEY, GROUP,
                consumer, XClaimOptions.minIdle(minIdle).ids(ids.toArray(String[]::new)));
        List<Delivery> reprocessable = new ArrayList<>();
        List<Delivery> poison = new ArrayList<>();
        if (claimed != null) {
            for (MapRecord<String, Object, Object> record : claimed) {
                String id = record.getId().getValue();
                // pending said N deliveries; this claim is delivery N+1.
                long deliveries = deliveryCounts.getOrDefault(id, 1L) + 1L;
                Delivery delivery =
                        new Delivery(id, fromFields(record.getValue(), id), consumer, deliveries);
                if (deliveries > guide.domain.messaging.ClaimIdleEntries.MAX_DELIVERIES) {
                    poison.add(delivery);
                } else {
                    reprocessable.add(delivery);
                }
            }
        }
        return new ClaimedEntries(reprocessable, poison, consumer, minIdle.toMillis());
    }

    @Override
    public List<DeadLetter> deadLetters(int limit) {
        List<MapRecord<String, Object, Object>> all = ops().range(DEAD_KEY, Range.unbounded());
        List<DeadLetter> letters = new ArrayList<>();
        if (all == null) {
            return letters;
        }
        int from = Math.max(0, all.size() - limit);
        for (MapRecord<String, Object, Object> record : all.subList(from, all.size())) {
            String id = record.getId().getValue();
            Map<String, String> fields = stringFields(record.getValue());
            letters.add(new DeadLetter(id, fields.get("eventId"), fields.get("eventType"),
                    fields.get("reason"), fields.get("deadAt"),
                    fromFields(record.getValue(), id)));
        }
        return letters;
    }

    // ---- the field mapping: one event, one flat string map ----

    private StreamOperations<String, Object, Object> ops() {
        return template.opsForStream();
    }

    /** The wire shape: scalars as strings, the two documents as JSON. */
    private Map<String, String> toFields(DomainEvent event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", event.eventId());
        fields.put("aggregateType", event.aggregateType());
        fields.put("aggregateId", event.aggregateId());
        fields.put("eventType", event.eventType());
        fields.put("occurredAt", event.occurredAt().toString());
        fields.put("payload", mapper.writeValueAsString(event.payload()));
        fields.put("headers", mapper.writeValueAsString(event.headers()));
        return fields;
    }

    /**
     * Parse an entry back into an event. Tolerant of foreign/legacy fields
     * on purpose: an unreadable entry must still FLOW to the poison path
     * (delivery counts grow, claims dead-letter it) rather than break the
     * consumer loop — absent identity degrades to the stream id, and the
     * event arrives at the dedupe set with whatever it actually carries.
     */
    private DomainEvent fromFields(Map<Object, Object> raw, String streamId) {
        Map<String, String> fields = stringFields(raw);
        Map<String, Object> payload = readMap(fields.get("payload"),
                new TypeReference<Map<String, Object>>() {
                });
        Map<String, String> headers = readMap(fields.get("headers"),
                new TypeReference<Map<String, String>>() {
                });
        Instant occurredAt = null;
        try {
            occurredAt = Instant.parse(fields.getOrDefault("occurredAt", ""));
        } catch (RuntimeException ignored) {
            occurredAt = Instant.EPOCH;
        }
        return new DomainEvent(
                fields.getOrDefault("eventId", streamId),
                fields.getOrDefault("aggregateType", "unknown"),
                fields.getOrDefault("aggregateId", "unknown"),
                fields.getOrDefault("eventType", "unknown"),
                payload, headers, occurredAt);
    }

    private Map<String, String> stringFields(Map<Object, Object> raw) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (raw != null) {
            for (Map.Entry<Object, Object> entry : raw.entrySet()) {
                fields.put(String.valueOf(entry.getKey()),
                        entry.getValue() == null ? null : String.valueOf(entry.getValue()));
            }
        }
        return fields;
    }

    private <T> T readMap(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return (T) Map.of();
        }
        try {
            return mapper.readValue(json, type);
        } catch (RuntimeException e) {
            return (T) Map.of();
        }
    }

    private static UnavailableException unavailable(Exception cause) {
        return new UnavailableException(
                "Redis Streams are unreachable — the messaging demo is degraded until the "
                        + "service returns (" + cause.getClass().getSimpleName() + ")",
                java.util.Map.of("area", TeachingEventPublisher.AREA), cause);
    }
}
