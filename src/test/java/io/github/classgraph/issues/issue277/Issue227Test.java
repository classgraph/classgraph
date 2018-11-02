package io.github.classgraph.issues.issue277;

import org.junit.Test;

import io.github.classgraph.ClassGraph;

/**
 * https://github.com/classgraph/classgraph/issues/277
 */
public class Issue227Test {

    @Test
    public void testNoArgsBlacklistLibOrExtJars() {
        new ClassGraph().blacklistLibOrExtJars();
    }

    @Test
    public void testNoArgsWhitelistLibOrExtJars() {
        new ClassGraph().whitelistLibOrExtJars();
    }
}
