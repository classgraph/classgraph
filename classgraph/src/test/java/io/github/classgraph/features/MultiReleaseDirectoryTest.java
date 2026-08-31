package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * Multi-release is a jar-only feature: the {@code Multi-Release: true} manifest entry is only read from a jar's
 * manifest, and the JVM loads the base version of a resource from a directory classpath element even when a
 * versioned copy is present alongside it. A directory scan therefore skips versioned sections, so that it reports
 * what the JVM would actually load. Compare {@link MultiReleaseJarTest}, where the same layout inside a jar makes
 * the version 9 copy win.
 */
public class MultiReleaseDirectoryTest {
    /** The path of the base resource. */
    private static final String RESOURCE_PATH = "resource.txt";

    /** The path of the version 9 copy of the same resource. */
    private static final String VERSIONED_RESOURCE_PATH = "META-INF/versions/9/resource.txt";

    /**
     * Write a directory holding a base resource and a version 9 copy of it, plus a manifest declaring the directory
     * to be multi-release (which the JVM ignores for a directory).
     *
     * @param dir
     *            the directory to write into.
     * @throws IOException
     *             if the directory could not be written.
     */
    private static void writeVersionedDir(final Path dir) throws IOException {
        Files.createDirectories(dir.resolve("META-INF/versions/9"));
        Files.writeString(dir.resolve(RESOURCE_PATH), "base");
        Files.writeString(dir.resolve(VERSIONED_RESOURCE_PATH), "9");
        Files.writeString(dir.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nMulti-Release: true\n");
    }

    /**
     * Read the single resource at the given path, or null if there is no such resource.
     *
     * @param dir
     *            the directory classpath element to scan.
     * @param path
     *            the resource path to read.
     * @param disableMultiReleaseVersions
     *            whether to scan with {@link ClassGraph#disableMultiReleaseVersions()}.
     * @return the content of the resource, or null if there is no resource at that path.
     * @throws IOException
     *             if the resource could not be read.
     */
    private static String readResource(final Path dir, final String path, final boolean disableMultiReleaseVersions)
            throws IOException {
        final var classGraph = new ClassGraph().enableClasspathEntries(dir.toString());
        if (disableMultiReleaseVersions) {
            classGraph.disableMultiReleaseVersions();
        }
        try (var scanResult = classGraph.scan()) {
            final var resources = scanResult.getResourcesWithPath(path);
            if (resources.isEmpty()) {
                return null;
            }
            assertThat(resources).hasSize(1);
            return new String(resources.get(0).load()).trim();
        }
    }

    /** By default, a versioned copy in a directory is skipped, and the base copy is the one reported. */
    @Test
    void versionedSectionOfADirectoryIsSkippedByDefault(@TempDir final Path tempDir) throws IOException {
        writeVersionedDir(tempDir);
        assertThat(readResource(tempDir, RESOURCE_PATH, false)).isEqualTo("base");
        assertThat(readResource(tempDir, VERSIONED_RESOURCE_PATH, false)).isNull();
    }

    /**
     * With {@link ClassGraph#disableMultiReleaseVersions()}, every version is reported under its own versioned
     * path, exactly as for a jar.
     */
    @Test
    void disableMultiReleaseVersionsReportsEveryVersion(@TempDir final Path tempDir) throws IOException {
        writeVersionedDir(tempDir);
        assertThat(readResource(tempDir, RESOURCE_PATH, true)).isEqualTo("base");
        assertThat(readResource(tempDir, VERSIONED_RESOURCE_PATH, true)).isEqualTo("9");
    }
}
