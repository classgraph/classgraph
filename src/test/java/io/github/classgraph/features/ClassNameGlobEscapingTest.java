package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * A class name glob containing a regexp metacharacter other than '.' had that character copied into the compiled
 * pattern unescaped, where it was interpreted as regexp syntax. '$' separates the parts of the binary name of an
 * inner class, and as a regexp it is an end-of-input anchor, so a glob naming an inner class matched nothing.
 */
public class ClassNameGlobEscapingTest {
    /** An inner class, so that its binary name contains a '$'. */
    public static class Inner {
    }

    /** The binary name of {@link Inner}. */
    private static final String INNER = ClassNameGlobEscapingTest.class.getName() + "$Inner";

    /** A '$' in a class name glob must be matched literally, not as an end-of-input anchor. */
    @Test
    public void dollarInClassNameGlobIsMatchedLiterally() {
        try (ScanResult scanResult = new ClassGraph().acceptClasses(INNER.substring(0, INNER.length() - 1) + "*")
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).contains(INNER);
        }
    }
}
