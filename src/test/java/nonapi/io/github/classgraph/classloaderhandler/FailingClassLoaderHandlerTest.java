package nonapi.io.github.classgraph.classloaderhandler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.catalina.loader.WebappClassLoaderBase;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.openejb.server.cxf.transport.util.CxfContainerClassLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.classpath.ClasspathFinder;
import nonapi.io.github.classgraph.classpath.ClasspathOrder.ClasspathEntry;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Tests that a ClassLoaderHandler that throws, for example because it meets a container release whose internals
 * have changed, is logged and skipped, and does not stop the other classloaders from being read.
 */
public class FailingClassLoaderHandlerTest {
    /**
     * Find the classpath of some classloaders.
     *
     * @param classLoaders
     *            the classloaders.
     * @return the classpath finder.
     */
    private static ClasspathFinder find(final ClassLoader... classLoaders) {
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.overrideClassLoaders(classLoaders);
        return new ClasspathFinder(scanSpec, new ReflectionUtils(), /* log = */ null);
    }

    /**
     * Find the classpath element locations of some classloaders.
     *
     * @param classpathFinder
     *            the classpath finder.
     * @return the classpath element locations.
     */
    private static List<String> locations(final ClasspathFinder classpathFinder) {
        final List<String> locations = new ArrayList<>();
        for (final ClasspathEntry entry : classpathFinder.getClasspathOrder().getOrder()) {
            locations.add(entry.classpathEntryObj.toString());
        }
        return locations;
    }

    /**
     * The location of a classpath element, as it is listed in the classpath order.
     *
     * @param path
     *            the path of the classpath element.
     * @return the location.
     */
    private static String location(final Path path) {
        return path.toFile().getPath().replace(File.separatorChar, '/');
    }

    /**
     * A handler whose {@code findClassLoaderOrder} throws is skipped: its classloader is still placed in the
     * classloader order, and every other classloader is still read.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a directory could not be created.
     */
    @Test
    public void aHandlerWhoseFindClassLoaderOrderThrowsIsSkipped(@TempDir final Path tempDir) throws IOException {
        final Path otherDir = Files.createDirectories(tempDir.resolve("other"));
        // The handler casts the result of tccl() to ClassLoader, which throws ClassCastException
        final CxfContainerClassLoader cxf = new CxfContainerClassLoader("not a classloader", /* parent = */ null);
        try (URLClassLoader other = new URLClassLoader(new URL[] { otherDir.toUri().toURL() },
                /* parent = */ null)) {
            final ClasspathFinder classpathFinder = find(cxf, other);
            assertThat(locations(classpathFinder)).containsExactly(location(otherDir));
            assertThat(classpathFinder.getClassLoaderOrderRespectingParentDelegation()).containsExactly(cxf,
                    other);
        }
    }

    /**
     * A stand-in for Catalina's {@code StandardRoot} whose {@code allResources} field holds something other than a
     * list of {@code WebResourceSet}s after the first one.
     */
    private static class BrokenStandardRoot {
        /** The {@code WebResourceSet}s, grouped as Catalina groups them, followed by an object that is not. */
        @SuppressWarnings("unused")
        final List<Object> allResources;

        /**
         * Constructor.
         *
         * @param firstGroup
         *            the first group of {@code WebResourceSet}s.
         */
        BrokenStandardRoot(final List<Object> firstGroup) {
            this.allResources = Arrays.<Object> asList(firstGroup, "not a list");
        }
    }

    /**
     * A handler whose {@code findClasspathOrder} throws keeps the classpath entries it added before it threw, and
     * every other classloader is still read.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a directory could not be created.
     */
    @Test
    public void aHandlerWhoseFindClasspathOrderThrowsIsSkipped(@TempDir final Path tempDir) throws IOException {
        final Path webappDir = tempDir.resolve("webapp");
        final Path classesDir = Files.createDirectories(webappDir.resolve("WEB-INF/classes"));
        final Path otherDir = Files.createDirectories(tempDir.resolve("other"));
        final BrokenStandardRoot root = new BrokenStandardRoot(
                Arrays.<Object> asList(new DirResourceSet("/", webappDir.toString(), "/")));
        // The handler adds the first group's classes dir, then throws ClassCastException at the second group
        final WebappClassLoaderBase webapp = new WebappClassLoaderBase(root, /* parent = */ null);
        try (URLClassLoader other = new URLClassLoader(new URL[] { otherDir.toUri().toURL() },
                /* parent = */ null)) {
            assertThat(locations(find(webapp, other))).containsExactly(location(classesDir), location(otherDir));
        }
    }
}
