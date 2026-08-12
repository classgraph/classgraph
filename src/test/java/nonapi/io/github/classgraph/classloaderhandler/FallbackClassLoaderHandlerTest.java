package nonapi.io.github.classgraph.classloaderhandler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.classpath.ClasspathFinder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder.ClasspathEntry;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Test that the classpath of a classloader that ClassGraph does not recognize is recovered by asking the classloader
 * for resources that sit in the root of a classpath element, without also picking up the resources that the
 * classloader only serves because it delegates to the bootstrap classloader.
 */
public class FallbackClassLoaderHandlerTest {
    /**
     * An unrecognized classloader that reports nothing about its classpath, and can only be asked for the resources
     * it serves.
     */
    public static class ResourceServingClassLoader extends ClassLoader {
        /** The URLs of each resource that this classloader serves. */
        private final Map<String, List<URL>> resourceURLs = new LinkedHashMap<>();

        /** Constructor. */
        public ResourceServingClassLoader() {
            super(/* parent = */ null);
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
            resourceURLs.put(resourcePath, Arrays.asList(urls));
            return this;
        }

        @Override
        public Enumeration<URL> findResources(final String resourcePath) {
            final List<URL> urls = resourceURLs.get(resourcePath);
            return Collections.enumeration(urls == null ? Collections.<URL> emptyList() : urls);
        }
    }

    /**
     * Find the classpath entries of a classloader.
     *
     * @param classLoader
     *            the classloader.
     * @return the classpath entries, as strings.
     */
    private static List<String> classpathEntries(final ClassLoader classLoader) {
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.overrideClassLoaders(classLoader);
        final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, new ReflectionUtils(),
                /* log = */ null);
        final List<String> entries = new ArrayList<>();
        for (final ClasspathEntry entry : classpathFinder.getClasspathOrder().getOrder()) {
            entries.add(entry.classpathEntryObj.toString());
        }
        return entries;
    }

    /**
     * A classloader that has no resources of its own still serves the resources of the bootstrap classloader,
     * including the {@code module-info.class} of every module of the JDK's own runtime image (on Java 9+). Those
     * modules are not classpath elements -- they are found by module scanning -- so they are not on the classpath.
     */
    @Test
    public void theModulesOfTheRuntimeImageAreNotOnTheClasspath() {
        assertThat(classpathEntries(new ResourceServingClassLoader())).isEmpty();
    }

    /**
     * A classloader that reports nothing about its classpath is asked for the manifest of every classpath element it
     * serves, and each jar those manifests are in is on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the classpath in.
     * @throws IOException
     *             if the classpath could not be created.
     */
    @Test
    public void theJarsThatAClassLoaderServesManifestsFromAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final File jar = Files.createFile(tempDir.resolve("app.jar")).toFile();
        final URL manifestURL = URI.create("jar:" + jar.toURI() + "!/META-INF/MANIFEST.MF").toURL();
        assertThat(classpathEntries(new ResourceServingClassLoader().serving("META-INF/MANIFEST.MF", manifestURL)))
                .containsExactly(jar.getPath().replace(File.separatorChar, '/'));
    }
}
