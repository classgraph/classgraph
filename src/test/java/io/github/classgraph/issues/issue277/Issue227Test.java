package io.github.classgraph.issues.issue277;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * https://github.com/classgraph/classgraph/issues/277
 */
public class Issue227Test {
    /**
     * Test no args reject lib or ext jars.
     */
    @Test
    public void testNoArgsRejectLibOrExtJars() {
        new ClassGraph().rejectLibOrExtJars();
    }

    /**
     * Test no args accept lib or ext jars.
     */
    @Test
    public void testNoArgsAcceptLibOrExtJars() {
        new ClassGraph().acceptLibOrExtJars();
    }
}
