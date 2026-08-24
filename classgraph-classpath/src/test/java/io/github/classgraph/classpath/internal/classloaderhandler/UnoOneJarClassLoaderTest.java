package io.github.classgraph.classpath.internal.classloaderhandler;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that the classpath of an application packaged as a single jar by Uno-Jar or One-Jar is recovered, both when
 * the classloader knows the jar it runs from and when the jar and any extra classpath entries were named on the
 * command line, which is how both tools pass them.
 */
public class UnoOneJarClassLoaderTest {
    /** The system properties that Uno-Jar and One-Jar name their classpath in. */
    private static final List<String> CLASSPATH_PROPERTIES = List.of("uno-jar.jar.path", "uno-jar.class.path",
            "one-jar.jar.path", "one-jar.class.path");

    /** The values that the classpath properties had before the test, so that they can be restored after it. */
    private final Map<String, String> propertyValuesBeforeTest = new LinkedHashMap<>();

    /** Clear the classpath properties, remembering what they held, so that each test starts from a known state. */
    @BeforeEach
    public void clearClasspathProperties() {
        for (final String property : CLASSPATH_PROPERTIES) {
            final var value = System.getProperty(property);
            if (value != null) {
                propertyValuesBeforeTest.put(property, value);
                System.clearProperty(property);
            }
        }
    }

    /** Restore the classpath properties to what they held before the test. */
    @AfterEach
    public void restoreClasspathProperties() {
        for (final String property : CLASSPATH_PROPERTIES) {
            System.clearProperty(property);
        }
        propertyValuesBeforeTest.forEach(System::setProperty);
    }

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
     * The jar that an Uno-Jar application runs from is on the classpath, when its classloader knows which jar that
     * is.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theJarThatAnUnoJarApplicationRunsFromIsOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var appJar = Files.createFile(tempDir.resolve("app.jar"));
        assertThat(locations(new com.needhamsoftware.unojar.JarClassLoader(appJar.toString())))
                .containsExactly(location(appJar));
    }

    /**
     * The jar that an Uno-Jar application runs from is on the classpath when it was named on the command line,
     * which is how the jar is passed when it is not the jar the JVM was started from.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theJarThatAnUnoJarApplicationWasGivenOnTheCommandLineIsOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var appJar = Files.createFile(tempDir.resolve("app.jar"));
        System.setProperty("uno-jar.jar.path", appJar.toString());
        assertThat(locations(new com.needhamsoftware.unojar.JarClassLoader(/* oneJarPath = */ null)))
                .containsExactly(location(appJar));
    }

    /**
     * The jar that a One-Jar application runs from is on the classpath when it was named on the command line.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theJarThatAOneJarApplicationWasGivenOnTheCommandLineIsOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var appJar = Files.createFile(tempDir.resolve("app.jar"));
        System.setProperty("one-jar.jar.path", appJar.toString());
        assertThat(locations(new com.simontuffs.onejar.JarClassLoader())).containsExactly(location(appJar));
    }

    /**
     * Uno-Jar takes extra classpath entries on the command line, separated by {@code '|'} rather than by the
     * platform's path separator. All of them are on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theExtraClasspathEntriesOfAnUnoJarApplicationAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var libJar = Files.createFile(tempDir.resolve("lib.jar"));
        final var classesDir = Files.createDirectory(tempDir.resolve("classes"));
        System.setProperty("uno-jar.class.path", libJar + "|" + classesDir);
        assertThat(locations(new com.needhamsoftware.unojar.JarClassLoader(/* oneJarPath = */ null)))
                .containsExactly(location(libJar), location(classesDir));
    }

    /**
     * One-Jar takes extra classpath entries on the command line, separated by {@code '|'} rather than by the
     * platform's path separator. All of them are on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theExtraClasspathEntriesOfAOneJarApplicationAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var libJar = Files.createFile(tempDir.resolve("lib.jar"));
        final var classesDir = Files.createDirectory(tempDir.resolve("classes"));
        System.setProperty("one-jar.class.path", libJar + "|" + classesDir);
        assertThat(locations(new com.simontuffs.onejar.JarClassLoader())).containsExactly(location(libJar),
                location(classesDir));
    }

    /**
     * A classloader that does not know the jar it runs from, and was given no classpath on the command line, has
     * nothing on its classpath -- the jar it runs from is on {@code java.class.path}, which is read separately.
     */
    @Test
    public void aClassLoaderThatWasGivenNoClasspathReportsNoClasspathElements() {
        assertThat(locations(new com.needhamsoftware.unojar.JarClassLoader(/* oneJarPath = */ null))).isEmpty();
    }
}
