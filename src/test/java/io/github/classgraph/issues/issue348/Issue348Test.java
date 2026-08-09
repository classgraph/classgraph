package io.github.classgraph.issues.issue348;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.LinkedHashSet;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue348Test {
    /** Test for wildcarded jars. */
    @Test
    public void testWildcard() {
        try (var scanResult1 = new ClassGraph().acceptPathsNonRecursive("").scan()) {
            // Find all resources within classpath elements with ".jar" extension
            final var jarResourceUris = scanResult1.getResourcesWithExtension("jar").stream()
                    .map(r -> r.getURI().toString().replace(":///", ":/").replace("://", ":/")).toList();
            assertThat(jarResourceUris).isNotEmpty();

            try (var scanResult2 = new ClassGraph().overrideClasspath(jarResourceUris).acceptJars("issue*.jar")
                    .scan()) {
                // Find all classpath element URIs for non-nested jars
                final var cpUris = scanResult2.getClasspathURIs().stream().map(URI::toString)
                        .filter(u -> !u.contains("!")).map(u -> u.replace(":///", ":/").replace("://", ":/"))
                        .toList();
                assertThat(cpUris).isNotEmpty();

                // Check that cpUris is a non-empty subset of jarResourceUris
                final var jarResourceUrisMinusCpUris = new LinkedHashSet<>(jarResourceUris);
                jarResourceUrisMinusCpUris.removeAll(cpUris);
                assertThat(jarResourceUrisMinusCpUris).isNotEmpty();
                assertThat(jarResourceUrisMinusCpUris.size()).isLessThan(jarResourceUris.size());
                final var cpUrisMinusJarResourceUris = new LinkedHashSet<>(cpUris);
                cpUrisMinusJarResourceUris.removeAll(jarResourceUris);
                assertThat(cpUrisMinusJarResourceUris).isEmpty();

                // Check that cpUris all end with "issue*.jar"
                for (final String uri : cpUris) {
                    final var leaf = uri.substring(uri.lastIndexOf('/') + 1);
                    assertThat(leaf).matches("issue.*\\.jar");
                }
            }
        }
    }
}
