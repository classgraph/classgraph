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
 * directory the platform reaches through it, which is the directory the JVM's own classloader loads classes from.
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
     * A classpath entry containing a {@code ".."} after a symlinked directory must be scanned as the directory the
     * platform reaches through it, which is the directory the JVM's own classloader loads classes from. The two
     * platforms differ, and each is checked against itself here: on Linux and macOS the filesystem resolves the
     * {@code ".."}, so {@code "link/../classes"} is {@code "real/classes"}, while on Windows the path APIs collapse
     * it lexically, so it is the {@code "classes"} directory beside the symlink. Both were confirmed by running
     * {@code java -cp "link/../classes"} on all three platforms.
     *
     * <p>
     * The two directories hold differently named files, so the scan result says which of them was reached.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the directory tree could not be created
     */
    @Test
    public void aClasspathEntryIsResolvedTheWayThePlatformResolvesIt(@TempDir final Path tempDir)
            throws IOException {
        // tempDir/real/classes/in-real.txt, tempDir/real/other, tempDir/link -> tempDir/real/other, and a second
        // "classes" directory beside the symlink, which is the one a Windows classloader reaches
        final var realDir = Files.createDirectory(tempDir.resolve("real"));
        final var realClassesDir = Files.createDirectory(realDir.resolve("classes"));
        Files.write(realClassesDir.resolve("in-real.txt"), "content".getBytes(StandardCharsets.UTF_8));
        final var linkedDir = createSymbolicLinkOrSkip(tempDir.resolve("link"),
                Files.createDirectory(realDir.resolve("other")));
        final var besideLinkClassesDir = Files.createDirectory(tempDir.resolve("classes"));
        Files.write(besideLinkClassesDir.resolve("beside-link.txt"), "content".getBytes(StandardCharsets.UTF_8));

        final var classpathEntry = linkedDir.resolve("../classes");
        // Whichever of the two directories this platform reaches, that is the one that has to be scanned
        final var expectedDir = classpathEntry.toRealPath();
        final var expectedResource = expectedDir.equals(realClassesDir.toRealPath()) ? "in-real.txt"
                : "beside-link.txt";

        try (var scanResult = new ClassGraph().overrideClasspath(classpathEntry.toString()).scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly(expectedResource);
            assertThat(scanResult.getClasspathFiles()).containsExactly(expectedDir.toFile());
        }
    }
}
