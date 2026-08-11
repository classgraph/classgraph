package io.github.classgraph.issues.issue83;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;

public class Issue83Test {
    /** The Constant jarPathURL. */
    private static final URL jarPathURL = Issue83Test.class.getClassLoader().getResource("nested-jars-level1.zip");

    /**
     * Jar accept.
     */
    @Test
    public void jarAccept() {
        assertThat(jarPathURL).isNotNull();
        try (var scanResult = new ClassGraph().overrideClasspath(jarPathURL).acceptJars("nested-jars-level1.zip")
                .scan()) {
            final var paths = scanResult.getAllResources().stream().map(Resource::getPath).toList();
            assertThat(paths).contains("level2.jar");
        }
    }

    /**
     * Jar reject.
     */
    @Test
    public void jarReject() {
        assertThat(jarPathURL).isNotNull();
        try (var scanResult = new ClassGraph().overrideClasspath(jarPathURL).rejectJars("nested-jars-level1.zip")
                .scan()) {
            final var paths = scanResult.getAllResources().stream().map(Resource::getPath).toList();
            assertThat(paths).doesNotContain("level2.jar");
        }
    }
}
