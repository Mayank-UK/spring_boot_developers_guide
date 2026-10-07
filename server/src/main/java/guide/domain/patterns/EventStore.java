package guide.domain.patterns;

import guide.core.errors.AppException;
import guide.core.errors.Failure;
import guide.core.errors.FailureKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * THE EVENT STORE — topic 44's other engine: state as a FUNCTION OF HISTORY.
 * The store holds one thing, an append-only log of FACTS per stream, and
 * answers two questions over it: what happened (the raw stream) and what
 * does that mean the state IS (replay — folding every event, in order, from
 * an empty beginning). There is no update and no delete anywhere in the
 * class; a cart's items, total and status are never STORED at all — every
 * read derives them, which is what makes the derivation trustworthy: the
 * same log always folds to the same state, here or restored from a backup
 * five years from now.
 *
 * The vocabulary is a small CART lifecycle: item-added, item-removed,
 * checked-out. Two disciplines make it honest rather than toy:
 *
 * - FACTS ARE VALIDATED AGAINST THE CURRENT FOLD AT APPEND TIME. You cannot
 *   remove an item the stream's own history doesn't contain, checkout an
 *   empty cart, or append to a stream sealed by checkout — the store refuses
 *   the fact, it never silently rewrites it. But the check is a GUARD, not a
 *   second state: the fold is still the only state there is.
 * - THE FOLD IS PURE AND DETERMINISTIC. Same events, same order, same state
 *   — no clock, no randomness, sequential event ids (evt-1, evt-2, …) — so
 *   the MockMvc tests pin exact projections and the curl tour's comments
 *   are true for everyone who runs it.
 *
 * Pure domain (the dependency rule, again): JDK and guide.core only. A real
 * event store adds storage, optimistic concurrency on stream versions, and
 * snapshots for long streams — prose in the topic, because none of it
 * changes the CONTRACT this class pins.
 */
public final class EventStore {

    /** The vocabulary this store's projection understands (cart lifecycle). */
    public static final String ITEM_ADDED = "cart.item-added";
    public static final String ITEM_REMOVED = "cart.item-removed";
    public static final String CHECKED_OUT = "cart.checked-out";

    /** The whole vocabulary, for the console's UI and the edge's validation. */
    public static List<String> vocabulary() {
        return List.of(ITEM_ADDED, ITEM_REMOVED, CHECKED_OUT);
    }

    private static final long MAX_PRICE_CENTS = 1_000_000;
    private static final Pattern STREAM_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    /** One stored fact: its position in the stream, its identity, its body. */
    public record StoredEvent(long position, String eventId, String streamId, String type,
            Map<String, Object> payload) {
    }

    /** The stream list the overview serves: how much history each stream owns. */
    public record StreamSummary(String streamId, int events, boolean sealed) {
    }

    /**
     * The projection — current state, replayed from the log on every read:
     * the items (a list, so duplicates are quantity), the folded total, the
     * lifecycle status, and the replay log that shows the fold happening.
     */
    public record Projection(String streamId, int version, List<String> items, long totalCents,
            String status, boolean sealed, List<String> replayLog) {
    }

    private final Map<String, List<StoredEvent>> streams = new LinkedHashMap<>();
    private long nextEventNumber = 1;

    /**
     * Append one fact. Validated against the stream's current fold (not
     * against stored state — there is none), then stored forever: append is
     * the ONLY write this class knows.
     */
    public synchronized StoredEvent append(String streamId, String type, Map<String, Object> payload) {
        require(streamId != null && STREAM_ID.matcher(streamId).matches(),
                "stream must be 1-40 chars of lowercase letters, digits and dashes (e.g. cart-1)");
        Map<String, Object> body = payload == null ? Map.of() : Map.copyOf(payload);
        Projection current = fold(streamId);
        switch (type) {
            case ITEM_ADDED -> {
                requireItem(body);
                requirePrice(body);
                if (current.sealed()) {
                    throw conflict(streamId, "already checked out — a sealed stream accepts no new facts");
                }
            }
            case ITEM_REMOVED -> {
                String item = requireItem(body);
                if (current.sealed()) {
                    throw conflict(streamId, "already checked out — a sealed stream accepts no new facts");
                }
                if (!current.items().contains(item)) {
                    throw AppException.of(Failure.of(FailureKind.VALIDATION,
                            "cannot remove '" + item + "' — the stream's own history doesn't contain it",
                            null));
                }
            }
            case CHECKED_OUT -> {
                if (current.sealed()) {
                    throw conflict(streamId, "already checked out — a sealed stream accepts no new facts");
                }
                if (current.items().isEmpty()) {
                    throw AppException.of(Failure.of(FailureKind.VALIDATION,
                            "checkout of an empty cart is not a fact the store can own", null));
                }
            }
            default -> throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "type must be one of " + String.join(", ", vocabulary())
                            + " — the store only owns its vocabulary", null));
        }
        StoredEvent event = new StoredEvent(current.version() + 1, "evt-" + (nextEventNumber++),
                streamId, type, body);
        streams.computeIfAbsent(streamId, key -> new ArrayList<>()).add(event);
        return event;
    }

    /** The raw history, in append order — what actually happened, nothing derived. */
    public synchronized List<StoredEvent> stream(String streamId) {
        List<StoredEvent> events = streams.get(streamId);
        if (events == null) {
            throw AppException.of(Failure.of(FailureKind.NOT_FOUND,
                    "no stream named '" + streamId + "' — append to it first", null));
        }
        return List.copyOf(events);
    }

    /** Current state: the log folded, from an empty beginning, on every call. */
    public synchronized Projection replay(String streamId) {
        if (!streams.containsKey(streamId)) {
            throw AppException.of(Failure.of(FailureKind.NOT_FOUND,
                    "no stream named '" + streamId + "' — append to it first", null));
        }
        return fold(streamId);
    }

    /** Every stream the store owns — the overview rail. */
    public synchronized List<StreamSummary> streams() {
        List<StreamSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<StoredEvent>> entry : streams.entrySet()) {
            summaries.add(new StreamSummary(entry.getKey(), entry.getValue().size(),
                    entry.getValue().stream().anyMatch(event -> event.type().equals(CHECKED_OUT))));
        }
        return List.copyOf(summaries);
    }

    /** Back to the empty log — nothing ever happened. */
    public synchronized void reset() {
        streams.clear();
        nextEventNumber = 1;
    }

    /**
     * THE FOLD — the only place state comes from. Starts empty, applies every
     * event in order, derives items + total + status. Pure: no stored state,
     * no clock, no side effects; the replay log narrates each step so the
     * demo can show state being BUILT rather than fetched.
     */
    private Projection fold(String streamId) {
        List<StoredEvent> events = streams.getOrDefault(streamId, List.of());
        List<String> items = new ArrayList<>();
        List<Long> prices = new ArrayList<>();
        long totalCents = 0;
        boolean sealed = false;
        List<String> replayLog = new ArrayList<>();
        for (StoredEvent event : events) {
            switch (event.type()) {
                case ITEM_ADDED -> {
                    items.add(itemOf(event.payload()));
                    long price = priceOf(event.payload());
                    prices.add(price);
                    totalCents += price;
                    replayLog.add(event.eventId() + " " + ITEM_ADDED + " " + itemOf(event.payload())
                            + " " + price + "¢ → items " + items + ", total " + totalCents + "¢");
                }
                case ITEM_REMOVED -> {
                    int index = items.indexOf(itemOf(event.payload()));
                    items.remove(index);
                    totalCents -= prices.remove(index);
                    replayLog.add(event.eventId() + " " + ITEM_REMOVED + " " + itemOf(event.payload())
                            + " → items " + items + ", total " + totalCents + "¢");
                }
                case CHECKED_OUT -> {
                    sealed = true;
                    replayLog.add(event.eventId() + " " + CHECKED_OUT
                            + " → history sealed: every later read of this stream folds the same state");
                }
                default -> throw new IllegalStateException("the store never appended a " + event.type());
            }
        }
        return new Projection(streamId, events.size(), List.copyOf(items), totalCents,
                sealed ? "checked-out" : events.isEmpty() ? "empty" : "open", sealed, List.copyOf(replayLog));
    }

    private static AppException conflict(String streamId, String reason) {
        return AppException.of(Failure.of(FailureKind.CONFLICT,
                "stream '" + streamId + "' " + reason, null));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION, message, null));
        }
    }

    private static String requireItem(Map<String, Object> body) {
        Object item = body.get("item");
        if (!(item instanceof String value) || value.isBlank()) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "payload needs a non-blank 'item' string", null));
        }
        return value;
    }

    private static long requirePrice(Map<String, Object> body) {
        long price = priceOf(body);
        if (price < 1 || price > MAX_PRICE_CENTS) {
            throw AppException.of(Failure.of(FailureKind.VALIDATION,
                    "priceCents must be between 1 and " + MAX_PRICE_CENTS, null));
        }
        return price;
    }

    private static String itemOf(Map<String, Object> payload) {
        return String.valueOf(payload.get("item"));
    }

    private static long priceOf(Map<String, Object> payload) {
        Object price = payload.get("priceCents");
        return price instanceof Number number ? number.longValue() : -1;
    }
}
