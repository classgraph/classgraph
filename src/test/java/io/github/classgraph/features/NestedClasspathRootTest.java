package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * Test that when one classpath element is nested within another, the outer classpath element does not scan the
 * contents of the inner classpath element a second time.
 */
public class NestedClasspathRootTest {
    /** A jar containing only {@code pkg/Record.class} and {@code pkg/Record.java}. */
    private static final String JAR = "src/test/resources/record.jar";

    /** A package root within a jar should be scanned only by the nested classpath element. */
    @Test
    void packageRootNestedWithinJar() {
        try (var scanResult = new ClassGraph().overrideClasspath(JAR + File.pathSeparator + JAR + "!/pkg").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder("Record.class",
                    "Record.java");
        }
    }

    /** A directory within a directory classpath element should be scanned only by the nested classpath element. */
    @Test
    void dirNestedWithinDir() {
        try (var scanResult = new ClassGraph()
                .overrideClasspath("src/test/resources" + File.pathSeparator + "src/test/resources/issue673")
                .scan()) {
            assertThat(scanResult.getAllResources().getPaths()).contains("a.zip").doesNotContain("issue673/a.zip");
        }
    }
}
