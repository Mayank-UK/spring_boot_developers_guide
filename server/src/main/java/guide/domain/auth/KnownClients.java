package guide.domain.auth;

import java.util.List;
import java.util.Map;

/**
 * The client registry — the authorization server's list of apps it is
 * willing to talk to. OAuth grants access ONLY to known clients: the
 * redirect_uri a client presents at exchange time must be on the list
 * registered for THAT client_id, or the dance is over (the open-redirect
 * vulnerability otherwise: an attacker's authorization code delivered to
 * an attacker's server). One teaching client is seeded; the shape is the
 * lesson, not the count.
 */
public final class KnownClients {

    /** The demo's first-party client: this guide's own console. */
    public static final String CONSOLE_CLIENT_ID = "sdg-console";

    private static final Map<String, ClientApp> CLIENTS = Map.of(
            CONSOLE_CLIENT_ID, new ClientApp(CONSOLE_CLIENT_ID, "SDG Guide Console",
                    List.of("http://localhost:5176/oauth/callback",
                            "http://localhost:8080/api/demo/auth/oauth/sink")));

    public record ClientApp(String clientId, String name, List<String> allowedRedirectUris) {
    }

    public ClientApp find(String clientId) {
        return CLIENTS.get(clientId);
    }

    public boolean redirectAllowed(String clientId, String redirectUri) {
        ClientApp client = CLIENTS.get(clientId);
        return client != null && client.allowedRedirectUris().contains(redirectUri);
    }
}
