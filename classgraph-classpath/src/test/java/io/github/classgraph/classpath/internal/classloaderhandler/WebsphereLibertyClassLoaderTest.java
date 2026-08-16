package io.github.classgraph.classpath.internal.classloaderhandler;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ibm.ws.classloading.internal.AppClassLoader;
import com.ibm.ws.classloading.internal.Container;
import com.ibm.ws.classloading.internal.ContainerDelegate;
import com.ibm.ws.classloading.internal.SmartClassPath;
import com.ibm.ws.classloading.internal.ThreadContextClassLoader;
import com.ibm.ws.classloading.internal.UniversalContainer;

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that the classpath of a WebSphere Liberty application is read from its {@code SmartClassPath}, whichever of
 * the several ways of reporting it the running Liberty version supports.
 */
public class WebsphereLibertyClassLoaderTest {
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
     * The URL of a file or directory, as Liberty reports its containers' locations.
     *
     * @param path
     *            the file or directory.
     * @return the URL.
     * @throws IOException
     *             if the URL could not be created.
     */
    private static Object url(final Path path) throws IOException {
        return path.toUri().toURL();
    }

    /**
     * The application's classpath is the classpath its {@code SmartClassPath} reports, in the order it reports it,
     * since classpath order decides which copy of a duplicated class is loaded.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theApplicationsClasspathIsReportedInOrder(@TempDir final Path tempDir) throws IOException {
        final var first = Files.createDirectory(tempDir.resolve("first"));
        final var second = Files.createDirectory(tempDir.resolve("second"));
        final var smartClassPath = new SmartClassPath(List.of(url(first), url(second)));
        assertThat(locations(new AppClassLoader(smartClassPath, /* parent = */ null)))
                .containsExactly(location(first), location(second));
    }

    /**
     * A {@code SmartClassPath} that groups its URLs by container reports a collection of collections, which is
     * flattened into a single classpath.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void groupedClasspathUrlsAreFlattened(@TempDir final Path tempDir) throws IOException {
        final var first = Files.createDirectory(tempDir.resolve("first"));
        final var second = Files.createDirectory(tempDir.resolve("second"));
        final var third = Files.createDirectory(tempDir.resolve("third"));
        final var smartClassPath = new SmartClassPath(
                List.of(List.of(url(first), url(second)), List.of(url(third))));
        assertThat(locations(new AppClassLoader(smartClassPath, /* parent = */ null)))
                .containsExactly(location(first), location(second), location(third));
    }

    /**
     * A thread context classloader has no classpath of its own -- it serves the classpath of the application
     * classloader it wraps.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void aThreadContextClassLoaderServesTheApplicationsClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var appDir = Files.createDirectory(tempDir.resolve("app"));
        final var appLoader = new AppClassLoader(new SmartClassPath(List.of(url(appDir))), /* parent = */ null);
        assertThat(locations(new ThreadContextClassLoader(appLoader, /* parent = */ null)))
                .containsExactly(location(appDir));
    }

    /**
     * On a Liberty version whose {@code SmartClassPath} cannot report the classpath directly, the classpath is
     * assembled from the containers that make it up.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theClasspathIsAssembledFromTheContainersWhenItCannotBeReportedDirectly(@TempDir final Path tempDir)
            throws IOException {
        final var first = Files.createDirectory(tempDir.resolve("first"));
        final var second = Files.createDirectory(tempDir.resolve("second"));
        final var smartClassPath = new SmartClassPath(/* classPathURLs = */ null)
                .addContainer(new UniversalContainer(List.of(url(first)), /* container = */ null))
                .addContainer(new UniversalContainer(List.of(url(second)), /* container = */ null));
        assertThat(locations(new AppClassLoader(smartClassPath, /* parent = */ null)))
                .containsExactly(location(first), location(second));
    }

    /**
     * A container that does not report its own locations is read through the container it wraps.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void aContainerIsReadThroughTheContainerItWraps(@TempDir final Path tempDir) throws IOException {
        final var appDir = Files.createDirectory(tempDir.resolve("app"));
        final var smartClassPath = new SmartClassPath(/* classPathURLs = */ null)
                .addContainer(new UniversalContainer(new Container(List.of(url(appDir)), /* delegate = */ null)));
        assertThat(locations(new AppClassLoader(smartClassPath, /* parent = */ null)))
                .containsExactly(location(appDir));
    }

    /**
     * A container that reports no locations at all is read through its delegate, which knows the directory the
     * container serves resources from.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void aContainerServingADirectoryIsReadThroughItsDelegate(@TempDir final Path tempDir)
            throws IOException {
        final var appDir = Files.createDirectory(tempDir.resolve("app"));
        final var delegate = new ContainerDelegate(appDir.toString(), /* base = */ null);
        final var smartClassPath = new SmartClassPath(/* classPathURLs = */ null)
                .addContainer(new UniversalContainer(new Container(delegate)));
        assertThat(locations(new AppClassLoader(smartClassPath, /* parent = */ null)))
                .containsExactly(location(appDir));
    }

    /**
     * A container that serves resources from an archive rather than a directory is read through the archive file
     * that its delegate holds.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void aContainerServingAnArchiveIsReadThroughItsArchiveFile(@TempDir final Path tempDir)
            throws IOException {
        final var appJar = Files.createFile(tempDir.resolve("app.jar"));
        final var delegate = new ContainerDelegate(/* path = */ null,
                new ContainerDelegate.ArchiveBase(appJar.toFile()));
        final var smartClassPath = new SmartClassPath(/* classPathURLs = */ null)
                .addContainer(new UniversalContainer(new Container(delegate)));
        assertThat(locations(new AppClassLoader(smartClassPath, /* parent = */ null)))
                .containsExactly(location(appJar));
    }

    /**
     * A container that reports nothing anywhere is skipped, rather than failing the scan.
     */
    @Test
    public void aContainerThatReportsNothingIsSkipped() {
        final var smartClassPath = new SmartClassPath(/* classPathURLs = */ null)
                .addContainer(new UniversalContainer(new Container(new ContainerDelegate(null, null))));
        assertThat(locations(new AppClassLoader(smartClassPath, /* parent = */ null))).isEmpty();
    }

    /**
     * An application classloader with no classpath at all does not fail the scan.
     */
    @Test
    public void anApplicationWithNoClasspathDoesNotThrow() {
        assertThatCode(() -> locations(new AppClassLoader(/* smartClassPath = */ null, /* parent = */ null)))
                .doesNotThrowAnyException();
    }
}
