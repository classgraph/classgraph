package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@link Path} is an {@link Iterable} of its own name elements, so a single {@link Path} is passed to the
 * {@code Iterable<?>} overload of {@link ClassGraph#overrideClasspath(Iterable)}, rather than to the
 * {@code Object...} overload. Its name elements are not classpath entries, so the path was split into pieces and
 * nothing was scanned.
 */
public class OverrideClasspathPathTest {
    /** A single {@link Path} is one classpath entry. */
    @Test
    public void aSinglePathIsOneClasspathEntry(@TempDir final Path tempDir) throws IOException {
        final Path dir = Files.createDirectories(tempDir.resolve("classpathElement"));
        Files.write(dir.resolve("marker.txt"), "marker".getBytes(StandardCharsets.UTF_8));

        try (ScanResult scanResult = new ClassGraph().overrideClasspath(dir).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("marker.txt");
        }
    }

    /** A list of {@link Path} is still one classpath entry per element. */
    @Test
    public void aListOfPathsIsOneClasspathEntryPerElement(@TempDir final Path tempDir) throws IOException {
        final Path dir0 = Files.createDirectories(tempDir.resolve("classpathElement0"));
        final Path dir1 = Files.createDirectories(tempDir.resolve("classpathElement1"));

        // Classpath entries are canonicalized, so the expected paths have to be canonicalized too -- on macOS the
        // temp directory is reached through the symlink /var -> /private/var, and on Windows through an 8.3 short
        // name (C:\Users\RUNNER~1).
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(Arrays.asList(dir0, dir1)).scan()) {
            assertThat(scanResult.getClasspathFiles()).containsExactly(dir0.toRealPath().toFile(),
                    dir1.toRealPath().toFile());
        }
    }
}
