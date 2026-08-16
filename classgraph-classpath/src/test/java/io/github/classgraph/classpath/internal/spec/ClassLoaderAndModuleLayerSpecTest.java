package io.github.classgraph.classpath.internal.spec;

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
public class ClassLoaderAndModuleLayerSpecTest {
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
                ClassLoaderAndModuleLayerSpecTest.class.getClassLoader()).layer();
    }

    /**
     * The names of the modules that are not part of the runtime image, which are the modules a caller adds or
     * overrides.
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
     * An added classloader is searched as well as the classloaders found in the environment, so the rest of the
     * classpath is still found.
     *
     * @param tempDir
     *            a temporary directory to create the jar in.
     * @throws IOException
     *             if the jar could not be created.
     */
    @Test
    public void anAddedClassLoaderIsSearchedAsWellAsTheOnesFoundInTheEnvironment(@TempDir final Path tempDir)
            throws IOException {
        final var addedJar = buildJar(tempDir, "added");
        try (var classLoader = classLoaderFor(addedJar)) {
            final var locations = locations(new ClasspathFinder().addClassLoader(classLoader));
            assertThat(locations).contains(location(addedJar));
            assertThat(locations).as("the rest of the classpath is still present").hasSizeGreaterThan(1);
        }
    }

    /**
     * Overriding the classloaders replaces the ones found in the environment, so nothing but the overriding
     * classloaders' classpath elements is found -- in particular, {@code java.class.path} is ignored.
     *
     * @param tempDir
     *            a temporary directory to create the jar in.
     * @throws IOException
     *             if the jar could not be created.
     */
    @Test
    public void overridingTheClassLoadersReplacesTheOnesFoundInTheEnvironment(@TempDir final Path tempDir)
            throws IOException {
        final var overrideJar = buildJar(tempDir, "override");
        try (var classLoader = classLoaderFor(overrideJar)) {
            assertThat(locations(new ClasspathFinder().overrideClassLoaders(classLoader)))
                    .containsExactly(location(overrideJar));
        }
    }

    /**
     * A classloader added before the classloaders are overridden is discarded, because overriding replaces
     * everything that was requested before it.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void aClassLoaderAddedBeforeTheClassLoadersAreOverriddenIsDiscarded(@TempDir final Path tempDir)
            throws IOException {
        final var addedJar = buildJar(tempDir, "added");
        final var overrideJar = buildJar(tempDir, "override");
        try (var addedClassLoader = classLoaderFor(addedJar);
                var overrideClassLoader = classLoaderFor(overrideJar)) {
            assertThat(locations(new ClasspathFinder().addClassLoader(addedClassLoader)
                    .overrideClassLoaders(overrideClassLoader))).containsExactly(location(overrideJar));
        }
    }

    /**
     * All the overriding classloaders are searched, in the order they were given.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void allTheOverridingClassLoadersAreSearchedInOrder(@TempDir final Path tempDir) throws IOException {
        final var firstJar = buildJar(tempDir, "first");
        final var secondJar = buildJar(tempDir, "second");
        try (var firstClassLoader = classLoaderFor(firstJar); var secondClassLoader = classLoaderFor(secondJar)) {
            assertThat(locations(new ClasspathFinder().overrideClassLoaders(firstClassLoader, secondClassLoader)))
                    .containsExactly(location(firstJar), location(secondJar));
        }
    }

    /**
     * An added module layer is searched as well as the module layers that are visible to the caller.
     *
     * @param tempDir
     *            a temporary directory to create the jar in.
     * @throws IOException
     *             if the jar could not be created.
     */
    @Test
    public void anAddedModuleLayerIsSearchedAsWellAsTheVisibleOnes(@TempDir final Path tempDir) throws IOException {
        final var addedJar = buildJar(tempDir, "addedmodule");
        assertThat(nonSystemModuleNames(new ClasspathFinder().addModuleLayer(moduleLayerFor(addedJar))))
                .contains("addedmodule");
    }

    /**
     * Overriding the module layers replaces the ones that are visible to the caller, so only the modules of the
     * overriding layers are found.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void overridingTheModuleLayersReplacesTheVisibleOnes(@TempDir final Path tempDir) throws IOException {
        final var overrideJar = buildJar(tempDir, "overridemodule");
        assertThat(nonSystemModuleNames(new ClasspathFinder().overrideModuleLayers(moduleLayerFor(overrideJar))))
                .containsExactly("overridemodule");
    }

    /**
     * A module layer added before the module layers are overridden is discarded, because overriding replaces
     * everything that was requested before it.
     *
     * @param tempDir
     *            a temporary directory to create the jars in.
     * @throws IOException
     *             if the jars could not be created.
     */
    @Test
    public void aModuleLayerAddedBeforeTheModuleLayersAreOverriddenIsDiscarded(@TempDir final Path tempDir)
            throws IOException {
        final var addedJar = buildJar(tempDir, "addedmodule");
        final var overrideJar = buildJar(tempDir, "overridemodule");
        assertThat(nonSystemModuleNames(new ClasspathFinder().addModuleLayer(moduleLayerFor(addedJar))
                .overrideModuleLayers(moduleLayerFor(overrideJar)))).containsExactly("overridemodule");
    }

    /** A null classloader or module layer is rejected, rather than silently ignored or scanned as null. */
    @Test
    public void aNullClassLoaderOrModuleLayerIsRejected() {
        final var classpathFinder = new ClasspathFinder();
        assertThatThrownBy(() -> classpathFinder.addClassLoader(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> classpathFinder.overrideClassLoaders(new ClassLoader[] { null }))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> classpathFinder.addModuleLayer(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> classpathFinder.overrideModuleLayers(new ModuleLayer[] { null }))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Overriding with nothing at all is rejected, since it would otherwise silently mean "scan nothing", which is
     * never what the caller meant.
     */
    @Test
    public void overridingWithNothingAtAllIsRejected() {
        final var classpathFinder = new ClasspathFinder();
        assertThatThrownBy(classpathFinder::overrideClassLoaders).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(classpathFinder::overrideModuleLayers).isInstanceOf(IllegalArgumentException.class);
    }
}
