package guide.domain.thirdparty;

/**
 * How an integration's adapter is currently answering — the third-party
 * topic's central safety vocabulary:
 *
 * <ul>
 *   <li><b>FAKE</b> — an in-process adapter the repo owns: no provider, no
 *       network, no credential. The default the demos and tests run.</li>
 *   <li><b>SANDBOX</b> — the provider's TEST WORLD, modeled in-process:
 *       real-shaped semantics (test vectors, greylisting, provider limits)
 *       with the same zero-network guarantee. The step you graduate to
 *       before live.</li>
 *   <li><b>LIVE</b> — the real provider, real key, real consequences. This
 *       guide CONSTRUCTS a live-capable mail adapter to prove the drop-in,
 *       but never calls it: live is a wiring + key decision, never a
 *       runtime dial a demo flips by accident.</li>
 * </ul>
 */
public enum Posture {

    FAKE("fake"),
    SANDBOX("sandbox"),
    LIVE("live");

    private final String wireName;

    Posture(String wireName) {
        this.wireName = wireName;
    }

    /** The name the content API and demo surfaces carry. */
    public String wireName() {
        return wireName;
    }

    /** Parses the wire name; null (not an exception) when unknown — the edge validates. */
    public static Posture fromWireName(String name) {
        for (Posture posture : values()) {
            if (posture.wireName.equals(name)) {
                return posture;
            }
        }
        return null;
    }
}
