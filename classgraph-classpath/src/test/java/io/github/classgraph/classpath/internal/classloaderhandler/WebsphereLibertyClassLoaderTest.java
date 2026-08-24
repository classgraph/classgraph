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
        try (var classpath = new ClasspathFinder().enableClassLoaders(classLoader).find()) {
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
     * An application delegates to the classloaders of the libraries it is configured to use, and the precedence
     * configured for each library decides whether it is searched before or after the application's own classpath.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theClasspathsOfTheLibrariesTheApplicationDelegatesToAreOnTheClasspathInPrecedenceOrder(
            @TempDir final Path tempDir) throws IOException {
        final var beforeDir = Files.createDirectory(tempDir.resolve("before"));
        final var appDir = Files.createDirectory(tempDir.resolve("app"));
        final var afterDir = Files.createDirectory(tempDir.resolve("after"));
        final var classLoader = new AppClassLoader(new SmartClassPath(List.of(url(appDir))), /* parent = */ null)
                .delegatingBeforeAppTo(
                        new AppClassLoader(new SmartClassPath(List.of(url(beforeDir))), /* parent = */ null))
                .delegatingAfterAppTo(
                        new AppClassLoader(new SmartClassPath(List.of(url(afterDir))), /* parent = */ null));
        assertThat(locations(classLoader)).containsExactly(location(beforeDir), location(appDir),
                location(afterDir));
    }

    /**
     * A thread context classloader searches the classloaders it is chained to after its own, so their classpaths
     * are on the classpath too.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theClasspathsOfTheClassLoadersAThreadContextClassLoaderFallsThroughToAreOnTheClasspath(
            @TempDir final Path tempDir) throws IOException {
        final var appDir = Files.createDirectory(tempDir.resolve("app"));
        final var followOnDir = Files.createDirectory(tempDir.resolve("follow-on"));
        final var appLoader = new AppClassLoader(new SmartClassPath(List.of(url(appDir))), /* parent = */ null);
        final var followOn = new AppClassLoader(new SmartClassPath(List.of(url(followOnDir))), /* parent = */ null);
        assertThat(locations(new ThreadContextClassLoader(appLoader, /* parent = */ null).followedBy(followOn)))
                .containsExactly(location(appDir), location(followOnDir));
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
