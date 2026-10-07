package guide.content;

import com.fasterxml.jackson.annotation.JsonValue;

/** Nav groups in display order — ids and titles match the client nav registry. */
public enum TopicGroupId {
    FUNDAMENTALS("fundamentals", "Fundamentals", 1),
    MIDDLEWARE_REQUEST("middleware-request", "Middleware & Request Handling", 2),
    APIS("apis", "APIs", 3),
    DATA("data", "Data", 4),
    CACHING_MESSAGING("caching-messaging", "Caching & Messaging", 5),
    AUTH_SECURITY("auth-security", "Auth & Security", 6),
    FILES_MEDIA("files-media", "Files & Media", 7),
    INTERNATIONALIZATION("internationalization", "Internationalization", 8),
    REALTIME_JOBS("realtime-jobs", "Real-time & Jobs", 9),
    PLATFORM("platform", "Platform", 10);

    private final String wireName;
    private final String title;
    private final int order;

    TopicGroupId(String wireName, String title, int order) {
        this.wireName = wireName;
        this.title = title;
        this.order = order;
    }

    /** Serialized on the wire as the lowercase-hyphen id, e.g. "auth-security". */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    public String title() {
        return title;
    }

    public int order() {
        return order;
    }
}
