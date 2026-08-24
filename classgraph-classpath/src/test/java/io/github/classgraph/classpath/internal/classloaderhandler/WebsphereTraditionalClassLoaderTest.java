package io.github.classgraph.classpath.internal.classloaderhandler;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ibm.ws.bootstrap.ExtClassLoader;
import com.ibm.ws.classloader.CompoundClassLoader;

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that the classpath of a WebSphere traditional classloader is read from the classpath string it reports,
 * and, for the extension classloader, from its {@code URLClassLoader} URLs as well.
 */
public class WebsphereTraditionalClassLoaderTest {
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
     * The classpath that a compound classloader reports is on the classpath, in the order it reports it.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theClasspathThatACompoundClassLoaderReportsIsOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var first = Files.createDirectory(tempDir.resolve("first"));
        final var second = Files.createDirectory(tempDir.resolve("second"));
        assertThat(locations(new CompoundClassLoader(first + File.pathSeparator + second)))
                .containsExactly(location(first), location(second));
    }

    /**
     * The extension classloader is a {@code URLClassLoader}, so its URLs are on the classpath as well as the
     * classpath string it reports.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theUrlsOfTheExtensionClassLoaderAreOnTheClasspathToo(@TempDir final Path tempDir)
            throws IOException {
        final var fromClassPath = Files.createDirectory(tempDir.resolve("from-classpath"));
        final var fromUrls = Files.createDirectory(tempDir.resolve("from-urls"));
        assertThat(locations(new ExtClassLoader(fromClassPath.toString(), fromUrls.toUri().toURL())))
                .containsExactly(location(fromClassPath), location(fromUrls));
    }

    /**
     * A classloader that reports no classpath at all does not fail the scan.
     */
    @Test
    public void aClassLoaderWithNoClasspathDoesNotThrow() {
        assertThatCode(() -> locations(new CompoundClassLoader(/* classPath = */ null))).doesNotThrowAnyException();
    }
}
