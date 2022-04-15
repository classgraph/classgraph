package io.github.classgraph.issues.issue673;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

class Issue673Test {
    @Test
    void testResourcesCanBeRead() {
        // a has Class-Path manifest entry that points to b, b points to c
        final URL aURL = Issue673Test.class.getClassLoader().getResource("issue673/a.zip");
        assertThat(aURL != null);
        final URL bURL = Issue673Test.class.getClassLoader().getResource("issue673/b.zip");
        assertThat(bURL != null);

        // This succeeded before issue 673 was fixed 
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(bURL, aURL).scan()) {
            assertThat(scanResult.getClasspathFiles().stream().map(f -> f.getName()).collect(Collectors.toList()))
                    .isEqualTo(Arrays.asList("b.zip", "c.zip", "a.zip"));
            assertThat(scanResult.getAllResources().getPaths()).contains("C");
        }

        // This failed before issue 673 was fixed 
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(aURL, bURL).scan()) {
            assertThat(scanResult.getClasspathFiles().stream().map(f -> f.getName()).collect(Collectors.toList()))
                    .isEqualTo(Arrays.asList("a.zip", "b.zip", "c.zip"));
            assertThat(scanResult.getAllResources().getPaths()).contains("C");
        }
    }
}
