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

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that the classpath of a WebLogic classloader is read from the two path strings it reports: the classpath
 * its class finder reads from, and the classpath it was configured with.
 */
public class WeblogicClassLoaderTest {
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
     * Both of the path strings a WebLogic classloader reports are on the classpath, with the class finder's
     * classpath first, and each path string may name more than one classpath element.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void bothOfThePathStringsAWeblogicClassLoaderReportsAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var finderJar = Files.createFile(tempDir.resolve("finder.jar"));
        final var finderClasses = Files.createDirectory(tempDir.resolve("finder-classes"));
        final var appJar = Files.createFile(tempDir.resolve("app.jar"));
        final var classLoader = new weblogic.utils.classloaders.ChangeAwareClassLoader(
                finderJar + File.pathSeparator + finderClasses, appJar.toString());
        assertThat(locations(classLoader)).containsExactly(location(finderJar), location(finderClasses),
                location(appJar));
    }

    /**
     * A WebLogic classloader that reports only one of the two path strings contributes just that one, rather than
     * failing or contributing an empty entry.
     *
     * @param tempDir
     *            a temporary directory to create the application in.
     * @throws IOException
     *             if the application could not be created.
     */
    @Test
    public void aClassLoaderThatReportsOnlyOneOfTheTwoPathStringsContributesJustThatOne(@TempDir final Path tempDir)
            throws IOException {
        final var appJar = Files.createFile(tempDir.resolve("app.jar"));
        assertThat(locations(new weblogic.utils.classloaders.ChangeAwareClassLoader(/* finderClassPath = */ null,
                appJar.toString()))).containsExactly(location(appJar));
        assertThat(locations(
                new weblogic.utils.classloaders.ChangeAwareClassLoader(appJar.toString(), /* classPath = */ "")))
                .containsExactly(location(appJar));
    }

    /**
     * A WebLogic classloader that reports no classpath at all has nothing on its classpath, and does not fail the
     * scan.
     */
    @Test
    public void aClassLoaderThatReportsNoClasspathHasNothingOnItsClasspath() {
        assertThat(locations(new weblogic.utils.classloaders.ChangeAwareClassLoader(/* finderClassPath = */ null,
                /* classPath = */ null))).isEmpty();
    }

    /**
     * A WebLogic JSP classloader, which is not known to report its classpath the same way the other WebLogic
     * classloaders do, is still recognized, and not reporting a classpath does not fail the scan.
     */
    @Test
    public void aJspClassLoaderThatReportsNoClasspathDoesNotFailTheScan() {
        assertThatCode(() -> locations(new weblogic.servlet.jsp.JspClassLoader())).doesNotThrowAnyException();
    }
}
