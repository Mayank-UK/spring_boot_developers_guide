package guide.domain.realtime;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The chat demo's room contract (topic 28): the vocabulary and message
 * shapes the WebSocket handler speaks, kept socket-free so the policy —
 * which rooms exist, what a nick may look like, what happens to hostile
 * text — is plain, testable domain code rather than handler folklore.
 *
 * Two rooms exist ON PURPOSE (alpha and beta): the demo's whole
 * rooms-are-partitioning lesson needs two isolated audiences, the same way
 * the express guide's chat needed rooms to show that a broadcast is only
 * ever O(room), not O(server).
 */
public final class ChatRooms {

    /** The only rooms the handler will join. Closed vocabulary, no dynamic rooms — a demo, not a product. */
    public static final List<String> ROOMS = List.of("alpha", "beta");

    /** Nicks are labels, not identities: short, boring, control-character-free. */
    public static final int MAX_NICK_LENGTH = 24;

    /** Text cap: chat lines are short; anything longer is truncated, never propagated. */
    public static final int MAX_TEXT_LENGTH = 500;

    /**
     * One chat frame, the only shape the demo ever puts on the wire:
     * type (say | join | leave), from (nick, or "system"), the text, the
     * room it belongs to, when, and a server-assigned sequence — the seq is
     * what a future resume story would key on, exactly like the ticker's.
     */
    public record ChatMessage(String type, String from, String text, String room, Instant at, long seq) {

        public static final String TYPE_SAY = "say";
        public static final String TYPE_JOIN = "join";
        public static final String TYPE_LEAVE = "leave";
    }

    private ChatRooms() {
    }

    /**
     * Room names arrive as a query parameter, which means they arrive
     * hostile: normalized to lowercase, accepted only if the closed
     * vocabulary contains them. Unknown rooms are NOT defaulted to a lobby
     * — silently joining a different room than requested teaches the wrong
     * lesson; the handler refuses the connection instead.
     */
    public static Optional<String> normalizeRoom(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        return ROOMS.contains(normalized) ? Optional.of(normalized) : Optional.empty();
    }

    /**
     * Nicks: trimmed, control characters stripped, capped. Blank after all
     * that (empty param, only whitespace, only control characters) → empty
     * Optional, and the handler refuses the connection — a chat where
     * members are unnamed is a chat where join/leave messages lie.
     */
    public static Optional<String> sanitizeNick(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = stripControls(raw).trim();
        if (cleaned.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(cleaned.length() > MAX_NICK_LENGTH
                ? cleaned.substring(0, MAX_NICK_LENGTH) : cleaned);
    }

    /** Message text: trimmed, control-stripped, capped — truncation, not rejection. */
    public static String sanitizeText(String raw) {
        String cleaned = stripControls(raw == null ? "" : raw).trim();
        if (cleaned.length() > MAX_TEXT_LENGTH) {
            return cleaned.substring(0, MAX_TEXT_LENGTH - 1) + "…";
        }
        return cleaned;
    }

    /** The three factories — one shape, three types. */
    public static ChatMessage joined(String nick, String room, Instant at, long seq) {
        return new ChatMessage(ChatMessage.TYPE_JOIN, "system",
                nick + " joined " + room, room, at, seq);
    }

    public static ChatMessage left(String nick, String room, Instant at, long seq) {
        return new ChatMessage(ChatMessage.TYPE_LEAVE, "system",
                nick + " left " + room, room, at, seq);
    }

    public static ChatMessage said(String nick, String room, String text, Instant at, long seq) {
        return new ChatMessage(ChatMessage.TYPE_SAY, nick, text, room, at, seq);
    }

    private static String stripControls(String value) {
        StringBuilder cleaned = new StringBuilder(value.length());
        value.codePoints().filter(code -> !Character.isISOControl(code)).forEach(cleaned::appendCodePoint);
        return cleaned.toString();
    }
}
