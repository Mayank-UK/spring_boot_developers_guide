package guide.content;

/**
 * Versioning of the content API surface: bumped when response shapes change,
 * so the client can migrate deliberately. Every content response carries it.
 */
public final class ContentModel {

    public static final int CONTENT_API_VERSION = 1;

    private ContentModel() {
    }
}
