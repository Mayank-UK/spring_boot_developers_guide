package guide.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-matcher tests, ported from the express guide's snippet_sources.spec
 * plus this repo's own java/xml/properties comment families and the edge
 * cases the ratchet depends on (empty snippet, empty file).
 */
class SnippetMatcherTest {

    private final String tsSource = """
            import express from 'express';

            export function createApp(): Express {
              const app = express();
              const docsUrl = config.baseUrl + "/docs";
              app.disable("x-powered-by");
              return app;
            }
            """;

    @Test
    void acceptsAVerbatimSnippet() {
        String snippet = """
                import express from 'express';
                export function createApp(): Express {
                  const app = express();
                  return app;
                }
                """;
        assertTrue(SnippetMatcher.isQuotedFrom(snippet, tsSource, "ts").ok());
    }

    @Test
    void acceptsElisionsGapsAndTrailingTeachingComments() {
        String snippet = """
                import express from 'express'; // the framework, nothing else

                // … lifecycle and config wiring omitted …
                export function createApp(): Express {
                  const app = express();    // one registry object
                  // ...docsUrl setup skipped...
                  app.disable("x-powered-by");
                  return app;               // note: no listen() here
                }
                """;
        assertTrue(SnippetMatcher.isQuotedFrom(snippet, tsSource, "ts").ok());
    }

    @Test
    void rejectsAChangedIdentifier() {
        String source = """
                async function run(id: string) {
                  return getUserById(id);
                }
                """;
        String drifted = """
                async function run(id: string) {
                  return getUserByIds(id);
                }
                """;
        assertFalse(SnippetMatcher.isQuotedFrom(drifted, source, "ts").ok());
    }

    @Test
    void rejectsAChangedNumericLiteral() {
        String source = """
                if (x === 1) {
                  res.status(200);
                }
                """;
        String drifted = """
                if (x === 2) {
                  res.status(200);
                }
                """;
        assertFalse(SnippetMatcher.isQuotedFrom(drifted, source, "ts").ok());
    }

    @Test
    void rejectsReorderedSnippetLines() {
        String source = """
                const a = 1;
                const b = 2;
                const c = 3;
                """;
        String reordered = """
                const a = 1;
                const c = 3;
                const b = 2;
                """;
        assertFalse(SnippetMatcher.isQuotedFrom(reordered, source, "ts").ok());
    }

    @Test
    void doesNotTreatSlashSlashInsideAStringAsAComment() {
        String source = "const docs = 'https://example.com/docs';";
        // Naive trailing-comment stripping would truncate BOTH sides to
        // `const docs = 'https:` and pass. The quote-aware scanner must not.
        String same = "const docs = 'https://example.com/docs';";
        String drifted = "const docs = 'https://example.com/api';";
        assertTrue(SnippetMatcher.isQuotedFrom(same, source, "ts").ok());
        assertFalse(SnippetMatcher.isQuotedFrom(drifted, source, "ts").ok());
    }

    @Test
    void stripsSqlAndYamlCommentsOnBothSides() {
        String sqlSource = """
                -- migration header comment
                CREATE TABLE tags (
                  id INTEGER PRIMARY KEY
                );
                """;
        String sqlSnippet = """
                -- our own teaching header
                CREATE TABLE tags (
                  id INTEGER PRIMARY KEY
                );
                """;
        assertTrue(SnippetMatcher.isQuotedFrom(sqlSnippet, sqlSource, "sql").ok());

        String yamlSource = """
                # shipped comment
                image: node:22-alpine
                ports:
                  - "4000:4000"
                """;
        String yamlSnippet = """
                image: node:22-alpine   # pinned deliberately
                ports:
                  - "4000:4000"
                """;
        assertTrue(SnippetMatcher.isQuotedFrom(yamlSnippet, yamlSource, "yaml").ok());
    }

    @Test
    void keepsTsDecrementsAndHashKeysIntact() {
        assertEquals("retries--;", SnippetMatcher.normalizeSnippetLine("  retries--;", "ts"));
        assertEquals("return this.#limit;", SnippetMatcher.normalizeSnippetLine("  return this.#limit;", "ts"));
        assertEquals("const color = \"#fff\";", SnippetMatcher.normalizeSnippetLine("  const color = \"#fff\";", "ts"));
    }

    @Test
    void stripsJavaLineAndBlockComments() {
        String javaSource = """
                package guide.content;

                // shipped comment
                public final class Registry {
                    /* block comment */ private final int size = 1; // trailing
                    public int size() { return size; }
                }
                """;
        String javaSnippet = """
                package guide.content;
                public final class Registry {   // teaching note
                    // ...internals elided...
                    public int size() { return size; }
                }
                """;
        assertTrue(SnippetMatcher.isQuotedFrom(javaSnippet, javaSource, "java").ok());
        String drifted = """
                package guide.content;
                public final class Registry {
                    public int size() { return size + 1; }
                }
                """;
        assertFalse(SnippetMatcher.isQuotedFrom(drifted, javaSource, "java").ok());
    }

    @Test
    void stripsXmlSameLineComments() {
        String xmlSource = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- project manifest -->
                <project>
                  <modelVersion>4.0.0</modelVersion>
                </project>
                """;
        String xmlSnippet = """
                <?xml version="1.0" encoding="UTF-8"?> <!-- first line of pom.xml -->
                <project>
                  <modelVersion>4.0.0</modelVersion>
                </project>
                """;
        assertTrue(SnippetMatcher.isQuotedFrom(xmlSnippet, xmlSource, "xml").ok());
    }

    @Test
    void stripsPropertiesHashComments() {
        String propertiesSource = """
                # boot settings
                server.port=8080
                management.endpoints.web.exposure.include=health,info
                """;
        String propertiesSnippet = """
                server.port=8080   # fixed by the guide
                management.endpoints.web.exposure.include=health,info
                """;
        assertTrue(SnippetMatcher.isQuotedFrom(propertiesSnippet, propertiesSource, "properties").ok());
    }

    @Test
    void emptySnippetPassesVacuouslyAndEmptyFileFailsAnyContent() {
        assertTrue(SnippetMatcher.isQuotedFrom("", tsSource, "ts").ok());
        assertTrue(SnippetMatcher.isQuotedFrom("\n\n// only comments\n", tsSource, "ts").ok());
        assertFalse(SnippetMatcher.isQuotedFrom("const x = 1;", "", "ts").ok());
        assertFalse(SnippetMatcher.isQuotedFrom("const a = 1;\nconst b = 2;", "const a = 1;", "ts").ok());
    }

    @Test
    void mustSourceClassificationCoversCodeAndConfigLanguagesOnly() {
        for (String language : new String[] {"java", "xml", "yaml", "yml", "sql", "proto", "ts",
                "tsx", "js", "json", "properties", "dockerfile"}) {
            assertTrue(SnippetMatcher.isMustSourceLanguage(language), language + " must be must-source");
        }
        for (String language : new String[] {"bash", "sh", "shell", "console", "text", "txt",
                "http", "markdown"}) {
            assertFalse(SnippetMatcher.isMustSourceLanguage(language), language + " must be exempt");
        }
        assertTrue(SnippetMatcher.isMustSourceLanguage("Java"), "classification is case-insensitive");
        assertFalse(SnippetMatcher.isMustSourceLanguage(null));
    }

    @Test
    void sourcePathSyntaxAcceptsCleanRelativePathsOnly() {
        assertNull(SnippetMatcher.sourcePathError("server/src/main/java/guide/GuideApplication.java"));
        assertNull(SnippetMatcher.sourcePathError("client/src/core/result.ts"));
        assertNull(SnippetMatcher.sourcePathError(".docker/client/default.conf"));

        assertTrue(SnippetMatcher.sourcePathError("/etc/passwd").contains("repo-root-relative"));
        assertTrue(SnippetMatcher.sourcePathError("~/secrets.env").contains("repo-root-relative"));
        assertTrue(SnippetMatcher.sourcePathError("C:\\work\\app.ts").contains("repo-root-relative"));
        assertTrue(SnippetMatcher.sourcePathError("../outside-repo.ts").contains(".."));
        assertTrue(SnippetMatcher.sourcePathError("server/src/../../etc/passwd").contains(".."));
        assertTrue(SnippetMatcher.sourcePathError("server//src/app.ts").contains("clean relative"));
        assertTrue(SnippetMatcher.sourcePathError("./app.ts").contains("clean relative"));
        assertTrue(SnippetMatcher.sourcePathError("src\\app.ts").contains("forward slashes"));
        assertTrue(SnippetMatcher.sourcePathError("").contains("non-empty"));
    }
}
