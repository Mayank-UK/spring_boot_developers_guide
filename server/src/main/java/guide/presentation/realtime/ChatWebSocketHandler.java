package guide.presentation.realtime;

import guide.domain.realtime.ChatRooms;
import guide.domain.realtime.ChatRooms.ChatMessage;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Topic 28's WebSocket surface: a RAW TextWebSocketHandler for the chat —
 * no STOMP, no messaging subprotocol, just the four callbacks a socket
 * lifecycle offers. The three lessons the topic quotes from this file:
 *
 * - THE REGISTRY IS THE APP: a ConcurrentHashMap from session to member
 *   (room + nick). Everyone in it can be found; everyone who leaves (close,
 *   error, transport failure) is removed in every path — a leaked session
 *   is a ghost that receives broadcasts forever.
 * - CONCURRENT SENDS ARE THE CLASSIC BUG: WebSocketSession.sendMessage is
 *   not safe to call from two threads at once; broadcast sends under
 *   synchronized (session), one frame at a time.
 * - A SLOW OR DEAD CLIENT IS DROPPED, not waited on: any IOException on
 *   send removes the member — the container's own send buffer did the
 *   waiting for us before it threw (that buffer cap is the backpressure
 *   note in the topic's prose).
 *
 * Rooms arrive as a query parameter (?room=alpha|beta) and are validated by
 * the domain vocabulary (ChatRooms) — an unknown room refuses the
 * connection (1008), because silently joining a different room than the
 * one asked for is the wrong lesson.
 */
public final class ChatWebSocketHandler extends TextWebSocketHandler {

    /** One member: the room they joined and the nick they presented — nothing else is kept. */
    private record Member(String room, String nick) {
    }

    /** Occupancy row for the state endpoint: a room and how many members it holds. */
    public record RoomOccupancy(String name, int sessions) {
    }

    /** Protocol-level ping/pong frames never reach this handler — the container answers them. */
    static final String PING_NOTE = "ping/pong frames are answered by the container, not by the handler";

    private final Map<WebSocketSession, Member> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicLong seq = new AtomicLong();

    public ChatWebSocketHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Occupancy for the /chat/state endpoint: both rooms, always listed, with live counts. */
    public List<RoomOccupancy> occupancy() {
        return ChatRooms.ROOMS.stream()
                .map(room -> new RoomOccupancy(room, (int) sessions.values().stream()
                        .filter(member -> member.room().equals(room)).count()))
                .toList();
    }

    /** The join edge: validate room + nick, register, tell the room. */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> params = queryParams(session);
        var room = ChatRooms.normalizeRoom(params.get("room"));
        var nick = ChatRooms.sanitizeNick(params.get("nick"));
        if (room.isEmpty() || nick.isEmpty()) {
            session.close(new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(),
                    "room must be one of " + ChatRooms.ROOMS + " and nick must be 1–"
                            + ChatRooms.MAX_NICK_LENGTH + " printable characters"));
            return;
        }
        Member member = new Member(room.get(), nick.get());
        sessions.put(session, member);
        broadcast(member.room(), ChatRooms.joined(member.nick(), member.room(), now(), seq.incrementAndGet()));
    }

    /** The payload edge: one JSON shape ({"text": "…"}), sanitized, routed to the room only. */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Member member = sessions.get(session);
        if (member == null) {
            return;
        }
        String text = "";
        try {
            JsonNode payload = objectMapper.readTree(message.getPayload());
            if (payload.isObject() && payload.hasNonNull("text")) {
                text = payload.path("text").asString();
            }
        } catch (RuntimeException malformed) {
            // fall through with empty text — the reply below is the teaching moment
        }
        String cleaned = ChatRooms.sanitizeText(text);
        if (cleaned.isBlank()) {
            send(session, objectMapper.writeValueAsString(new ChatMessage("reject", "system",
                    "messages must be JSON with a non-blank text field", member.room(), now(),
                    seq.incrementAndGet())));
            return;
        }
        broadcast(member.room(),
                ChatRooms.said(member.nick(), member.room(), cleaned, now(), seq.incrementAndGet()));
    }

    /** The leave edge: deregister (idempotent — transport error may have beat us here), tell the room. */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Member member = sessions.remove(session);
        if (member == null) {
            return;
        }
        broadcast(member.room(), ChatRooms.left(member.nick(), member.room(), now(), seq.incrementAndGet()));
    }

    /** The error edge: transport failure IS a close — clean up now, never wait for the polite one. */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        Member member = sessions.remove(session);
        if (member == null) {
            return;
        }
        broadcast(member.room(), ChatRooms.left(member.nick(), member.room(), now(), seq.incrementAndGet()));
        closeQuietly(session);
    }

    /**
     * Fan-out: serialize ONCE, then O(room) sends — never O(server). Only
     * members of the message's own room see it; that partitioning is the
     * whole point of rooms.
     */
    private void broadcast(String room, ChatMessage message) {
        String wire = objectMapper.writeValueAsString(message);
        sessions.forEach((session, member) -> {
            if (member.room().equals(room)) {
                send(session, wire);
            }
        });
    }

    /**
     * One frame to one session, serialized (concurrent sendMessage on the
     * same session throws IllegalStateException — the classic ws bug), with
     * the slow-client policy: an IOException here means the container gave
     * up on this client (its send buffer overflowed or the TCP connection
     * is gone) — drop the member immediately.
     */
    private void send(WebSocketSession session, String wire) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(wire));
            }
        } catch (IOException | IllegalStateException slowOrGone) {
            sessions.remove(session);
            closeQuietly(session);
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException | IllegalStateException alreadyClosed) {
            // nothing left to close — this is the cleanup path, not an error path
        }
    }

    private Instant now() {
        return clock.instant();
    }

    /** Query params off the handshake URI, decoded — ?room=alpha&nick=ada is this handler's whole auth story. */
    private static Map<String, String> queryParams(WebSocketSession session) {
        URI uri = session.getUri();
        Map<String, String> params = new HashMap<>();
        if (uri == null || uri.getRawQuery() == null) {
            return params;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            int split = pair.indexOf('=');
            if (split > 0) {
                params.put(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}
