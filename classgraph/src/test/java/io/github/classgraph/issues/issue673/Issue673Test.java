package io.github.classgraph.issues.issue673;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

class Issue673Test {
    @Test
    void testResourcesCanBeRead() {
        // a has Class-Path manifest entry that points to b, b points to c
        final var aURL = Issue673Test.class.getClassLoader().getResource("issue673/a.zip");
        assertThat(aURL).isNotNull();
        final var bURL = Issue673Test.class.getClassLoader().getResource("issue673/b.zip");
        assertThat(bURL).isNotNull();

        // This succeeded before issue 673 was fixed
        try (var scanResult = new ClassGraph().overrideClasspath(bURL, aURL).scan()) {
            assertThat(scanResult.getClasspathFiles().stream().map(File::getName).toList())
                    .isEqualTo(List.of("b.zip", "c.zip", "a.zip"));
            assertThat(scanResult.getAllResources().getPaths()).contains("C");
        }

        // This failed before issue 673 was fixed
        try (var scanResult = new ClassGraph().overrideClasspath(aURL, bURL).scan()) {
            assertThat(scanResult.getClasspathFiles().stream().map(File::getName).toList())
                    .isEqualTo(List.of("a.zip", "b.zip", "c.zip"));
            assertThat(scanResult.getAllResources().getPaths()).contains("C");
        }
    }
}
