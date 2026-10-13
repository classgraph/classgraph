package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.vfs.Vfs;

/**
 * Test that a path in a ClassGraph filesystem view can be a classpath entry, and is reported by the location of the
 * directory or jarfile it is a view of.
 */
public class VfsPathClasspathEntryTest {
    /** A jar containing only {@code pkg/Record.class} and {@code pkg/Record.java}. */
    private static final Path JAR = Paths.get("src/test/resources/record.jar");

    /**
     * Scan a jarfile and a directory named by paths of a filesystem view.
     *
     * @param tempDir
     *            the directory the view is a view of.
     * @throws IOException
     *             if the fixture could not be written.
     */
    @Test
    void jarAndDirectoryInAView(@TempDir final Path tempDir) throws IOException {
        final var dir = tempDir.toRealPath();
        Files.copy(JAR, dir.resolve("record.jar"));
        Files.writeString(Files.createDirectory(dir.resolve("classes")).resolve("res.txt"), "res");
        try (var vfs = new Vfs()) {
            final var view = vfs.open(dir).asFileSystem();
            try (var scanResult = new ClassGraph().enableClasspathEntries(view.getPath("/record.jar"))
                    .acceptPaths("").scan()) {
                assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder("pkg/Record.class",
                        "pkg/Record.java");
                assertThat(scanResult.getClasspathURIs()).hasSize(1);
                assertThat(Paths.get(scanResult.getClasspathURIs().get(0))).isEqualTo(dir.resolve("record.jar"));
            }
            try (var scanResult = new ClassGraph().enableClasspathEntries(view.getPath("/classes")).acceptPaths("")
                    .scan()) {
                assertThat(scanResult.getAllResources().getPaths()).containsExactly("res.txt");
                assertThat(scanResult.getClasspathURIs()).hasSize(1);
                assertThat(Paths.get(scanResult.getClasspathURIs().get(0))).isEqualTo(dir.resolve("classes"));
            }
        }
    }
}
