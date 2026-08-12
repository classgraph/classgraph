package io.github.classgraph.classpath.internal.classloaderhandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jboss.modules.JarFileResourceLoader;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.modules.ModuleLoader;
import org.jboss.modules.PathResourceLoader;
import org.jboss.vfs.VirtualFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests that the classpath of a JBoss/WildFly module is read from the resource loaders of every module the module
 * system knows about, including the ones whose files live in the JBoss virtual filesystem rather than directly on
 * disk.
 */
public class JBossModuleClassLoaderTest {
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
     * The location that a file or directory is reported as.
     *
     * @param path
     *            the file or directory.
     * @return the location.
     */
    private static String location(final Path path) {
        return path.toFile().getPath().replace(File.separatorChar, '/');
    }

    /**
     * Build a module, loaded by its own module loader, whose classes are read from the given resource loaders.
     *
     * @param resourceLoaders
     *            the resource loaders.
     * @return the module's classloader.
     */
    private static ModuleClassLoader moduleWithResourceLoaders(final Object... resourceLoaders) {
        final var module = new Module();
        final var classLoader = new ModuleClassLoader(module, resourceLoaders);
        new ModuleLoader().loaded("test.module", module);
        return classLoader;
    }

    /**
     * A classloader that has no module at all does not fail the scan -- none of the methods and fields the handler
     * reads by reflection are present on it.
     */
    @Test
    public void aModuleClassLoaderWithNoModuleDoesNotThrow() {
        assertThatCode(() -> locations(new ModuleClassLoader())).doesNotThrowAnyException();
    }

    /**
     * A JBoss module can load classes from any other module deployed alongside it, so the jars of every module the
     * module loader knows about are on the classpath, not just the scanned module's own.
     *
     * @param tempDir
     *            a temporary directory to create the modules in.
     * @throws IOException
     *             if the modules could not be created.
     */
    @Test
    public void theJarsOfEveryModuleTheModuleLoaderKnowsAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var ownJar = Files.createFile(tempDir.resolve("own.jar"));
        final var otherJar = Files.createFile(tempDir.resolve("other.jar"));
        final var ownModule = new Module();
        final var ownClassLoader = new ModuleClassLoader(ownModule, new JarFileResourceLoader(ownJar.toFile()));
        final var otherModule = new Module();
        new ModuleClassLoader(otherModule, new JarFileResourceLoader(otherJar.toFile()));
        new ModuleLoader().loaded("own.module", ownModule).loaded("other.module", otherModule);
        assertThat(locations(ownClassLoader)).containsExactly(location(ownJar), location(otherJar));
    }

    /**
     * A module whose resources are read from a directory on disk has that directory on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the module in.
     * @throws IOException
     *             if the module could not be created.
     */
    @Test
    public void aModulesResourceDirectoryIsOnTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var resourcesDir = Files.createDirectory(tempDir.resolve("resources"));
        assertThat(locations(moduleWithResourceLoaders(new PathResourceLoader(resourcesDir))))
                .containsExactly(location(resourcesDir));
    }

    /**
     * A module records which other module each of its packages is loaded from. Those modules are on the classpath
     * too, which is how the modules a module depends on are found when the module loader itself cannot be reached.
     *
     * @param tempDir
     *            a temporary directory to create the modules in.
     * @throws IOException
     *             if the modules could not be created.
     */
    @Test
    public void theModulesThatAModulesPackagesAreLoadedFromAreOnTheClasspath(@TempDir final Path tempDir)
            throws IOException {
        final var ownJar = Files.createFile(tempDir.resolve("own.jar"));
        final var otherJar = Files.createFile(tempDir.resolve("other.jar"));
        final var otherModule = new Module();
        final var otherClassLoader = new ModuleClassLoader(otherModule,
                new JarFileResourceLoader(otherJar.toFile()));
        final var ownModule = new Module();
        final var ownClassLoader = new ModuleClassLoader(ownModule, new JarFileResourceLoader(ownJar.toFile()));
        ownModule.loadingPackageFrom("org/example/own", ownClassLoader.localLoader())
                .loadingPackageFrom("org/example/other", otherClassLoader.localLoader());
        assertThat(locations(ownClassLoader)).containsExactly(location(ownJar), location(otherJar));
    }

    /**
     * A jar deployed into the JBoss virtual filesystem is served from an extracted contents directory, but it is
     * the jar next to that directory that goes on the classpath, so that the jar is scanned rather than the
     * extracted copy.
     *
     * @param tempDir
     *            a temporary directory to create the deployment in.
     * @throws IOException
     *             if the deployment could not be created.
     */
    @Test
    public void aJarInTheVirtualFileSystemIsOnTheClasspathAsTheJarItself(@TempDir final Path tempDir)
            throws IOException {
        final var deployment = Files.createDirectory(tempDir.resolve("deployment"));
        final var jar = Files.createFile(deployment.resolve("foo.jar"));
        final var root = new VirtualFile(deployment.resolve("contents").toFile(), "foo.jar");
        assertThat(locations(moduleWithResourceLoaders(new PathResourceLoader(root))))
                .containsExactly(location(jar));
    }

    /**
     * An archive that was deployed exploded has no jar next to its contents directory, so the contents directory
     * itself goes on the classpath.
     *
     * @param tempDir
     *            a temporary directory to create the deployment in.
     * @throws IOException
     *             if the deployment could not be created.
     */
    @Test
    public void anExplodedArchiveIsOnTheClasspathAsItsContentsDirectory(@TempDir final Path tempDir)
            throws IOException {
        final var contents = Files.createDirectories(tempDir.resolve("deployment/contents"));
        final var root = new VirtualFile(contents.toFile(), "app.war");
        assertThat(locations(moduleWithResourceLoaders(new PathResourceLoader(root))))
                .containsExactly(location(contents));
    }

    /**
     * Newer JBoss and WildFly versions report the archive a virtual file was mounted from through the mount rather
     * than next to the contents directory, and that archive is on the classpath too.
     *
     * @param tempDir
     *            a temporary directory to create the deployment in.
     * @throws IOException
     *             if the deployment could not be created.
     */
    @Test
    public void aJarMountedIntoTheVirtualFileSystemIsFoundThroughItsMount(@TempDir final Path tempDir)
            throws IOException {
        final var contents = Files.createDirectories(tempDir.resolve("deployment/contents"));
        final var jar = Files.createFile(tempDir.resolve("foo.jar"));
        final var root = new VirtualFile(contents.toFile(), /* name = */ null).mountedFrom(jar.toFile());
        assertThat(locations(moduleWithResourceLoaders(new PathResourceLoader(root))))
                .containsExactly(location(contents), location(jar));
    }
}
