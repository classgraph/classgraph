package io.github.classgraph.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;

import org.junit.Test;

import io.github.classgraph.ClassGraph;

public class TestGetUniqueClasspathElements {
    @Test
    public void testGetUniqueClasspathElements() {
        final List<File> classpathElements = new ClassGraph().whitelistPackages("com.xyz").getClasspathFiles();
        assertThat(classpathElements).isNotEmpty();
    }
}
