package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.AbstractList;

import org.junit.jupiter.api.Test;

/**
 * {@link ScanResult#getAllClassesAsMap()} holds the same classes as {@link ScanResult#getAllClasses()}.
 */
class GetAllClassesAsMapTest {
    /** A class with an external superclass. */
    public abstract static class Target extends AbstractList<String> {
    }

    /** Without external classes enabled, the external superclasses are in neither. */
    @Test
    void externalClassesAreLeftOut() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo()
                .acceptClasses(Target.class.getName()).scan()) {
            assertThat(scanResult.getAllClassesAsMap().keySet())
                    .containsExactlyElementsOf(scanResult.getAllClasses().getNames())
                    .containsExactly(Target.class.getName());
        }
    }

    /** With external classes enabled, the external superclasses are in both. */
    @Test
    void externalClassesAreIncludedIfEnabled() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableExternalClasses()
                .acceptClasses(Target.class.getName()).scan()) {
            assertThat(scanResult.getAllClassesAsMap().keySet())
                    .containsExactlyElementsOf(scanResult.getAllClasses().getNames())
                    .contains(Target.class.getName(), AbstractList.class.getName());
        }
    }
}
