package guide.presentation.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Topic 28: the registry that mounts the raw chat handler at
 * /ws/demo/chat. Two deliberate choices worth quoting:
 *
 * - @EnableWebSocket is the whole wiring — one interface method maps a
 *   path to a handler. No subprotocol (STOMP), no broker: the raw
 *   WebSocketHandler API is the topic's subject.
 * - ALLOWED ORIGINS is enumerated, not "*". A browser's handshake carries
 *   an Origin header, and any page on the web can try to open a socket to
 *   your server; the default (same-origin only) is correct for production
 *   and wrong for this demo only because the dev client (5176) and the
 *   server (8080) are different ports. The list below names exactly the
 *   dev and preview origins this guide's containers use — replacing it
 *   with "*" is the difference between "any website can chat as my user"
 *   and not. Non-browser clients (the tests, websocat) send no Origin
 *   header and are not affected either way.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler) {
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/demo/chat")
                .setAllowedOrigins(
                        "http://localhost:5176", "http://127.0.0.1:5176",
                        "http://localhost:8081", "http://127.0.0.1:8081");
    }
}
