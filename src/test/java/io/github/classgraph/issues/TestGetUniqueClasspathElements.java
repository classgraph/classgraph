package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;

import org.junit.Test;

import io.github.classgraph.ClassGraph;

/**
 * The Class TestGetUniqueClasspathElements.
 */
public class TestGetUniqueClasspathElements {

    /**
     * Test get unique classpath elements.
     */
    @Test
    public void testGetUniqueClasspathElements() {
        final List<File> classpathElements = new ClassGraph().whitelistPackages("com.xyz").getClasspathFiles();
        assertThat(classpathElements).isNotEmpty();
    }
}
