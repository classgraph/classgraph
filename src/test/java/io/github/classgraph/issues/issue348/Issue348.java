package io.github.classgraph.issues.issue348;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue345.
 */
public class Issue348 {
    /** Test for wildcarded jars. */
    @Test
    public void testWildcard() {
        try (ScanResult scanResult1 = new ClassGraph().whitelistPathsNonRecursive("").scan()) {
            // Find all resources within classpath elements with ".jar" extension
            final List<String> jarResourceUris = scanResult1.getResourcesWithExtension("jar").stream()
                    .map(r -> r.getURI().toString()).collect(Collectors.toList());
            assertThat(jarResourceUris).isNotEmpty();

            try (ScanResult scanResult2 = new ClassGraph().overrideClasspath(jarResourceUris)
                    .whitelistJars("issue*.jar").scan()) {
                // Find all classpath element URIs for non-nested jars
                final List<String> cpUris = scanResult2.getClasspathURIs().stream().map(URI::toString)
                        .filter(u -> !u.contains("!")).collect(Collectors.toList());
                assertThat(cpUris).isNotEmpty();

                // Check that cpUris is a non-empty subset of jarResourceUris
                final Set<String> jarResourceUrisMinusCpUris = new LinkedHashSet<>(jarResourceUris);
                jarResourceUrisMinusCpUris.removeAll(cpUris);
                assertThat(jarResourceUrisMinusCpUris).isNotEmpty();
                assertThat(jarResourceUrisMinusCpUris.size()).isLessThan(jarResourceUris.size());
                final Set<String> cpUrisMinusJarResourceUris = new LinkedHashSet<>(cpUris);
                cpUrisMinusJarResourceUris.removeAll(jarResourceUris);
                assertThat(cpUrisMinusJarResourceUris).isEmpty();

                // Check that cpUris all end with "issue*.jar"
                for (final String uri : cpUris) {
                    final String leaf = uri.substring(uri.lastIndexOf('/') + 1);
                    assertThat(leaf).matches("issue.*\\.jar");
                }
            }
        }
    }
}
