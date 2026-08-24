package io.github.classgraph.classpath.internal.classloaderhandler;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that the classpath of a classloader that ClassGraph does not recognize is recovered anyway: first by
 * looking for the method and field names that classloaders commonly hold their classpath in, and failing that by
 * asking the classloader for resources that sit in the root of a classpath element, and stripping the resource path
 * from the URLs it returns.
 */
public class UnknownClassLoaderTest {
    /** An unrecognized classloader that reports its classpath as a path string. */
    public static class ClassPathStringClassLoader extends ClassLoader {
        // (this field is deliberately not named after anything the handler probes for, so that the test
        // exercises the method it is read through)
        /** The classpath, as a path string. */
        private final String searchPathString;

        /**
         * Constructor.
         *
         * @param classPath
         *            the classpath, as a path string.
         */
        public ClassPathStringClassLoader(final String classPath) {
            super(/* parent = */ null);
            this.searchPathString = classPath;
        }

        /**
         * The classpath.
         *
         * @return the classpath, as a path string.
         */
        public String getClassPath() {
            return searchPathString;
        }
    }

    /** An unrecognized classloader that reports its classpath both as a path string and as URLs. */
    public static class ClassPathAndUrlsClassLoader extends ClassPathStringClassLoader {
        /** The classpath, as URLs. */
        private final URL[] urls;

        /**
         * Constructor.
         *
         * @param classPath
         *            the classpath, as a path string.
         * @param urls
         *            the classpath, as URLs.
         */
        public ClassPathAndUrlsClassLoader(final String classPath, final URL... urls) {
            super(classPath);
            this.urls = urls;
        }

        /**
         * The classpath.
         *
         * @return the classpath, as URLs.
         */
        public URL[] getURLs() {
            return urls;
        }
    }

    /** An unrecognized classloader that holds its classpath in a field of jarfiles. */
    public static class JarsFieldClassLoader extends ClassLoader {
        /** The jars on the classpath. */
        public final List<File> jars;

        /**
         * Constructor.
         *
         * @param jars
         *            the jars on the classpath.
         */
        public JarsFieldClassLoader(final File... jars) {
            super(/* parent = */ null);
            this.jars = List.of(jars);
        }
    }

    /**
     * An unrecognized classloader that reports nothing about its classpath, and can only be asked for the resources
     * it serves.
     */
    public static class ResourceServingClassLoader extends ClassLoader {
        /** The URLs of each resource that this classloader serves. */
        private final Map<String, List<URL>> resourceURLs = new LinkedHashMap<>();

        /**
         * Constructor.
         *
         * @param parent
         *            the parent classloader, or null if there is none.
         */
        public ResourceServingClassLoader(final ClassLoader parent) {
            super(parent);
        }

        /**
         * Serve a resource from the given URLs.
         *
         * @param resourcePath
         *            the path of the resource.
         * @param urls
         *            the URLs of the resource.
         * @return this, for chaining.
         */
        public ResourceServingClassLoader serving(final String resourcePath, final URL... urls) {
            resourceURLs.put(resourcePath, List.of(urls));
            return this;
        }

        @Override
        public Enumeration<URL> findResources(final String resourcePath) {
            return Collections.enumeration(resourceURLs.getOrDefault(resourcePath, List.of()));
        }
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
     * The URL of a resource within a jar.
     *
     * @param jar
     *            the jar.
     * @param resourcePath
     *            the path of the resource within the jar.
     * @return the URL.
     * @throws MalformedURLException
     *             if the URL could not be built.
     */
    private static URL urlWithinJar(final Path jar, final String resourcePath) throws MalformedURLException {
        return URI.create("jar:" + jar.toUri() + "!/" + resourcePath).toURL();
    }

    /**
     * A classloader that holds its classpath in a path string has each element of that path on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the classpath in.
     * @throws IOException
     *             if the classpath could not be created.
     */
    @Test
    public void theElementsOfAClasspathStringAreOnTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var classesDir = Files.createDirectory(tempDir.resolve("classes"));
        final var jar = Files.createFile(tempDir.resolve("lib.jar"));
        final var classPath = classesDir + File.pathSeparator + jar;
        assertThat(locations(new ClassPathStringClassLoader(classPath))).containsExactly(location(classesDir),
                location(jar));
    }

    /**
     * A classloader that holds its classpath in a field of jarfiles has those jars on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the classpath in.
     * @throws IOException
     *             if the classpath could not be created.
     */
    @Test
    public void theJarsInAClassLoadersFieldAreOnTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var appJar = Files.createFile(tempDir.resolve("app.jar"));
        final var libJar = Files.createFile(tempDir.resolve("lib.jar"));
        assertThat(locations(new JarsFieldClassLoader(appJar.toFile(), libJar.toFile())))
                .containsExactly(location(appJar), location(libJar));
    }

    /**
     * A classloader may report its classpath in more than one way. Everything it reports is on the classpath, and
     * whichever way names the classpath most directly is read first.
     *
     * @param tempDir
     *            a temporary directory to create the classpath in.
     * @throws IOException
     *             if the classpath could not be created.
     */
    @Test
    public void everythingAClassLoaderReportsIsOnTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var appJar = Files.createFile(tempDir.resolve("app.jar"));
        final var libJar = Files.createFile(tempDir.resolve("lib.jar"));
        final var classLoader = new ClassPathAndUrlsClassLoader(appJar.toString(), libJar.toUri().toURL());
        assertThat(locations(classLoader)).containsExactly(location(appJar), location(libJar));
    }

    /**
     * A classloader that reports nothing about its classpath is asked for the manifest of every classpath element
     * it serves, and each jar those manifests are in is on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the classpath in.
     * @throws IOException
     *             if the classpath could not be created.
     */
    @Test
    public void theJarsThatAClassLoaderServesManifestsFromAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var appJar = Files.createFile(tempDir.resolve("app.jar"));
        final var libJar = Files.createFile(tempDir.resolve("lib.jar"));
        final var classLoader = new ResourceServingClassLoader(/* parent = */ null).serving("META-INF/MANIFEST.MF",
                urlWithinJar(appJar, "META-INF/MANIFEST.MF"), urlWithinJar(libJar, "META-INF/MANIFEST.MF"));
        assertThat(locations(classLoader)).containsExactly(location(appJar), location(libJar));
    }

    /**
     * A classpath element that is a directory has no manifest, so the directory is recovered from the package root
     * itself.
     *
     * @param tempDir
     *            a temporary directory to create the classpath in.
     * @throws IOException
     *             if the classpath could not be created.
     */
    @Test
    public void theDirectoriesThatAClassLoaderServesItsPackageRootFromAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var classesDir = Files.createDirectory(tempDir.resolve("classes"));
        final var classLoader = new ResourceServingClassLoader(/* parent = */ null).serving("",
                classesDir.toUri().toURL());
        assertThat(locations(classLoader)).containsExactly(location(classesDir));
    }

    /**
     * Resources that a classloader serves only because its parent serves them are not classpath elements of the
     * classloader itself, so they are not on the classpath when the parent classloaders are ignored.
     *
     * @param tempDir
     *            a temporary directory to create the classpath in.
     * @throws IOException
     *             if the classpath could not be created.
     */
    @Test
    public void theResourcesThatOnlyTheParentServesAreNotOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var appJar = Files.createFile(tempDir.resolve("app.jar"));
        final var parentJar = Files.createFile(tempDir.resolve("parent.jar"));
        final var parentURL = urlWithinJar(parentJar, "META-INF/MANIFEST.MF");
        final var parent = new ResourceServingClassLoader(/* parent = */ null).serving("META-INF/MANIFEST.MF",
                parentURL);
        final var classLoader = new ResourceServingClassLoader(parent).serving("META-INF/MANIFEST.MF",
                urlWithinJar(appJar, "META-INF/MANIFEST.MF"), parentURL);
        try (var classpath = new ClasspathFinder().enableClassLoaders(classLoader).ignoreParentClassLoaders()
                .find()) {
            assertThat(classpath.getLocations()).containsExactly(location(appJar));
        }
    }

    /**
     * The modules of the JDK's own runtime image are not classpath elements -- they are found by module scanning --
     * so they are not on the classpath, even though every classloader serves their resources.
     *
     * @throws IOException
     *             if the URL could not be built.
     */
    @Test
    public void theModulesOfTheRuntimeImageAreNotOnTheClasspath() throws IOException {
        final var classLoader = new ResourceServingClassLoader(/* parent = */ null).serving("module-info.class",
                URI.create("jrt:/java.base/module-info.class").toURL());
        assertThat(locations(classLoader)).isEmpty();
    }

    /**
     * A classloader that reports nothing about its classpath and serves no resources does not fail the scan.
     */
    @Test
    public void aClassLoaderThatReportsNothingDoesNotThrow() {
        assertThatCode(() -> locations(new ResourceServingClassLoader(/* parent = */ null)))
                .doesNotThrowAnyException();
    }
}
