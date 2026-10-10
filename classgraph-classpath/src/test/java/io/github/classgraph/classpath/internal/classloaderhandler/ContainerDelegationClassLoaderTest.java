package io.github.classgraph.classpath.internal.classloaderhandler;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.openejb.server.cxf.transport.util.CxfContainerClassLoader;
import org.apache.openejb.server.cxf.transport.util.CxfUtil;
import org.apache.tools.ant.AntClassLoader;
import org.eclipse.osgi.internal.framework.ContextFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests the classpath of classloaders that load classes through a classloader other than the one that
 * {@link ClassLoader#getParent()} returns: Ant's {@code AntClassLoader}, TomEE's {@code CxfContainerClassLoader},
 * and Equinox' {@code ContextFinder}.
 */
public class ContainerDelegationClassLoaderTest {
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
     * An {@code AntClassLoader} that searches its parent first has its parent's classpath before its own. Its
     * parent is the classloader it was given, not the system classloader that {@link ClassLoader#getParent()}
     * returns.
     *
     * @param tempDir
     *            a temporary directory to create the classpaths in.
     * @throws IOException
     *             if the classpaths could not be created.
     */
    @Test
    public void antClassLoaderThatSearchesItsParentFirst(@TempDir final Path tempDir) throws IOException {
        final var parentDir = Files.createDirectory(tempDir.resolve("parent"));
        final var dir1 = Files.createDirectory(tempDir.resolve("dir1"));
        final var dir2 = Files.createDirectory(tempDir.resolve("dir2"));
        try (var parent = new URLClassLoader(new URL[] { parentDir.toUri().toURL() }, /* parent = */ null)) {
            final var antClassLoader = new AntClassLoader(parent, /* parentFirst = */ true);
            antClassLoader.addPathElement(dir1.toString());
            antClassLoader.addPathElement(dir2.toString());
            assertThat(locations(antClassLoader)).containsExactly(location(parentDir), location(dir1),
                    location(dir2));
        }
    }

    /**
     * An {@code AntClassLoader} that searches its parent last, as Ant's {@code junit} task sets it to, has its own
     * classpath before its parent's.
     *
     * @param tempDir
     *            a temporary directory to create the classpaths in.
     * @throws IOException
     *             if the classpaths could not be created.
     */
    @Test
    public void antClassLoaderThatSearchesItsParentLast(@TempDir final Path tempDir) throws IOException {
        final var parentDir = Files.createDirectory(tempDir.resolve("parent"));
        final var dir1 = Files.createDirectory(tempDir.resolve("dir1"));
        final var dir2 = Files.createDirectory(tempDir.resolve("dir2"));
        try (var parent = new URLClassLoader(new URL[] { parentDir.toUri().toURL() }, /* parent = */ null)) {
            final var antClassLoader = new AntClassLoader(parent, /* parentFirst = */ false);
            antClassLoader.addPathElement(dir1.toString());
            antClassLoader.addPathElement(dir2.toString());
            assertThat(locations(antClassLoader)).containsExactly(location(dir1), location(dir2),
                    location(parentDir));
        }
    }

    /**
     * An isolated {@code AntClassLoader} that searches its parent last, as Ant's {@code java} task sets it to, does
     * not fall back to its parent for a class that is not on its own classpath, so its classpath is only its own.
     *
     * @param tempDir
     *            a temporary directory to create the classpaths in.
     * @throws IOException
     *             if the classpaths could not be created.
     */
    @Test
    public void isolatedAntClassLoaderThatSearchesItsParentLast(@TempDir final Path tempDir) throws IOException {
        final var parentDir = Files.createDirectory(tempDir.resolve("parent"));
        final var dir1 = Files.createDirectory(tempDir.resolve("dir1"));
        try (var parent = new URLClassLoader(new URL[] { parentDir.toUri().toURL() }, /* parent = */ null)) {
            final var antClassLoader = new AntClassLoader(parent, /* parentFirst = */ false);
            antClassLoader.setIsolated(true);
            antClassLoader.addPathElement(dir1.toString());
            assertThat(locations(antClassLoader)).containsExactly(location(dir1));
        }
    }

    /**
     * A {@code CxfContainerClassLoader} has no classpath of its own. While a webapp is calling CXF, its classpath
     * is the classpath of the container, which holds {@code CxfUtil}, followed by the classpath of the webapp. As
     * in TomEE, the container's classloader is the parent of the webapp's. {@code CxfUtil} is also on the test's
     * own classpath, so this also shows that {@code CxfUtil} is looked up through the classloader being handled:
     * looking it up through ClassGraph's own classloader would put the test's own classpath first.
     *
     * @param tempDir
     *            a temporary directory to create the container's and the webapp's classpath in.
     * @throws IOException
     *             if the classpath could not be created.
     */
    @Test
    public void cxfContainerClassLoaderPutsTheContainerBeforeTheCallingWebapp(@TempDir final Path tempDir)
            throws IOException {
        final var containerDir = Files.createDirectory(tempDir.resolve("container"));
        final var webappDir = Files.createDirectory(tempDir.resolve("webapp"));
        final var cxfUtilClassfile = containerDir.resolve(CxfUtil.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(cxfUtilClassfile.getParent());
        try (var inputStream = CxfUtil.class.getResourceAsStream(CxfUtil.class.getSimpleName() + ".class")) {
            Files.copy(inputStream, cxfUtilClassfile);
        }
        try (var containerLoader = new URLClassLoader(new URL[] { containerDir.toUri().toURL() },
                /* parent = */ null);
                var webappLoader = new URLClassLoader(new URL[] { webappDir.toUri().toURL() }, containerLoader)) {
            final var cxfContainerClassLoader = new CxfContainerClassLoader(containerLoader);
            cxfContainerClassLoader.tccl(webappLoader);
            assertThat(locations(cxfContainerClassLoader)).containsExactly(location(containerDir),
                    location(webappDir));
        }
    }

    /**
     * A {@code ContextFinder} has no classpath of its own. When Equinox made it the context classloader in place of
     * another classloader, that classloader is its parent, and its classpath is the {@code ContextFinder}'s.
     *
     * @param tempDir
     *            a temporary directory to create the classpaths in.
     * @throws IOException
     *             if the classpaths could not be created.
     */
    @Test
    public void contextFinderUsesTheContextClassLoaderItReplaced(@TempDir final Path tempDir) throws IOException {
        final var contextDir = Files.createDirectory(tempDir.resolve("context"));
        final var bootDir = Files.createDirectory(tempDir.resolve("boot"));
        try (var contextLoader = new URLClassLoader(new URL[] { contextDir.toUri().toURL() }, /* parent = */ null);
                var bootLoader = new URLClassLoader(new URL[] { bootDir.toUri().toURL() }, /* parent = */ null)) {
            assertThat(locations(new ContextFinder(contextLoader, bootLoader)))
                    .containsExactly(location(contextDir));
        }
    }

    /**
     * When there was no context classloader for a {@code ContextFinder} to replace, it has no parent, and loads
     * classes through the framework's boot classloader instead, which it holds in a field. Its classpath is then
     * the boot classloader's classpath, which can only be found by reading that field.
     *
     * @param tempDir
     *            a temporary directory to create the classpath in.
     * @throws IOException
     *             if the classpath could not be created.
     */
    @Test
    public void contextFinderWithNoContextClassLoaderToReplaceUsesTheBootClassLoader(@TempDir final Path tempDir)
            throws IOException {
        final var bootDir = Files.createDirectory(tempDir.resolve("boot"));
        try (var bootLoader = new URLClassLoader(new URL[] { bootDir.toUri().toURL() }, /* parent = */ null)) {
            assertThat(locations(new ContextFinder(/* contextClassLoader = */ null, bootLoader)))
                    .containsExactly(location(bootDir));
        }
    }
}
