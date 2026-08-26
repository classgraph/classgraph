package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Test that when one classpath element is nested within another, the outer classpath element does not scan the
 * contents of the inner classpath element a second time.
 */
public class NestedClasspathRootTest {
    /** A jar containing only {@code pkg/Record.class} and {@code pkg/Record.java}. */
    private static final String JAR = "src/test/resources/record.jar";

    /** A package root within a jar should be scanned only by the nested classpath element. */
    @Test
    public void packageRootNestedWithinJar() {
        try (ScanResult scanResult = new ClassGraph()
                .overrideClasspath(JAR + File.pathSeparator + JAR + "!/pkg").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder("Record.class",
                    "Record.java");
        }
    }

    /** A directory within a directory classpath element should be scanned only by the nested classpath element. */
    @Test
    public void dirNestedWithinDir() {
        try (ScanResult scanResult = new ClassGraph()
                .overrideClasspath("src/test/resources" + File.pathSeparator + "src/test/resources/issue673")
                .scan()) {
            assertThat(scanResult.getAllResources().getPaths()).contains("a.zip").doesNotContain("issue673/a.zip");
        }
    }

    /**
     * Nesting should still be found when a classpath element that is not nested within the outer element sorts
     * between the two. Classpath elements are compared in lexicographic order, and every character below
     * {@code '/'} puts a sibling of the outer element between it and the element nested within it, so scanning
     * stopped at the sibling and the nested element's contents were scanned twice.
     */
    @Test
    public void dirNestedWithinDirWithSiblingBetween(@TempDir final Path tempDir) throws IOException {
        // "classes-extra" sorts after "classes" and before "classes/sub", since '-' is below '/'
        final Path outer = Files.createDirectories(tempDir.resolve("classes"));
        final Path sibling = Files.createDirectories(tempDir.resolve("classes-extra"));
        final Path nested = Files.createDirectories(outer.resolve("sub"));
        Files.write(nested.resolve("res.txt"), "res".getBytes(StandardCharsets.UTF_8));

        try (ScanResult scanResult = new ClassGraph()
                .overrideClasspath(outer + File.pathSeparator + sibling + File.pathSeparator + nested)
                .acceptPaths("").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("res.txt");
        }
    }
}
