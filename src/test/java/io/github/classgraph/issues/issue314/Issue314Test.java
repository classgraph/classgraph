package io.github.classgraph.issues.issue314;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * The subclass relationship between two private nested classes is found by a
 * scan.
 */
public class Issue314Test {
    /**
     * The Class A.
     */
    private static class A {
    }

    /**
     * The Class B.
     */
    private static class B extends A {
    }

    /**
     * Both nested classes are scanned, and B is found as the only subclass of A.
     */
    @Test
    public void nestedClassHierarchy() {
        try (var scanResult = new ClassGraph().acceptPackages(Issue314Test.class.getPackage().getName())
                .enableAllInfo().scan()) {
            assertThat(scanResult.getClassInfo(A.class.getName())).isNotNull();
            assertThat(scanResult.getClassInfo(B.class.getName())).isNotNull();
            assertThat(scanResult.getSubclasses(A.class).getNames()).containsOnly(B.class.getName());
        }
    }
}
