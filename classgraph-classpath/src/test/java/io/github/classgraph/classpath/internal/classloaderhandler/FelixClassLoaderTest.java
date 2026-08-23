package io.github.classgraph.classpath.internal.classloaderhandler;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.felix.framework.BundleRevisionImpl;
import org.apache.felix.framework.BundleWiringImpl;
import org.apache.felix.framework.BundleWiringImpl.BundleClassLoader;
import org.apache.felix.framework.cache.Content;
import org.apache.felix.framework.cache.ContentDirectoryContent;
import org.apache.felix.framework.util.MultiReleaseContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that the classpath of an Apache Felix (OSGi) bundle is read from the bundle's own contents, the jars
 * embedded in it, and the bundles it imports packages from.
 */
public class FelixClassLoaderTest {
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
     * The bundle jar that a bundle's classes are loaded from is on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void theBundleJarIsOnTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var bundleJar = Files.createFile(tempDir.resolve("bundle.jar"));
        final var wiring = new BundleWiringImpl(new BundleRevisionImpl(new Content(bundleJar.toFile())));
        assertThat(locations(new BundleClassLoader(wiring))).containsExactly(location(bundleJar));
    }

    /**
     * A bundle can carry its dependencies inside itself, listed in its {@code Bundle-ClassPath} header. Those are
     * on the classpath as well as the bundle jar, and the bundle jar is not listed twice for being both the
     * bundle's own contents and the first entry of its content path.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void theJarsEmbeddedInTheBundleAreOnTheClasspathToo(@TempDir final Path tempDir) throws IOException {
        final var bundleJar = Files.createFile(tempDir.resolve("bundle.jar"));
        final var embeddedJar = Files.createFile(tempDir.resolve("embedded.jar"));
        final var revision = new BundleRevisionImpl(new Content(bundleJar.toFile()))
                .embedding(new Content(embeddedJar.toFile()));
        assertThat(locations(new BundleClassLoader(new BundleWiringImpl(revision))))
                .containsExactly(location(bundleJar), location(embeddedJar));
    }

    /**
     * A {@code Bundle-ClassPath} entry can name a directory inside the bundle jar rather than an embedded jar. It
     * is on the classpath as that directory within the jar, since the directory has no file of its own on disk.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void aDirectoryInsideTheBundleJarIsOnTheClasspathAsAPathWithinTheJar(@TempDir final Path tempDir)
            throws IOException {
        final var bundleJar = tempDir.resolve("bundle.jar");
        try (var zipOutputStream = new ZipOutputStream(Files.newOutputStream(bundleJar))) {
            zipOutputStream.putNextEntry(new ZipEntry("embedded/"));
            zipOutputStream.closeEntry();
        }
        final var bundleContent = new Content(bundleJar.toFile());
        final var revision = new BundleRevisionImpl(bundleContent)
                .embedding(new ContentDirectoryContent(bundleContent, "embedded"));
        assertThat(locations(new BundleClassLoader(new BundleWiringImpl(revision))))
                .containsExactly(location(bundleJar), location(bundleJar) + "!/embedded");
    }

    /**
     * Felix wraps the contents of a multi-release bundle in a {@code MultiReleaseContent}, which has no file of its
     * own. The bundle is on the classpath as the file of the content that the wrapper serves.
     *
     * @param tempDir
     *            a temporary directory to create the bundle in.
     * @throws IOException
     *             if the bundle could not be created.
     */
    @Test
    public void aMultiReleaseBundleIsOnTheClasspathAsTheJarItsWrapperServes(@TempDir final Path tempDir)
            throws IOException {
        final var bundleJar = Files.createFile(tempDir.resolve("bundle.jar"));
        final var revision = new BundleRevisionImpl(new MultiReleaseContent(new Content(bundleJar.toFile())));
        assertThat(locations(new BundleClassLoader(new BundleWiringImpl(revision))))
                .containsExactly(location(bundleJar));
    }

    /**
     * An OSGi bundle loads classes from the bundles it imports packages from, so those bundles are on the classpath
     * too.
     *
     * @param tempDir
     *            a temporary directory to create the bundles in.
     * @throws IOException
     *             if the bundles could not be created.
     */
    @Test
    public void theBundlesThatThisBundleImportsPackagesFromAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var bundleJar = Files.createFile(tempDir.resolve("bundle.jar"));
        final var providerJar = Files.createFile(tempDir.resolve("provider.jar"));
        final var provider = new BundleWiringImpl(new BundleRevisionImpl(new Content(providerJar.toFile())));
        final var wiring = new BundleWiringImpl(new BundleRevisionImpl(new Content(bundleJar.toFile())))
                .requiring(provider);
        assertThat(locations(new BundleClassLoader(wiring))).containsExactly(location(bundleJar),
                location(providerJar));
    }

    /**
     * A bundle whose contents are not backed by a file contributes no classpath element, rather than failing the
     * scan.
     */
    @Test
    public void aBundleWithNoFileOnDiskContributesNoClasspathElement() {
        final var revision = new BundleRevisionImpl(new Content(/* file = */ null));
        assertThat(locations(new BundleClassLoader(new BundleWiringImpl(revision)))).isEmpty();
    }

    /**
     * A classloader whose bundle has no wiring at all does not fail the scan.
     */
    @Test
    public void aBundleWithNoWiringDoesNotThrow() {
        assertThatCode(() -> locations(new BundleClassLoader(/* wiring = */ null))).doesNotThrowAnyException();
    }
}
