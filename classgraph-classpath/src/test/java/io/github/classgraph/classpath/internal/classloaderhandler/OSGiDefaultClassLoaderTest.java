package io.github.classgraph.classpath.internal.classloaderhandler;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.osgi.internal.baseadaptor.BundleFile;
import org.eclipse.osgi.internal.baseadaptor.ClasspathEntry;
import org.eclipse.osgi.internal.baseadaptor.ClasspathManager;
import org.eclipse.osgi.internal.baseadaptor.DefaultClassLoader;
import org.eclipse.osgi.internal.baseadaptor.FragmentClasspath;
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
        try (var classpath = new ClasspathFinder().enableClassLoaders(classLoader).find()) {
            return classpath.getLocations();
        }
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
     * A classpath entry that serves only a subdirectory of the bundle, which is what a {@code Bundle-ClassPath}
     * entry of {@code "bin/"} produces, puts that subdirectory on the classpath rather than the whole bundle.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void theSubdirectoryOfTheBundleThatAClasspathEntryServesIsOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var bundleDir = Files.createDirectory(tempDir.resolve("bundle"));
        final var classesDir = Files.createDirectory(bundleDir.resolve("bin"));
        final var entry = new ClasspathEntry(new BundleFile(bundleDir.toFile()).serving("bin"));
        assertThat(locations(bundleWithClasspathEntries(entry))).containsExactly(location(classesDir));
    }

    /**
     * A framework extension can replace a bundle file with a chain of wrappers around it. Every bundle file in the
     * chain is on the classpath, since only the innermost one knows which subdirectory of the bundle is served.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void theBundleFilesThatAWrapperChainWrapsAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var bundleDir = Files.createDirectory(tempDir.resolve("bundle"));
        final var classesDir = Files.createDirectory(bundleDir.resolve("bin"));
        final var wrappedJar = Files.createFile(tempDir.resolve("wrapped.jar"));
        final var chain = new BundleFile(/* baseFile = */ null)
                .wrapping(new BundleFile(bundleDir.toFile()).serving("bin"))
                .followedBy(new BundleFile(wrappedJar.toFile()));
        assertThat(locations(bundleWithClasspathEntries(new ClasspathEntry(chain))))
                .containsExactly(location(classesDir), location(wrappedJar));
    }

    /**
     * The classpath entries contributed by the bundle's fragments are on the classpath, after the bundle's own.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void theClasspathEntriesOfTheBundlesFragmentsAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var bundleJar = Files.createFile(tempDir.resolve("bundle.jar"));
        final var fragmentJar = Files.createFile(tempDir.resolve("fragment.jar"));
        final var classpathManager = new ClasspathManager(entryReadFrom(bundleJar))
                .withFragments(new FragmentClasspath(entryReadFrom(fragmentJar)));
        assertThat(locations(new DefaultClassLoader(classpathManager))).containsExactly(location(bundleJar),
                location(fragmentJar));
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
