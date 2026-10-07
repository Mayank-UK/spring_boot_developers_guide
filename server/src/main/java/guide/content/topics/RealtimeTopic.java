package guide.content.topics;

import static guide.content.topics.TopicBlocks.code;
import static guide.content.topics.TopicBlocks.demo;
import static guide.content.topics.TopicBlocks.section;
import static guide.content.topics.TopicBlocks.text;

import guide.content.ContentSection;
import guide.content.Topic;
import guide.content.TopicGroupId;
import java.util.List;

/**
 * Topic 28 — Realtime. Group realtime-jobs, order 1. Push, honestly: the
 * polling → long-poll → SSE → WebSocket ladder, then two live surfaces
 * built on real machinery (domain/realtime + presentation/realtime) — an
 * SSE price ticker whose feed pauses when nobody listens and replays by
 * Last-Event-ID, and a raw WebSocket chat whose rooms prove partitioning
 * and whose sends prove the slow-client drop policy. Every claim in the
 * prose is code the reader can fire at.
 */
public final class RealtimeTopic {

    private RealtimeTopic() {
    }

    public static Topic topic() {
        return new Topic("realtime", "Real-Time",
                "Push, honestly: the polling ladder, server-sent events with named "
                        + "ids and Last-Event-ID resume, raw WebSocket rooms with a slow-client "
                        + "drop policy — and the delivery semantics nobody promises you.",
                TopicGroupId.REALTIME_JOBS, 1,
                sections());
    }

    private static List<ContentSection> sections() {
        return List.of(
                section("push-models", "Push models",
                        "Polling, long-polling, SSE, WebSocket — the same desire (tell me the moment it happens) at four points on the latency/complexity ladder.",
                        text("push:ladder", """
                                Every realtime question starts as a LATENCY question: the client wants to know something the server knows, the moment the server knows it. The four answers form a ladder, and each rung trades server cost and code complexity for latency — there is no free bottom rung.

                                POLLING is the client asking "anything new?" on a timer — every 5s, every minute. It is one line of client code, zero server surprises, and its latency is half your interval on average and the whole interval in the worst case. Its cost is waste: 1,440 requests a day per client at a one-minute interval, of which nearly all answer "nothing". Polling is not a mistake; it is the right answer when "within a minute" is genuinely fine and the data changes slowly — dashboards showing yesterday's numbers should poll.

                                LONG-POLLING bends polling until it looks like push: the client asks, and the server HOLDS the request open until something exists (or a timeout), answers, and the client immediately asks again. Latency collapses to milliseconds; the cost is a permanently occupied connection per client plus a server that must park thousands of in-flight requests somewhere — a thread pool sized for held connections, or (the modern MVC answer this app uses everywhere) async request dispatch, where topic 09's StreamingResponseBody and SseEmitter release the request thread and re-attach it when data arrives. Long-poll survives in the wild mostly as the fallback of push libraries.

                                SERVER-SENT EVENTS (SSE) keeps the HTTP response open forever and writes events into the body as they happen — one-way push over plain HTTP. WEBSOCKET upgrades the connection to a bidirectional frame protocol: both directions, binary and text, at the cost of leaving HTTP's semantics behind (no status codes, no content negotiation, your own reconnect policy). Those two get this topic's remaining sections and both of its demos, because they are the two a Spring Boot developer actually builds.

                                The honest pick guidance, before any code: if only the SERVER talks (prices, progress, logs, notifications), SSE — it is HTTP, it traverses proxies, the browser client auto-reconnects, and it costs one held response per client. If the CLIENT also talks while listening (chat, collaborative editing, games, signaling), WebSocket — but you inherit reconnection, heartbeats, and delivery semantics as YOUR problems. And if the update rate is measured in minutes, poll and spend the saved complexity somewhere it pays."""),
                        text("push:capacity", """
                                Capacity is the axis the ladder quietly bills you on. A polling server pays per REQUEST: 10,000 clients at 30s intervals is ~333 requests/second of mostly-empty work, cheap per unit but constant. A push server pays per HELD CONNECTION: 10,000 open SSE streams is 10,000 file descriptors, 10,000 entries in some server-side registry, and — if you are careless — 10,000 parked threads. That last number is why this whole topic is async-dispatch-shaped: an SseEmitter or a WebSocket session on modern servlet stacks occupies memory and a socket, NOT a thread; threads are borrowed per event. An idle push connection is a file descriptor and a registry entry; a BLOCKED thread is a thread, and ten thousand of them hold nothing.

                                The registry itself is now your data structure problem — this topic builds two (the ticker's listener list, the chat's session map) and both have the same shape: thread-safe, add on subscribe, remove on every terminal path, and a PAUSE when empty. A push feed that keeps generating events nobody consumes is a server burning CPU for an audience of zero — the ticker's first design rule, and the reason its state endpoint exists.""")),
                section("server-sent-events", "Server-sent events",
                        "A held-open HTTP response that speaks a four-line protocol: event, id, data, retry — plus comment heartbeats. One-way push that stays HTTP all the way down.",
                        text("sse:protocol", """
                                SSE is not a framework feature; it is a MEDIA TYPE and four formatting rules. The response carries Content-Type: text/event-stream, stays open, and writes events as plain text: a line `event: tick` names the event, `id: 41` stamps it, `data: {…}` carries one line of payload (multi-line data repeats the field), `retry: 3000` tells the client to wait three seconds between reconnects, and any line starting with a colon is a COMMENT — invisible to the client API, perfect as a heartbeat. The event ends at the first blank line. That is the entire protocol; everything else today is Spring plumbing around it.

                                On the client it is EventSource — the browser API this demo uses live below. EventSource gives you one connection per URL, dispatches named events to addEventListener, and — its superpower — RECONNECTS ITSELF: when the stream drops, it waits the retry interval and re-opens, presenting the id of the last event it saw as the Last-Event-ID header so the server can replay what was missed. The reconnect is built in; the replay is yours to implement, and this topic's ticker does (bounded ring, reconnection section).

                                On the server, Spring MVC's answer is SseEmitter: return one from a controller and the response goes async (the request thread is released — the same machinery topic 09's streaming used), then send events from ANY thread until you complete it. The ticker controller is the whole pattern:"""),
                        code("sse:lifecycle", "java",
                                "guide/presentation/realtime/SseTickerController.java — subscribe: emitter out, listener in, every terminal path wired to cleanup",
                                "server/src/main/java/guide/presentation/realtime/SseTickerController.java", """
                                SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
                                List<TickerFeed.Tick> missed = tickerFeed.replayAfter(resumeAfter);
                                StreamListener listener = new StreamListener(emitter, missed, limit);
                                activeEmitters.incrementAndGet();
                                // …
                                Runnable unsubscribe = tickerFeed.subscribe(listener::onTick);
                                listener.holdUnsubscribe(unsubscribe);
                                emitter.onCompletion(listener::detach);
                                emitter.onTimeout(listener::detach);
                                emitter.onError(error -> listener.detach());
                                return emitter;"""),
                        text("sse:events", """
                                Three callbacks, one job: onCompletion, onTimeout, and onError must ALL funnel into the same detach — unsubscribe from the feed, decrement the gauge. A stream that times out without cleanup leaves a ghost listener in the feed's list, and the ghost keeps receiving ticks and failing sends forever; the feed's pause-when-empty invariant quietly dies. Miss one of the three and the bug shows up only under load, exactly when you do not want it.

                                Events themselves are built with SseEmitter.event() — the four protocol rules as a builder. The tick event carries all three load-bearing fields: the id (the resume contract), the name (so the client can have many event kinds on one stream), and the data:"""),
                        code("sse:builder", "java",
                                "guide/presentation/realtime/SseTickerController.java — one tick as the protocol writes it: id, name, data",
                                "server/src/main/java/guide/presentation/realtime/SseTickerController.java", """
                                private SseEmitter.SseEventBuilder tickEvent(TickerFeed.Tick tick) {
                                    return SseEmitter.event()
                                            .id(Long.toString(tick.seq()))
                                            .name("tick")
                                            .data(objectMapper.writeValueAsString(tick), MediaType.APPLICATION_JSON);
                                }"""),
                        demo("sse:demo", "realtime:ticker", "The ticker: a live SSE stream",
                                "Subscribe with the browser's EventSource. Named tick events with ids land in the live table, the connection badge walks connecting → live → reconnecting (choose a bounded limit and watch the browser reconnect itself), and the state endpoint reports activeEmitters and ticksSent beside the stream."),
                        text("sse:oneway", """
                                What SSE deliberately is NOT: bidirectional. The client cannot send anything on this connection — no chat messages, no acknowledgments; the client's only upstream verbs are HTTP requests to other endpoints. That limit is the feature: the connection stays ordinary HTTP, so every proxy, load balancer, auth filter, and observability tool that understands responses understands your stream. It is also HTTP/1.1-unfriendly at scale in one specific way — browsers cap simultaneous connections per origin (six), and each SSE stream eats one; HTTP/2 multiplexes many streams over one connection and makes that cap moot, which is why "SSE over HTTP/2" is the default production posture rather than an optimization.""")),
                section("event-stream-mechanics", "Event-stream mechanics",
                        "The plumbing under a working stream: flush discipline, deferred first send, timeouts, heartbeats against proxy buffering — and a feed that pauses when nobody listens.",
                        text("mech:flush", """
                                A stream is only a stream if bytes LEAVE the server when written. Servlet responses buffer; a handler that writes without flushing hands the client a normal, fully-buffered response that arrives all at once when the connection closes — the single most common "my SSE doesn't work" report. Spring flushes after every SseEmitter.send (and after every StreamingResponseBody write), but the discipline is yours the moment you touch a raw OutputStream: write a chunk, flush, repeat — topic 09's chunked-lines exhibit taught it, and every send in this topic rides on it.

                                The second mechanic is ASYNC DISPATCH. The controller returns an SseEmitter; the framework starts async processing, RELEASES the request thread, and later dispatches the result when the emitter completes. That thread economics is the whole capacity story from the push-models section: 10,000 subscribers, ~0 threads while idle. The tests for this demo drive that machinery for real — MockMvc's asyncStarted/asyncDispatch pair is the same dispatch, executed in-process.

                                The third is a gotcha that costs everyone an hour: YOU CANNOT SEND BEFORE YOU RETURN THE EMITTER. send() on an emitter the framework has not initialized throws IllegalStateException — initialization happens when the controller method returns. So the ticker's opening comment, its retry hint, and any Last-Event-ID replay are QUEUED in the listener and flushed on the first tick delivery:"""),
                        code("mech:deferred", "java",
                                "guide/presentation/realtime/SseTickerController.java — the deferred preamble: first delivery sends the comment, the retry hint, the replay, then the tick",
                                "server/src/main/java/guide/presentation/realtime/SseTickerController.java", """
                                if (!preambleSent) {
                                    emitter.send(SseEmitter.event()
                                            .comment("ticker stream open — retry hint follows; comments are heartbeats")
                                            .reconnectTime(RECONNECT_HINT_MILLIS));
                                    for (TickerFeed.Tick missed : replay) {
                                        emitter.send(tickEvent(missed));
                                    }
                                    preambleSent = true;
                                }
                                emitter.send(tickEvent(tick));
                                newTicksSent++;
                                if (newTicksSent % HEARTBEAT_EVERY_TICKS == 0) {
                                    emitter.send(SseEmitter.event().comment("heartbeat"));
                                }"""),
                        text("mech:timeout-heartbeat", """
                                TIMEOUTS: an async request is not immortal. This emitter is built with an explicit 120-second timeout; when it fires, the container completes the request and Spring calls onTimeout — which this controller wired to the same detach as every other ending. A demo stream that ended on timeout without detaching would keep its ghost listener for the rest of the process's life. Choose the number deliberately: long enough that healthy clients never see it, short enough that abandoned streams (client crashed, tab closed mid-flight) get reclaimed.

                                HEARTBEATS exist because of the infrastructure between you and the client. Reverse proxies and corporate middleboxes time out connections that carry no bytes — often at 30 or 60 seconds — and a quiet stream looks exactly like a dead one to them. A comment-only event every few seconds (here: every fifth tick) keeps bytes flowing without surfacing as an event to client code; EventSource ignores comments entirely. Some proxies also BUFFER responses unless asked not to — the classic fix is the response header X-Accel-Buffering: no (nginx's switch); Spring's per-send flushing plus heartbeats covers the rest.

                                The last mechanic is the feed itself. A ticker with no subscribers should cost nothing, and the pause is enforced where ticks are born — the first lines of the feed's one generation method:"""),
                        code("mech:pause", "java",
                                "guide/domain/realtime/TickerFeed.java — the pause: generation requires listeners, so a beat with nobody listening is free",
                                "server/src/main/java/guide/domain/realtime/TickerFeed.java", """
                                public synchronized void tick(Instant now) {
                                    if (listeners.isEmpty()) {
                                        return;
                                    }
                                    // …
                                    ticksGenerated++;
                                    deliver(tick);
                                }"""),
                        code("mech:pump", "java",
                                "guide/domain/realtime/TickerPump.java — the heartbeat adapter: wall-clock beats become tick(now) calls, pace from a property",
                                "server/src/main/java/guide/domain/realtime/TickerPump.java", """
                                @Scheduled(fixedDelayString = "${guide.demo.realtime.ticker-interval-ms:500}")
                                public void beat() {
                                    feed.tick(clock.instant());
                                }"""),
                        text("mech:no-orphans", """
                                Note what is NOT here: a per-subscriber executor, a thread spawned on connect, anything with a lifecycle to leak. One @Scheduled pump beats for the whole app — fixedDelay so beats never stack — and the feed's tick(now) is a no-op when nobody listens. Time arrives as a parameter and randomness as an injected supplier, so the feed's unit tests need no sleeps and no scheduler at all: they call tick with constructed instants and a constant. "No orphan schedulers" is not a style rule; it is the difference between a demo that survives a week of visitors and one that accumulates a thread per abandoned tab until the JVM refuses.""")),
                section("websockets", "WebSockets",
                        "The upgrade handshake, frames, and Spring's raw WebSocketHandler — our chat: rooms by query parameter, system join/leave frames, and an enumerated origin allowlist.",
                        text("ws:handshake", """
                                A WebSocket starts life as one sneaky HTTP request: GET with Upgrade: websocket, Connection: Upgrade, Sec-WebSocket-Key, and — from browsers — an Origin header. The server answers 101 Switching Protocols with an accept key, and from that byte on, the connection is no longer HTTP: it is a FRAME protocol carrying text or binary frames either way, with small control frames (ping/pong/close) riding the same wire. Because the handshake is HTTP, your filters, security chain, and load balancer see it once; because the frames are not HTTP, nothing after that sees anything until the connection dies.

                                Spring gives you two levels of API. The high road is STOMP over WebSocket (spring-boot-starter-websocket's messaging half: subprotocols, broker relay, annotations) — powerful, and a deliberate NON-goal here. This topic takes the raw road: register a WebSocketHandler at a path, implement four callbacks, own everything yourself. That is the level where the lessons actually live:"""),
                        code("ws:config", "java",
                                "guide/presentation/realtime/WebSocketConfig.java — the whole wiring: one handler, one path, an ENUMERATED origin allowlist",
                                "server/src/main/java/guide/presentation/realtime/WebSocketConfig.java", """
                                @Override
                                public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
                                    registry.addHandler(chatWebSocketHandler, "/ws/demo/chat")
                                            .setAllowedOrigins(
                                                    "http://localhost:5176", "http://127.0.0.1:5176",
                                                    "http://localhost:8081", "http://127.0.0.1:8081");
                                }"""),
                        text("ws:origins", """
                                That allowlist deserves its paragraph. A browser's handshake carries Origin, and any page on the web can try to open a socket to your server — a hostile page scripting your chat as its user is Cross-Site WebSocket Hijacking, WebSocket's CSRF. Spring's default accepts only same-origin handshakes; this demo must enumerate its dev origins because the client (5176) and server (8080) are different ports. Replacing the list with "*" is the one-character change that makes any website a participant in your socket. Non-browser clients (tests, websocat) send no Origin and pass either way.

                                The handler itself extends TextWebSocketHandler and lives in four callbacks — established, text message, closed, transport error. The join edge does the demo's validation story in one place:"""),
                        code("ws:established", "java",
                                "guide/presentation/realtime/ChatWebSocketHandler.java — the join edge: hostile query params validated against the domain vocabulary, refuse-not-redirect",
                                "server/src/main/java/guide/presentation/realtime/ChatWebSocketHandler.java", """
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
                                }"""),
                        text("ws:payload-edge", """
                                Two habits worth stealing there: room and nick arrive as QUERY PARAMETERS on the handshake URI (there is no body and no header sugar — the handler parses the URI itself), and an unknown room CLOSES with 1008 policy violation instead of defaulting to a lobby — silently joining a different room than the one asked for is the wrong reflex everywhere, doubly so in a teaching guide. The 1008 status is the honest close code for "your request was valid WebSocket but I refuse it"; the reason phrase travels to the client's close event.

                                The message edge then has the thin-controller shape you know from HTTP controllers, minus the annotations: parse the payload (one JSON shape: {"text": …}), sanitize through the same domain vocabulary, answer rejections to the SENDER only, broadcast the rest:"""),
                        code("ws:text", "java",
                                "guide/presentation/realtime/ChatWebSocketHandler.java — the payload edge: unregistered sessions ignored, rejected input answered privately, valid input routed to the room",
                                "server/src/main/java/guide/presentation/realtime/ChatWebSocketHandler.java", """
                                Member member = sessions.get(session);
                                if (member == null) {
                                    return;
                                }
                                // …
                                broadcast(member.room(),
                                        ChatRooms.said(member.nick(), member.room(), cleaned, now(), seq.incrementAndGet()));"""),
                        demo("ws:demo", "realtime:chat", "The chat: raw WebSocket rooms",
                                "Open two browser tabs, pick different rooms, and watch messages route only within a room — join/leave system frames, per-room occupancy from the state endpoint, and the raw socket's honest no-auto-reconnect behavior on close."),
                        text("ws:pingpong", """
                                About PING/PONG: the protocol has its own keepalive frames, the container answers pings with pongs BELOW your handler — TextWebSocketHandler never sees them, and this handler contains no ping code on purpose. If you need application-level liveness (usually: to detect and reap half-open TCP connections behind aggressive NATs), you either configure the container's idle timeout or send your own app-level heartbeat frame and time out clients that stop responding to it. The demo chat leans on the container; a production chat usually adds the app-level heartbeat within a few months.""")),
                section("broadcasting-rooms-ceiling", "Broadcasting, rooms, and the in-memory ceiling",
                        "Fan-out is O(room) sends on one serialized wire format; slow clients are dropped, not waited on; and every in-memory registry eventually meets the second server.",
                        text("fan:fanout", """
                                Broadcasting one message to N members is O(N) sends — there is no batching trick, because each send is a separate frame on a separate TCP connection. What you CAN cap is everything around the send loop: serialize ONCE (one ObjectMapper pass, one string, reused for every member — the broadcast method above), and scope the loop to the ROOM, not the server. That second cap is the entire point of rooms: a chat where every message fans out to every connected user dies at thousands of users no matter how fast the sends are, while a room-scoped fan-out costs only that room's members — the express guide's chat learned this with rooms, and the same partitioning returns in every pub/sub system you will ever meet (topic 20's Redis channels are rooms; topic 22's consumer groups are rooms).

                                The registry the loop iterates is a ConcurrentHashMap — sessions join and leave from container threads while broadcasts run on whatever thread a message arrived on, so an unguarded HashMap corrupts under exactly the load you built rooms to survive. Thread-safe registry, one wire string, room-scoped sends: fan-out in three decisions."""),
                        code("fan:broadcast", "java",
                                "guide/presentation/realtime/ChatWebSocketHandler.java — fan-out: serialize once, send O(room) times, never O(server)",
                                "server/src/main/java/guide/presentation/realtime/ChatWebSocketHandler.java", """
                                private void broadcast(String room, ChatMessage message) {
                                    String wire = objectMapper.writeValueAsString(message);
                                    sessions.forEach((session, member) -> {
                                        if (member.room().equals(room)) {
                                            send(session, wire);
                                        }
                                    });
                                }"""),
                        text("fan:send-safety", """
                                The send method hides the two bugs every first WebSocket server ships. First, CONCURRENT SENDS: WebSocketSession.sendMessage is not thread-safe — two threads sending on one session throw IllegalStateException, and it happens the moment a broadcast overlaps a direct reply. The fix is structural: every send to a session passes through synchronized (session), one frame at a time. Second, the SLOW CLIENT: a member whose network is gone or whose reader stopped draining. The container's send buffer (Tomcat buffers outgoing frames per session, bounded) absorbs small hiccups — then throws on overflow, and send surfaces it as IOException. The policy is DROP, not wait: remove the member, close quietly, let the room's next join tell them what they missed. Waiting on a slow client would block the broadcast loop and make ONE bad connection the latency of the WHOLE room — the opposite of the partitioning rooms exist for. The webhooks topic met the same truth from the other side (a consumer that cannot keep up gets backoff and eventually a DLQ); on a push connection the client's loss is simply accepted, because the connection itself is the only delivery mechanism."""),
                        code("fan:send", "java",
                                "guide/presentation/realtime/ChatWebSocketHandler.java — one frame to one session: synchronized (concurrent sends throw), and the drop policy on failure",
                                "server/src/main/java/guide/presentation/realtime/ChatWebSocketHandler.java", """
                                private void send(WebSocketSession session, String wire) {
                                    try {
                                        synchronized (session) {
                                            session.sendMessage(new TextMessage(wire));
                                        }
                                    } catch (IOException | IllegalStateException slowOrGone) {
                                        sessions.remove(session);
                                        closeQuietly(session);
                                    }
                                }"""),
                        text("fan:ceiling", """
                                Every in-memory registry — this session map, the ticker's listener list — carries the same ceiling: it is correct for ONE process. Run two replicas behind a load balancer and ada's socket lives on server A while ben joins room alpha on server B; A's broadcast loop cannot see ben, and the room is silently split. The first mitigation is usually already true: STICKY SESSIONS at the balancer keep each client on one server, which fixes per-client state but not cross-server rooms. The actual escape hatch is an EXTERNAL BROKER: broadcasts publish to a channel (Redis pub/sub — topic 20 built the containers for exactly this shape) and every server subscribes and fans out to its LOCAL members; the room becomes a channel name, the registry stays per-process, and the fan-out cost is one broker hop plus one local send per member. Durability requirements then pull you toward a log (Kafka/RabbitMQ — topic 22's territory): pub/sub forgets instantly, logs retain; chat rarely needs retention, market data sometimes does.

                                The ladder, honestly: one server and an in-memory registry until the second server exists; then a broker; then — only with real durability requirements — a log. Building day one against three replicas is the same over-engineering sin as it is everywhere else in this guide.""")),
                section("reconnection-and-delivery-semantics", "Reconnection and delivery semantics",
                        "At-most-once unless you build otherwise: event ids and a bounded replay window make SSE resume honest; a dropped WebSocket is client discipline; and both lose messages you have not decided how to keep.",
                        text("rc:at-most-once", """
                                The guarantee nobody prints on the box: PUSH IS AT-MOST-ONCE BY DEFAULT. A tick sent to a client that disconnects mid-frame is gone; a chat message whose recipient's battery dies is gone; nobody queues it anywhere. SSE softens exactly one edge of this — the gap between "last event the client received" and "now" — with the id/retry/Last-Event-ID triple: the server stamps every event with an id, the browser remembers the newest one it saw, and on its AUTOMATIC reconnect it presents that id as a request header. The server's whole obligation is to answer "everything after 41" — which is exactly what the feed's replay window does:

                                The ring is bounded at 64 ticks on purpose. A client that reconnects within the window loses nothing; a client that comes back after the window has rolled past gets whatever survived — replays, never blocks, never pretends. Unbounded server-side memory per possible reconnect is not a durability strategy, it is an outage strategy. If you genuinely need "nothing is ever missed," that requirement is called a MESSAGE LOG and topic 22 already built it — streams, consumer groups, and pending entries ARE the durable version of this resume contract, because the consumer's offset lives on the broker, not in a ring buffer's mercy."""),
                        code("rc:ring", "java",
                                "guide/domain/realtime/TickerFeed.java — the resume window: bounded by construction, honest about what it no longer holds",
                                "server/src/main/java/guide/domain/realtime/TickerFeed.java", """
                                public synchronized List<Tick> replayAfter(long lastSeq) {
                                    if (ring.isEmpty() || ring.peekLast().seq() <= lastSeq) {
                                        return List.of();
                                    }
                                    return ring.stream().filter(tick -> tick.seq() > lastSeq).toList();
                                }"""),
                        text("rc:client", """
                                On the client side the reconnect is the browser's job, and the demo is built to make you WATCH it happen: choose limit=20 and the stream ends by design after twenty ticks; the badge flips to reconnecting without one line of your code running, the browser waits the server's retry: 3000 hint, re-subscribes — sending Last-Event-ID unprompted — and the ring replays the gap. That is the client half of the contract, in full:"""),
                        code("rc:eventsource", "ts",
                                "client/src/ui/demos/realtime/ticker_demo.tsx — the client half: named events, and an onerror that is usually just the auto-reconnect",
                                "client/src/ui/demos/realtime/ticker_demo.tsx", """
                                const search = limit === 'endless' ? '' : `?limit=${limit}`;
                                const source = new EventSource(`${config.apiBaseUrl}/demo/realtime/ticker${search}`);
                                sourceRef.current = source;
                                setConnection('connecting');
                                source.onopen = () => setConnection('live');
                                source.addEventListener('tick', (event) => {
                                  const row = JSON.parse((event as MessageEvent<string>).data) as TickRow;
                                  setLatest((previous) => ({ ...previous, [row.symbol]: row }));
                                  setRecent((previous) => [row, ...previous].slice(0, 12));
                                });
                                source.onerror = () => {
                                  setConnection(source.readyState === ES_CONNECTING || source.readyState === ES_OPEN
                                    ? 'reconnecting'
                                    : 'closed');
                                };"""),
                        text("rc:ws-reconnect", """
                                WebSocket has none of this. A dropped raw socket STAYS dropped — no EventSource-equivalent machinery, no automatic retry, no Last-Event-ID header, no retry hint. The chat demo shows the honest version: the badge goes closed and stays there until a human clicks connect again. A production client owns four disciplines the browser used to own for you: reconnect with exponential backoff and jitter (the webhooks topic's curve, now on the client), resubscribe (a new handshake re-runs your join logic — make join idempotent), REPLAY from a cursor (your own resume protocol: the chat's server-assigned seq exists so a future version could), and detect half-open connections with a heartbeat, because a TCP connection that died silently in the middle will otherwise sit "open" until an OS timeout that can outlast your users' patience. Libraries exist for all of this (reconnecting-websocket is the standard one-liner); knowing what they are compensating for is why this topic made you read it first."""),
                        code("rc:state", "java",
                                "guide/presentation/demo/RealtimeDemoController.java — the observability answer: the pause invariant, readable",
                                "server/src/main/java/guide/presentation/demo/RealtimeDemoController.java", """
                                @GetMapping(value = "/ticker/state", produces = "application/json")
                                TickerStateView tickerState() {
                                    return new TickerStateView(sseTickerController.activeEmittersCount(),
                                            tickerFeed.ticksGenerated());
                                }"""),
                        text("rc:honesty", """
                                The closing honesty, stated once where you will find it again: NEITHER TRANSPORT PROMISES DELIVERY. SSE over a flaky network gives you at-most-once plus a bounded maybe (the ring); WebSocket gives you at-most-once, full stop; both will lose messages during your deploys unless you build otherwise. At-least-once push is not a transport feature — it is an architecture: durable storage on the server (an event store, a stream, the outbox pattern topic 22 built), ids the client can present, and a resume path that reads from the DURABLE copy rather than memory. The ticker's ring is the honest teaching version of that ladder's first rung — bounded, explicit, tested. The state endpoint beside it is the other half of the discipline: two numbers that make the feed's behavior observable, because a push system you cannot observe is a push system you cannot trust.""")),
                section("terminal-companion", "Terminal companion",
                        "curl -N is an EventSource with the chrome stripped; websocat is a WebSocket client shaped like a shell — both demo streams, live from the tour.",
                        text("term:tour", """
                                The ticker first. curl's -N flag disables buffering — without it, curl quietly waits and shows you nothing, which is the proxy-buffering lesson from event-stream mechanics re-enacted by your own tooling. Subscribe with a bounded limit so the stream completes on its own, or leave it endless and Ctrl-C:

                                The wire format is the whole protocol — comments (colon lines), the retry hint, then named events with ids. In a second terminal, poll the state endpoint while the first streams and watch activeEmitters and ticksSent move together; kill the stream and watch the gauge drop to zero and the ticks stop accumulating — the pause invariant, observable.

                                For the chat, curl cannot speak WebSocket — reach for websocat (or wscat): `websocat "ws://localhost:8080/ws/demo/chat?room=alpha&nick=terminal-ada"` opens a session whose stdin is the socket — type a JSON line ({"text":"hello from the terminal"}), press enter, and it broadcasts to the room. Run a second websocat in another terminal joined to beta and prove the isolation yourself: alpha's messages never arrive. Then try a hostile room (room=wallstreet) and read the 1008 close reason; and curl the chat state endpoint for per-room occupancy. One honest tooling note: a browser EventSource auto-reconnects, websocat does not — when you kill the server, the terminal just ends, which is the client-discipline lesson in miniature."""),
                        code("term:curl", "bash",
                                "the ticker stream, verbatim — comments, retry, named events with ids",
                                null, """
                                $ curl -sN "http://localhost:8080/api/demo/realtime/ticker?limit=3"
                                : ticker stream open — retry hint follows; comments are heartbeats
                                retry:3000
                                id:41
                                event:tick
                                data:{"seq":41,"symbol":"SDG","price":100.12,"delta":0.12,"at":"2026-10-06T12:00:02.500Z"}

                                id:42
                                event:tick
                                data:{"seq":42,"symbol":"JDK","price":99.88,"delta":-0.1,"at":"2026-10-06T12:00:03Z"}

                                id:43
                                event:tick
                                data:{"seq":43,"symbol":"PG","price":100.03,"delta":0.03,"at":"2026-10-06T12:00:03.500Z"}"""),
                        code("term:state", "bash",
                                "the observability endpoints, while a stream runs and after it ends",
                                null, """
                                $ curl -s http://localhost:8080/api/demo/realtime/ticker/state
                                {"activeEmitters":1,"ticksSent":118}

                                $ curl -s http://localhost:8080/api/demo/realtime/chat/state
                                {"rooms":[{"name":"alpha","sessions":2},{"name":"beta","sessions":1}]}"""),
                        code("term:ws", "bash",
                                "the chat from the terminal — two rooms, one isolation proof",
                                null, """
                                # terminal one
                                $ websocat "ws://localhost:8080/ws/demo/chat?room=alpha&nick=terminal-ada"
                                {"type":"join","from":"system","text":"terminal-ada joined alpha",...}
                                {"text":"hello from the terminal"}

                                # terminal two — a different room hears nothing
                                $ websocat "ws://localhost:8080/ws/demo/chat?room=beta&nick=terminal-ben"

                                # the hostile room: refused with a reason, not redirected
                                $ websocat "ws://localhost:8080/ws/demo/chat?room=wallstreet&nick=gordon"
                                ⚠ connection closed: 1008 (policy violation)""")));
    }
}
