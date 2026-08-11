package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A classpath entry that reaches a directory through a symlink followed by {@code ".."} has to be scanned as the
 * directory the filesystem reaches through it, which is the directory the JVM's own classloader loads classes from.
 */
public class SymlinkedClasspathEntryTest {
    /**
     * Create a symlink, or skip the test if the filesystem does not allow it (creating a symlink needs a privilege
     * that is not granted by default on Windows).
     *
     * @param link
     *            the symlink to create
     * @param target
     *            the target of the symlink
     * @return the symlink
     */
    private static Path createSymbolicLinkOrSkip(final Path link, final Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            abort("Symlinks cannot be created: " + e);
            return link;
        }
    }

    /**
     * After a symlinked directory, {@code ".."} names the parent of the directory the symlink points at, not the
     * parent of the symlink, so the classpath entry {@code "link/../classes"} names {@code "real/classes"} and not
     * {@code "classes"}. Only the filesystem knows this, so the {@code ".."} must not be collapsed textually.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the directory tree could not be created
     */
    @Test
    public void aClasspathEntryIsResolvedTheWayTheFilesystemResolvesIt(@TempDir final Path tempDir)
            throws IOException {
        // tempDir/real/classes/resource.txt, tempDir/real/other, and tempDir/link -> tempDir/real/other
        final var realDir = Files.createDirectory(tempDir.resolve("real"));
        final var classesDir = Files.createDirectory(realDir.resolve("classes"));
        Files.write(classesDir.resolve("resource.txt"), "content".getBytes(StandardCharsets.UTF_8));
        final var linkedDir = createSymbolicLinkOrSkip(tempDir.resolve("link"),
                Files.createDirectory(realDir.resolve("other")));
        // A decoy at tempDir/classes, which is what the classpath entry would name if ".." were collapsed textually
        Files.createDirectory(tempDir.resolve("classes"));

        final var classpathEntry = linkedDir.resolve("../classes").toString();
        try (var scanResult = new ClassGraph().overrideClasspath(classpathEntry).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly("resource.txt");
            assertThat(scanResult.getClasspathFiles()).containsExactly(classesDir.toRealPath().toFile());
        }
    }
}
