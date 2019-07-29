package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;

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
        final List<File> classpathElements = new ClassGraph().whitelistPackages("com.xyz").getClasspathFiles();
        assertThat(classpathElements).isNotEmpty();
    }
}
