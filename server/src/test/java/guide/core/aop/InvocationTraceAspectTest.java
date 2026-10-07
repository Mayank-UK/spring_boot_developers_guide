package guide.core.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the aspect's argument rendering — the safety valve that
 * keeps trace lines useful without ever becoming a payload copy: caps on
 * count and length, size-only summaries for collections and arrays, null
 * handling, and a hostile toString() that throws must degrade instead of
 * breaking the traced call.
 */
class InvocationTraceAspectTest {

    @Test
    void emptyAndNullArgumentsRenderAsEmptyParentheses() {
        assertEquals("()", InvocationTraceAspect.summarizeArguments(null));
        assertEquals("()", InvocationTraceAspect.summarizeArguments(new Object[0]));
    }

    @Test
    void plainArgumentsAreRenderedInOrder() {
        assertEquals("(book, 7)",
                InvocationTraceAspect.summarizeArguments(new Object[] {"book", 7}));
    }

    @Test
    void collectionsAndArraysAreSummarizedBySizeNeverContent() {
        assertEquals("(ListN[size=3])",
                InvocationTraceAspect.summarizeArguments(new Object[] {List.of("a", "b", "c")}));
        assertEquals("(String[][length=2])",
                InvocationTraceAspect.summarizeArguments(new Object[] {new String[] {"x", "y"}}));
    }

    @Test
    void longArgumentsAreTruncatedAtTheCap() {
        String longArgument = "x".repeat(500);

        String summary = InvocationTraceAspect.summarizeArguments(new Object[] {longArgument});

        // parens (2) + cap (60) + ellipsis (3)
        assertTrue(summary.length() <= InvocationTraceAspect.MAX_ARG_LENGTH + 5,
                "summary must be capped, was: " + summary.length());
        assertTrue(summary.endsWith("...)"));
    }

    @Test
    void moreThanMaxArgumentsAreCountedNotRendered() {
        Object[] eight = {"a", "b", "c", "d", "e", "f", "g", "h"};

        String summary = InvocationTraceAspect.summarizeArguments(eight);

        assertEquals("(a, b, c, d, 4 more)", summary);
    }

    @Test
    void aThrowingToStringDegradesToTheClassName() {
        Object hostile = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("boom");
            }
        };

        String summary = InvocationTraceAspect.summarizeArguments(new Object[] {hostile});

        assertTrue(summary.contains("toString failed"), "summary was: " + summary);
    }

    @Test
    void aCollectionWhoseToStringWouldBeHugeStillReportsOnlyItsSize() {
        Collection<Integer> big = java.util.stream.IntStream.range(0, 10_000)
                .boxed().toList();

        String summary = InvocationTraceAspect.summarizeArguments(new Object[] {big});

        assertTrue(summary.contains("size=10000"));
        assertTrue(summary.length() <= InvocationTraceAspect.MAX_ARG_LENGTH + 4);
    }
}
