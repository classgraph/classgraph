package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * TestGetUniqueClasspathElements.
 */
class TestGetUniqueClasspathElements {
    /**
     * Test get unique classpath elements.
     */
    @Test
    void testGetUniqueClasspathElements() {
        final var classpathElements = new ClassGraph().enableClasspath().acceptPackages("com.xyz")
                .getClasspathFiles();
        assertThat(classpathElements).isNotEmpty();
    }
}
