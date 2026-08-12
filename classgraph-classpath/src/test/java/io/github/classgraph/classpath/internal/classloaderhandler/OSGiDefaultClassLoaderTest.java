package io.github.classgraph.classpath.internal.classloaderhandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.osgi.internal.baseadaptor.BundleFile;
import org.eclipse.osgi.internal.baseadaptor.ClasspathEntry;
import org.eclipse.osgi.internal.baseadaptor.ClasspathManager;
import org.eclipse.osgi.internal.baseadaptor.DefaultClassLoader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that the classpath of an OSGi bundle is read from the bundle classloader's classpath manager.
 */
public class OSGiDefaultClassLoaderTest {
    /**
     * Find the classpath element locations of a classloader.
     *
     * @param classLoader
     *            the classloader.
     * @return the classpath element locations.
     */
    private static List<String> locations(final ClassLoader classLoader) {
        try (var classpath = new ClasspathFinder().overrideClassLoaders(classLoader).find()) {
            return classpath.getLocations();
        }
    }

    /**
     * The location that a file or directory is reported as.
     *
     * @param path
     *            the file or directory.
     * @return the location.
     */
    private static String location(final Path path) {
        return path.toFile().getPath().replace(File.separatorChar, '/');
    }

    /**
     * A bundle classloader with the given classpath entries.
     *
     * @param entries
     *            the classpath entries.
     * @return the classloader.
     */
    private static DefaultClassLoader bundleWithClasspathEntries(final ClasspathEntry... entries) {
        return new DefaultClassLoader(new ClasspathManager(entries));
    }

    /**
     * A classpath entry whose contents are read from the given file or directory.
     *
     * @param path
     *            the file or directory, or null if the entry has none.
     * @return the classpath entry.
     */
    private static ClasspathEntry entryReadFrom(final @Nullable Path path) {
        return new ClasspathEntry(new BundleFile(path == null ? null : path.toFile()));
    }

    /**
     * The file or directory that each of a bundle's classpath entries is read from is on the classpath, in the
     * order the bundle lists them.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void theFilesThatABundlesClasspathEntriesAreReadFromAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var bundleJar = Files.createFile(tempDir.resolve("bundle.jar"));
        final var classesDir = Files.createDirectory(tempDir.resolve("bin"));
        assertThat(locations(bundleWithClasspathEntries(entryReadFrom(bundleJar), entryReadFrom(classesDir))))
                .containsExactly(location(bundleJar), location(classesDir));
    }

    /**
     * A classpath entry that is not read from anything on disk -- a bundle served from memory, say -- contributes
     * no classpath entry.
     */
    @Test
    public void aClasspathEntryThatIsNotReadFromAFileIsSkipped() {
        assertThat(locations(bundleWithClasspathEntries(entryReadFrom(null)))).isEmpty();
    }

    /**
     * A bundle classloader that has no classpath manager, because the bundle has not been resolved yet, does not
     * fail the scan.
     */
    @Test
    public void aBundleWithNoClasspathManagerDoesNotThrow() {
        assertThatCode(() -> locations(new DefaultClassLoader(/* classpathManager = */ null)))
                .doesNotThrowAnyException();
    }
}
