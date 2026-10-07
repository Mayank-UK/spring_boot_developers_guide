package guide.content;

/**
 * One renderable block inside a section. The wire always carries a
 * {@code kind} discriminator — "text", "code", or "demo" — emitted by each
 * record's kind() accessor (annotated for Jackson).
 */
public sealed interface ContentBlock permits TextBlock, CodeBlock, DemoBlock {

    /** The wire discriminator: "text" | "code" | "demo". */
    String kind();

    /** Block ids are unique within their topic (enforced by ContentRegistry). */
    String id();
}
