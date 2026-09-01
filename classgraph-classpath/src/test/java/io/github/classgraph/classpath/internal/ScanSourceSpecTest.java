package io.github.classgraph.classpath.internal;

import static io.github.classgraph.classpath.Locations.location;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.classpath.ClasspathFinder;

/**
 * Tests the classloaders and module layers that the caller can ask to be scanned, either in addition to or instead
 * of the ones found in the environment.
 */
public class ScanSourceSpecTest {
    /**
     * Build a jar containing a single resource, so that it is a valid archive that can be read both as a classpath
     * element and as an automatic module.
     *
     * @param dir
     *            the directory to create the jar in.
     * @param jarName
     *            the name of the jar, which is also the name of the automatic module it defines.
     * @return the jar.
     * @throws IOException
     *             if the jar could not be written.
     */
    private static Path buildJar(final Path dir, final String jarName) throws IOException {
        final var jar = dir.resolve(jarName + ".jar");
        try (var zipOutputStream = new ZipOutputStream(Files.newOutputStream(jar))) {
            zipOutputStream.putNextEntry(new ZipEntry("resource.txt"));
            zipOutputStream.write(jarName.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return jar;
    }

    /**
     * A classloader that serves the given jar and delegates to nothing.
     *
     * @param jar
     *            the jar.
     * @return the classloader.
     * @throws IOException
     *             if the jar could not be opened.
     */
    private static URLClassLoader classLoaderFor(final Path jar) throws IOException {
        return new URLClassLoader(new URL[] { jar.toUri().toURL() }, /* parent = */ null);
    }

    /**
     * Define a {@link ModuleLayer} containing the automatic module in the given jar.
     *
     * @param jar
     *            the jar to resolve as an automatic module.
     * @return the module layer.
     */
    private static ModuleLayer moduleLayerFor(final Path jar) {
        final var finder = java.lang.module.ModuleFinder.of(jar);
        final var moduleReferences = finder.findAll();
        assertThat(moduleReferences).hasSize(1);
        final var moduleName = moduleReferences.iterator().next().descriptor().name();
        final var bootLayer = ModuleLayer.boot();
        final var configuration = bootLayer.configuration().resolve(finder, java.lang.module.ModuleFinder.of(),
                Set.of(moduleName));
        return ModuleLayer.defineModulesWithOneLoader(configuration, List.of(bootLayer),
                ScanSourceSpecTest.class.getClassLoader()).layer();
    }

    /**
     * The names of the modules that are not part of the runtime image, which are the modules a caller names.
     *
     * @param classpathFinder
     *            the configured classpath finder.
     * @return the module names.
     */
    private static List<String> nonSystemModuleNames(final ClasspathFinder classpathFinder) {
        try (var classpath = classpathFinder.find()) {
            return classpath.getNonSystemModules().stream().map(ref -> ref.descriptor().name()).toList();
        }
    }

    /**
     * The classpath element locations found by a configured classpath finder.
     *
     * @param classpathFinder
     *            the configured classpath finder.
     * @return the locations.
     */
    private static List<String> locations(final ClasspathFinder classpathFinder) {
        try (var classpath = classpathFinder.find()) {
            return classpath.getLocations();
        }
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * A named classloader is searched as well as the classloaders found in the environment, if the classpath is
     * enabled too, so the rest of the classpath is still found.
     *
     * @param tempDir
     *            a temporary directory to create the jar in.
     * @throws IOException
     *             if the jar could not be created.
     */
    @Test
    public void aNamedClassLoaderIsSearchedAsWellAsTheOnesFoundInTheEnvironment(@TempDir final Path tempDir)
            throws IOException {
        final var addedJar = buildJar(tempDir, "added");
        try (var classLoader = classLoaderFor(addedJar)) {
            final var locations = locations(
                    new ClasspathFinder().enableClasspath().enableClassLoaders(classLoader));
            assertThat(locations).contains(location(addedJar));
            assertThat(locations).as("the rest of the classpath is still present").hasSizeGreaterThan(1);
        }
    }

    /**
     * Naming a classloader without also enabling the classpath searches only that classloader, so nothing but its
     * classpath elements is found -- in particular, {@code java.class.path} is ignored.
     *
     * @param tempDir
     *            a temporary directory to create the jar in.
     * @throws IOException
     *             if the jar could not be created.
     */
    @Test
    public void onlyTheNamedClassLoadersAreSearchedIfTheClasspathIsNotEnabled(@TempDir final Path tempDir)
            throws IOException {
        final var namedJar = buildJar(tempDir, "named");
        try (var classLoader = classLoaderFor(namedJar)) {
            assertThat(locations(new ClasspathFinder().enableClassLoaders(classLoader)))
                    .containsExactly(location(namedJar));
        }
    }

    /**
     * All the named classloaders are searched, in the order they were given.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void allTheNamedClassLoadersAreSearchedInOrder(@TempDir final Path tempDir) throws IOException {
        final var firstJar = buildJar(tempDir, "first");
        final var secondJar = buildJar(tempDir, "second");
        try (var firstClassLoader = classLoaderFor(firstJar); var secondClassLoader = classLoaderFor(secondJar)) {
            assertThat(locations(new ClasspathFinder().enableClassLoaders(firstClassLoader, secondClassLoader)))
                    .containsExactly(location(firstJar), location(secondJar));
        }
    }

    /**
     * Classloaders named by separate calls are searched in the order the calls were made in, so that the fluent
     * order decides which copy of a duplicated class would be loaded.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void classLoadersNamedBySeparateCallsAreSearchedInOrder(@TempDir final Path tempDir) throws IOException {
        final var firstJar = buildJar(tempDir, "first");
        final var secondJar = buildJar(tempDir, "second");
        try (var firstClassLoader = classLoaderFor(firstJar); var secondClassLoader = classLoaderFor(secondJar)) {
            assertThat(locations(new ClasspathFinder().enableClassLoaders(firstClassLoader)
                    .enableClassLoaders(secondClassLoader)))
                    .containsExactly(location(firstJar), location(secondJar));
        }
    }

    /**
     * A named module layer is searched as well as the module layers that are visible to the caller, if the detected
     * modules are enabled too.
     *
     * @param tempDir
     *            a temporary directory to create the jar in.
     * @throws IOException
     *             if the jar could not be created.
     */
    @Test
    public void aNamedModuleLayerIsSearchedAsWellAsTheVisibleOnes(@TempDir final Path tempDir) throws IOException {
        final var addedJar = buildJar(tempDir, "addedmodule");
        assertThat(nonSystemModuleNames(
                new ClasspathFinder().enableNonSystemModules().enableModuleLayers(moduleLayerFor(addedJar))))
                .contains("addedmodule");
    }

    /**
     * Naming a module layer without also enabling the detected modules searches only that layer, so only its
     * modules are found.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void onlyTheNamedModuleLayersAreSearchedIfTheDetectedOnesAreNotEnabled(@TempDir final Path tempDir)
            throws IOException {
        final var namedJar = buildJar(tempDir, "namedmodule");
        assertThat(nonSystemModuleNames(
                new ClasspathFinder().enableNonSystemModules().enableModuleLayers(moduleLayerFor(namedJar))))
                .containsExactly("namedmodule");
    }

    /**
     * Module layers named by separate calls are all searched.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void moduleLayersNamedBySeparateCallsAreAllSearched(@TempDir final Path tempDir) throws IOException {
        final var firstJar = buildJar(tempDir, "firstmodule");
        final var secondJar = buildJar(tempDir, "secondmodule");
        assertThat(nonSystemModuleNames(new ClasspathFinder().enableNonSystemModules()
                .enableModuleLayers(moduleLayerFor(firstJar)).enableModuleLayers(moduleLayerFor(secondJar))))
                .containsExactlyInAnyOrder("firstmodule", "secondmodule");
    }

    /** A null classloader or module layer is rejected, rather than silently ignored or scanned as null. */
    @Test
    public void aNullClassLoaderOrModuleLayerIsRejected() {
        final var classpathFinder = new ClasspathFinder();
        assertThatThrownBy(() -> classpathFinder.enableClassLoaders((ClassLoader[]) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> classpathFinder.enableClassLoaders(new ClassLoader[] { null }))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> classpathFinder.enableModuleLayers((ModuleLayer[]) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> classpathFinder.enableModuleLayers(new ModuleLayer[] { null }))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Naming nothing at all is rejected, since it would otherwise silently mean "scan nothing", which is never what
     * the caller meant.
     */
    @Test
    public void namingNothingAtAllIsRejected() {
        final var classpathFinder = new ClasspathFinder();
        assertThatThrownBy(classpathFinder::enableClassLoaders).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(classpathFinder::enableModuleLayers).isInstanceOf(IllegalArgumentException.class);
    }
}
