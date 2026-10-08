package guide.presentation.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/**
 * The chat handler over a REAL socket: the full application boots on a
 * random port (grpc moved to a random port too, so no cached context's
 * 9090 collides) and Spring's own WebSocketClient — StandardWebSocketClient
 * over the same jakarta.websocket implementation Tomcat serves with — opens
 * three sessions. What is asserted, in order of the lesson:
 *
 * - JOIN/LEAVE system messages reach the room (ada sees bob join alpha);
 * - a message is broadcast ONLY within its room — beta hears nothing,
 *   which is the rooms-as-partitioning claim, executable;
 * - the occupancy endpoint counts exactly who is where, both rooms listed;
 * - a hostile room name refuses the connection (1008 policy violation)
 *   instead of silently joining somewhere else;
 * - a malformed payload is rejected to the sender, never broadcast.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.grpc.server.port=0"})
class ChatWebSocketHandlerTest {

    /** Collects every text frame and the close status — one per client. */
    private static final class Collector implements WebSocketHandler {
        final List<String> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile CloseStatus closeStatus;

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            frames.add(((TextMessage) message).getPayload());
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable error) {
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closeStatus = status;
            closed.countDown();
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }

    private final StandardWebSocketClient client = new StandardWebSocketClient();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    private final List<WebSocketSession> toClose = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeEverythingLeftOpen() {
        for (WebSocketSession session : toClose) {
            close(session);
        }
    }

    @Test
    void roomsRouteMessagesOnlyWithinTheirRoomAndReportAccurateOccupancy() throws Exception {
        Collector ada = new Collector();
        Collector bob = new Collector();
        Collector cyd = new Collector();
        WebSocketSession adaSession = open(ada, "alpha", "ada");
        WebSocketSession cydSession = open(cyd, "beta", "cyd");
        WebSocketSession bobSession = open(bob, "alpha", "bob");

        // join messages: ada sees herself and bob join alpha; cyd sees only cyd join beta
        await(() -> assertTrue(ada.frames.stream().anyMatch(f -> f.contains("ada joined alpha"))));
        await(() -> assertTrue(bob.frames.stream().anyMatch(f -> f.contains("bob joined alpha"))));
        await(() -> assertTrue(cyd.frames.stream().anyMatch(f -> f.contains("cyd joined beta"))));
        assertFalse(cyd.frames.stream().anyMatch(f -> f.contains("joined alpha")),
                "beta must never hear alpha's joins");

        // occupancy: alpha=2 (ada+bob), beta=1 (cyd) — both rooms always listed
        String state = get("/api/demo/realtime/chat/state");
        assertTrue(state.contains("\"name\":\"alpha\",\"sessions\":2"), state);
        assertTrue(state.contains("\"name\":\"beta\",\"sessions\":1"), state);

        // the broadcast round-trip: ada speaks, ada+bob hear it, cyd does not
        adaSession.sendMessage(new TextMessage("{\"text\":\"hello from ada\"}"));
        await(() -> assertTrue(ada.frames.stream().anyMatch(f -> f
                .contains("\"type\":\"say\"") && f.contains("hello from ada"))));
        await(() -> assertTrue(bob.frames.stream().anyMatch(f -> f.contains("hello from ada"))));
        assertFalse(cyd.frames.stream().anyMatch(f -> f.contains("hello from ada")),
                "room isolation: beta never receives alpha's messages");

        // leave: bob closes, ada is told, cyd is not; occupancy drops to 1
        close(bobSession);
        await(() -> assertTrue(ada.frames.stream().anyMatch(f -> f.contains("bob left alpha"))));
        assertFalse(cyd.frames.stream().anyMatch(f -> f.contains("bob left")));
        await(() -> assertTrue(get("/api/demo/realtime/chat/state")
                .contains("\"name\":\"alpha\",\"sessions\":1")));

        // cleanup discipline: no ghost member keeps receiving after the close
        int adaFrameCount = ada.frames.size();
        cydSession.sendMessage(new TextMessage("{\"text\":\"beta only\"}"));
        await(() -> assertTrue(cyd.frames.stream().anyMatch(f -> f.contains("beta only"))));
        assertTrue(ada.frames.stream().noneMatch(f -> f.contains("beta only")));
        assertEquals(adaFrameCount, ada.frames.size(), "ada heard nothing from beta's exchange");
    }

    @Test
    void hostileRoomNamesAreRefusedNotRedirected() throws Exception {
        Collector intruder = new Collector();
        WebSocketSession session = client.execute(intruder,
                        "ws://localhost:" + port + "/ws/demo/chat?room=wallstreet&nick=gordon")
                .get(5, TimeUnit.SECONDS);
        toClose.add(session);
        awaitRefusal(session, intruder);
        if (intruder.closeStatus != null) {
            assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), intruder.closeStatus.getCode(),
                    "unknown room → 1008, not a silent redirect to some default room");
        }
    }

    // (blank-nick refusal is unit-proven in ChatRoomsTest — sanitizeNick
    // blank → Optional.empty; the socket-level proof of the shared refuse
    // branch is the wallstreet test above. A socket-level blank-nick probe
    // would need a percent-encoded handshake param, which Spring's client
    // template-expansion double-encodes into a printable nick.)

    @Test
    void malformedPayloadsAreRejectedToTheSenderAndNeverBroadcast() throws Exception {
        Collector ada = new Collector();
        Collector bob = new Collector();
        WebSocketSession adaSession = open(ada, "alpha", "ada");
        open(bob, "alpha", "bob");
        await(() -> assertTrue(bob.frames.stream().anyMatch(f -> f.contains("bob joined alpha"))));

        adaSession.sendMessage(new TextMessage("this is not json"));
        adaSession.sendMessage(new TextMessage("{\"text\":\"   \"}"));
        await(() -> assertTrue(ada.frames.stream()
                        .filter(f -> f.contains("\"type\":\"reject\"")).count() >= 2,
                "both hostile payloads got the typed rejection: " + ada.frames));
        assertFalse(bob.frames.stream().anyMatch(f -> f.contains("reject")),
                "rejections are answers to the sender, never broadcasts");
        assertFalse(bob.frames.stream().anyMatch(f -> f.contains("this is not json")));

        // the mapper never chokes on the stream afterwards — valid traffic still flows
        adaSession.sendMessage(new TextMessage("{\"text\":\"still alive\"}"));
        await(() -> assertTrue(bob.frames.stream().anyMatch(f -> f.contains("still alive"))));
    }

    // ---- helpers ----

    /**
     * Robust refusal wait: the server closes DURING afterConnectionEstablished,
     * so the close frame races the client's own open handling — poll for either
     * the close callback OR a session the client already reports closed.
     */
    private static void awaitRefusal(WebSocketSession session, Collector collector) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (collector.closeStatus == null && session.isOpen()
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(collector.closeStatus != null || !session.isOpen(),
                "the refused connection must close (1008), not hang open");
    }

    private WebSocketSession open(Collector collector, String room, String nick) throws Exception {
        WebSocketSession session = client.execute(collector,
                        "ws://localhost:" + port + "/ws/demo/chat?room=" + room + "&nick=" + nick)
                .get(5, TimeUnit.SECONDS);
        toClose.add(session);
        return session;
    }

    private static void close(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception alreadyClosed) {
            // cleanup path
        }
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = httpClient.send(java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    /** Bounded poll: the handshake and broadcasts are real async, the test is not a sleep. */
    private static void await(ThrowingRunnable until) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        AssertionError last = null;
        while (System.nanoTime() < deadline) {
            try {
                until.run();
                return;
            } catch (AssertionError notYet) {
                last = notYet;
                Thread.sleep(50);
            }
        }
        throw last != null ? last : new AssertionError("condition never met within 5s");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
