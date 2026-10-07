package guide.domain.arch;

import java.util.Optional;

/**
 * The two real endpoints the arch tracer can drive — chosen because together
 * they cross every layer of the app: filters, interceptor, a thin controller,
 * a use case, and a scenario-routing adapter. The labels the tracer renders
 * (controller, use case, adapter) describe REAL classes on those paths; the
 * story step evidence (markers, invocation records, timings) is measured,
 * never asserted.
 */
public enum ProbeTarget {

    /** The guide serving itself: this very topic's detail document. */
    CONTENT("content", "/api/content/architecture",
            "ContentController.detail", "GetTopicById", "ContentRepositoryAdapter",
            "the guide serving this very page — one topic's full section tree"),

    /** Topic 11's paged collection — fake or PostgreSQL by scenario flip. */
    POSTS("posts", "/api/demo/api/posts?page=0&size=3",
            "ApiDesignDemoController.list", "ListPosts", "ScenarioRoutingPostRepository",
            "topic 11's paged collection — the adapter is the scenario router");

    private final String id;
    private final String path;
    private final String controller;
    private final String useCase;
    private final String adapter;
    private final String note;

    ProbeTarget(String id, String path, String controller, String useCase, String adapter, String note) {
        this.id = id;
        this.path = path;
        this.controller = controller;
        this.useCase = useCase;
        this.adapter = adapter;
        this.note = note;
    }

    public String id() {
        return id;
    }

    /** The request path (with query string) the tracer drives. */
    public String path() {
        return path;
    }

    /** The real handler method on that path, for the story's controller step. */
    public String controller() {
        return controller;
    }

    /** The real use case that handler calls, for the story's use-case step. */
    public String useCase() {
        return useCase;
    }

    /** The real adapter that answers the port, for the story's adapter step. */
    public String adapter() {
        return adapter;
    }

    /** One line of context the demo renders under the target picker. */
    public String note() {
        return note;
    }

    /** Case-sensitive lookup by wire id; empty for unknown targets. */
    public static Optional<ProbeTarget> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (ProbeTarget target : values()) {
            if (target.id.equals(id)) {
                return Optional.of(target);
            }
        }
        return Optional.empty();
    }
}
