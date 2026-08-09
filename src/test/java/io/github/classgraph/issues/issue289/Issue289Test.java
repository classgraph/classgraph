package io.github.classgraph.issues.issue289;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue289Test {
    @Test
    public void issue289() {
        try (var scanResult = new ClassGraph()
                .overrideClassLoaders(
                        new URLClassLoader(new URL[] { Issue289Test.class.getClassLoader().getResource("zip64.zip") }))
                .scan()) {
            for (var i = 0; i < 90000; i++) {
                final var resources = scanResult.getResourcesWithPath(i + "");
                assertThat(resources).isNotEmpty();
            }
        }
    }
}
