package io.github.classgraph.classpath.internal.classloaderhandler;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.osgi.container.Module;
import org.eclipse.osgi.container.ModuleContainer;
import org.eclipse.osgi.container.ModuleDatabase;
import org.eclipse.osgi.internal.framework.BundleContextImpl;
import org.eclipse.osgi.internal.framework.EquinoxBundle;
import org.eclipse.osgi.internal.framework.EquinoxContainer;
import org.eclipse.osgi.internal.loader.BundleLoader;
import org.eclipse.osgi.internal.loader.EquinoxClassLoader;
import org.eclipse.osgi.internal.loader.classpath.ClasspathEntry;
import org.eclipse.osgi.internal.loader.classpath.ClasspathManager;
import org.eclipse.osgi.internal.loader.classpath.FragmentClasspath;
import org.eclipse.osgi.storage.Storage;
import org.eclipse.osgi.storage.bundlefile.BundleFile;
import org.eclipse.osgi.storage.bundlefile.BundleFileWrapper;
import org.eclipse.osgi.storage.bundlefile.DirBundleFile;
import org.eclipse.osgi.storage.bundlefile.NestedDirBundleFile;
import org.eclipse.osgi.storage.bundlefile.ZipBundleFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that the classpath of an Equinox (OSGi) bundle is read from the bundle files that its classpath is built
 * from, and that the bundles installed alongside it in the framework are found too.
 */
public class EquinoxClassLoaderTest {
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
     * A bundle whose classpath is the bundle directory itself is on the classpath as that directory.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void aBundleServedFromADirectoryIsOnTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var bundleDir = Files.createDirectory(tempDir.resolve("bundle"));
        final var classLoader = new EquinoxClassLoader(
                new ClasspathManager(new ClasspathEntry(new DirBundleFile(bundleDir.toFile()))));
        assertThat(locations(classLoader)).containsExactly(location(bundleDir));
    }

    /**
     * A bundle that puts its classes in a subdirectory of the bundle directory, as a bundle built by Eclipse does,
     * is on the classpath as that subdirectory rather than as the bundle directory.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void aBundlesClasspathElementWithinTheBundleDirectoryIsOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var bundleDir = Files.createDirectory(tempDir.resolve("bundle"));
        Files.createDirectory(bundleDir.resolve("bin"));
        final var classLoader = new EquinoxClassLoader(
                new ClasspathManager(new ClasspathEntry(new DirBundleFile(bundleDir.toFile()).serving("bin"))));
        assertThat(locations(classLoader)).containsExactly(location(bundleDir) + "/bin");
    }

    /**
     * A bundle whose classes live in a directory nested inside a bundle jar is on the classpath as a path within
     * that jar, so that the jar is opened and the nested directory read out of it.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void aDirectoryNestedInsideABundleJarIsOnTheClasspathAsAPathWithinTheJar(@TempDir final Path tempDir)
            throws IOException {
        final var bundleJar = Files.createFile(tempDir.resolve("bundle.jar"));
        final var extractedDir = Files.createDirectory(tempDir.resolve("extracted"));
        final var classLoader = new EquinoxClassLoader(new ClasspathManager(new ClasspathEntry(
                new NestedDirBundleFile(extractedDir.toFile(), new ZipBundleFile(bundleJar.toFile()), "bin"))));
        assertThat(locations(classLoader)).containsExactly(location(bundleJar) + "!/bin");
    }

    /**
     * A nested directory whose enclosing bundle file is a directory rather than a jar is on the classpath as an
     * ordinary path, not as a path within an archive.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void aDirectoryNestedInsideABundleDirectoryIsOnTheClasspathAsAnOrdinaryPath(@TempDir final Path tempDir)
            throws IOException {
        final var bundleDir = Files.createDirectory(tempDir.resolve("bundle"));
        final var nestedDir = Files.createDirectory(bundleDir.resolve("bin"));
        final var classLoader = new EquinoxClassLoader(new ClasspathManager(new ClasspathEntry(
                new NestedDirBundleFile(bundleDir.toFile(), new DirBundleFile(bundleDir.toFile()), "bin"))));
        assertThat(locations(classLoader)).containsExactly(location(nestedDir));
    }

    /**
     * Bundle files are chained -- a bundle file can wrap another and can be followed by another -- and every bundle
     * file in the chain contributes to the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void everyBundleFileInAChainIsOnTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var outer = Files.createDirectory(tempDir.resolve("outer"));
        final var inner = Files.createDirectory(tempDir.resolve("inner"));
        final var sibling = Files.createDirectory(tempDir.resolve("sibling"));
        final var bundleFile = new DirBundleFile(outer.toFile()).wrapping(new DirBundleFile(inner.toFile()))
                .followedBy(new DirBundleFile(sibling.toFile()));
        final var classLoader = new EquinoxClassLoader(new ClasspathManager(new ClasspathEntry(bundleFile)));
        assertThat(locations(classLoader)).containsExactly(location(outer), location(inner), location(sibling));
    }

    /**
     * A framework extension can install a hook that wraps a bundle file in a {@code BundleFileWrapper}. The wrapper
     * copies only the base file of the bundle file it wraps, so the bundle file it delegates to has to be read as
     * well, or the classpath element within the bundle would be lost.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void theBundleFileThatAWrapperDelegatesToIsOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var bundleDir = Files.createDirectory(tempDir.resolve("bundle"));
        final var classesDir = Files.createDirectory(bundleDir.resolve("bin"));
        final var wrapper = new BundleFileWrapper(new DirBundleFile(bundleDir.toFile()).serving("bin"));
        final var classLoader = new EquinoxClassLoader(new ClasspathManager(new ClasspathEntry(wrapper)));
        assertThat(locations(classLoader)).containsExactly(location(bundleDir), location(classesDir));
    }

    /**
     * A chain of bundle files that loops back on itself is followed only once round, rather than forever.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void aBundleFileChainThatLoopsBackOnItselfTerminates(@TempDir final Path tempDir) throws IOException {
        final var first = Files.createDirectory(tempDir.resolve("first"));
        final var second = Files.createDirectory(tempDir.resolve("second"));
        final BundleFile firstBundleFile = new DirBundleFile(first.toFile());
        final BundleFile secondBundleFile = new DirBundleFile(second.toFile());
        firstBundleFile.followedBy(secondBundleFile);
        secondBundleFile.followedBy(firstBundleFile);
        final var classLoader = new EquinoxClassLoader(new ClasspathManager(new ClasspathEntry(firstBundleFile)));
        assertThat(locations(classLoader)).containsExactly(location(first), location(second));
    }

    /**
     * A fragment bundle contributes its classpath to the bundle it is attached to.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void aFragmentsClasspathIsOnTheClasspathToo(@TempDir final Path tempDir) throws IOException {
        final var hostDir = Files.createDirectory(tempDir.resolve("host"));
        final var fragmentDir = Files.createDirectory(tempDir.resolve("fragment"));
        final var manager = new ClasspathManager(new ClasspathEntry(new DirBundleFile(hostDir.toFile())))
                .withFragments(new FragmentClasspath(new ClasspathEntry(new DirBundleFile(fragmentDir.toFile()))));
        assertThat(locations(new EquinoxClassLoader(manager))).containsExactly(location(hostDir),
                location(fragmentDir));
    }

    /**
     * A bundle file that is not backed by anything on disk contributes no classpath element, rather than failing
     * the scan.
     */
    @Test
    public void aBundleFileWithNothingOnDiskContributesNoClasspathElement() {
        final var classLoader = new EquinoxClassLoader(
                new ClasspathManager(new ClasspathEntry(new BundleFile(/* basefile = */ null))));
        assertThat(locations(classLoader)).isEmpty();
    }

    /**
     * A bundle with no classpath at all does not fail the scan.
     */
    @Test
    public void aBundleWithNoClasspathDoesNotThrow() {
        assertThatCode(() -> locations(new EquinoxClassLoader(/* manager = */ null))).doesNotThrowAnyException();
    }

    /**
     * Every bundle installed in the framework alongside the bundle being scanned is on the classpath, since an OSGi
     * bundle's own classpath says nothing about the bundles it imports packages from.
     *
     * @param tempDir
     *            a temporary directory to create the bundles in.
     * @throws IOException
     *             if the bundles could not be created.
     */
    @Test
    public void theOtherBundlesInstalledInTheFrameworkAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var bundleDir = Files.createDirectory(tempDir.resolve("bundle"));
        final var otherJar = Files.createFile(tempDir.resolve("other.jar"));
        final var systemModule = new Module("System Bundle");
        final var systemBundle = new EquinoxBundle(systemModule);
        final var otherBundle = new EquinoxBundle(new Module("initial@reference:file:" + location(otherJar)));
        systemBundle.inContext(new BundleContextImpl(systemBundle, otherBundle));
        final var classLoader = new EquinoxClassLoader(
                new ClasspathManager(new ClasspathEntry(new DirBundleFile(bundleDir.toFile()))))
                .withFramework(new BundleLoader(
                        new EquinoxContainer(new Storage(new ModuleContainer(new ModuleDatabase(systemModule))))));
        assertThat(locations(classLoader)).containsExactly(location(bundleDir), location(otherJar));
    }
}
