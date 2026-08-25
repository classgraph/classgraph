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

import io.github.classgraph.classpath.ClasspathFinder;
import io.quarkus.bootstrap.classloading.DirectoryClassPathElement;
import io.quarkus.bootstrap.classloading.JarClassPathElement;
import io.quarkus.bootstrap.classloading.PathTreeClassPathElement;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.bootstrap.runner.JarResource;
import io.quarkus.bootstrap.runner.RunnerClassLoader;
import io.quarkus.runner.RuntimeClassLoader;

/**
 * Tests that the classpath of a Quarkus application is read from its classloader, for each of the three
 * classloaders Quarkus has used and each of the ways they have reported their classpath elements.
 */
public class QuarkusClassLoaderTest {
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
     * The jars and directories of an application built with Quarkus prior to 3.11 are on the classpath, in the
     * order the classloader lists them.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theJarsAndDirectoriesOfAnOlderQuarkusApplicationAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var jar = Files.createFile(tempDir.resolve("app.jar"));
        final var classesDir = Files.createDirectory(tempDir.resolve("classes"));
        final var classLoader = new QuarkusClassLoader().serving(new JarClassPathElement(jar.toFile()),
                new DirectoryClassPathElement(classesDir));
        assertThat(locations(classLoader)).containsExactly(location(jar), location(classesDir));
    }

    /**
     * Newer Quarkus versions report the root of each classpath element through a method rather than a field, and
     * those roots are on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theRootsOfANewerQuarkusApplicationsClasspathElementsAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var jar = Files.createFile(tempDir.resolve("app.jar"));
        final var classesDir = Files.createDirectory(tempDir.resolve("classes"));
        final var classLoader = new QuarkusClassLoader().serving(new PathTreeClassPathElement(jar),
                new PathTreeClassPathElement(classesDir));
        assertThat(locations(classLoader)).containsExactly(location(jar), location(classesDir));
    }

    /**
     * A classpath element that serves something other than a file or directory on disk -- a class it generates in
     * memory, say -- contributes no classpath entry.
     */
    @Test
    public void aClasspathElementThatServesNothingOnDiskIsSkipped() {
        final var classLoader = new QuarkusClassLoader().serving(new PathTreeClassPathElement(null),
                new PathTreeClassPathElement("generated classes"));
        assertThat(locations(classLoader)).isEmpty();
    }

    /**
     * Quarkus 3.16 and later split the classpath elements into a normal-priority and a lesser-priority list. Both
     * are on the classpath, normal priority first.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theLesserPriorityClasspathElementsComeAfterTheNormalPriorityOnes(@TempDir final Path tempDir)
            throws IOException {
        final var appJar = Files.createFile(tempDir.resolve("app.jar"));
        final var libJar = Files.createFile(tempDir.resolve("lib.jar"));
        final var classLoader = new QuarkusClassLoader().servingByPriority(
                List.of(new PathTreeClassPathElement(appJar)), List.of(new PathTreeClassPathElement(libJar)));
        assertThat(locations(classLoader)).containsExactly(location(appJar), location(libJar));
    }

    /**
     * A subclass of a Quarkus classloader is read the same way as the classloader it extends. The handler accepts a
     * subclass, and once a handler has been chosen for a classloader no other handler is offered it, so a handler
     * that recognized a classloader and then read nothing from it would leave that classloader's classpath entries
     * out of the scan entirely.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theClasspathElementsOfASubclassOfAQuarkusClassLoaderAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var jar = Files.createFile(tempDir.resolve("app.jar"));
        final var classLoader = new QuarkusClassLoader() {
            // A subclass of the Quarkus classloader, which Quarkus itself does not currently have
        }.serving(new PathTreeClassPathElement(jar));
        assertThat(locations(classLoader)).containsExactly(location(jar));
    }

    /**
     * Quarkus renames the fields that the classpath elements are held in between releases, so a classloader that
     * reports no elements at all must not fail the scan.
     */
    @Test
    public void aQuarkusClassLoaderThatReportsNoElementsDoesNotThrow() {
        assertThatCode(() -> locations(new QuarkusClassLoader())).doesNotThrowAnyException();
    }

    /**
     * The directories that an application built with Quarkus 1.2 or earlier serves its classes from are on the
     * classpath. This classloader reports its directories as {@link java.net.URI}s, since it can serve classes from
     * a filesystem other than the default one, but a directory on the default filesystem is still reported as a
     * plain path, the same as it is for every other classloader.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theClassDirectoriesOfAQuarkusRuntimeApplicationAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var classesDir = Files.createDirectory(tempDir.resolve("classes"));
        final var generatedDir = Files.createDirectory(tempDir.resolve("generated-classes"));
        assertThat(locations(new RuntimeClassLoader().serving(classesDir, generatedDir)))
                .containsExactly(location(classesDir), location(generatedDir));
    }

    /**
     * Every jar of an application packaged as a fast jar is on the classpath, and a jar that serves more than one
     * of the application's resource directories is on it only once.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void theJarsOfAFastJarApplicationAreOnTheClasspathOnce(@TempDir final Path tempDir) throws IOException {
        final var appJar = Files.createFile(tempDir.resolve("app.jar"));
        final var libJar = Files.createFile(tempDir.resolve("lib.jar"));
        final var appResource = new JarResource(appJar);
        final var classLoader = new RunnerClassLoader().serving("org/example", appResource, new JarResource(libJar))
                .serving("org/example/sub", appResource);
        assertThat(locations(classLoader)).containsExactly(location(appJar), location(libJar));
    }

    /**
     * Quarkus renames the field that a fast jar application's resources are held in between releases, so a runner
     * classloader that reports no resources at all must not fail the scan.
     */
    @Test
    public void aRunnerClassLoaderThatReportsNoResourcesDoesNotThrow() {
        assertThatCode(() -> locations(new RunnerClassLoader())).doesNotThrowAnyException();
    }
}
