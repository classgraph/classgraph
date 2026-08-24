package io.github.classgraph.classpath.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.LogNode;

/**
 * A module layer's own modules are searched before its parent layers' modules: the
 * {@code jdk.internal.loader.Loader} that a layer creates looks a package up in the modules defined to itself
 * before consulting the parent layers' modules or its parent classloader. The layer order must mirror that, so a
 * child layer's modules are listed before its parents'. A layer must also be listed only once, however many of the
 * layers in a chain the caller names.
 */
public class ModuleFinderLayerOrderTest {

    /** The name of the automatic module in the parent layer. Sorts <i>before</i> the child module's name. */
    private static final String PARENT_MODULE = "cgaparentmod";

    /** The name of the automatic module in the child layer. Sorts <i>after</i> the parent module's name. */
    private static final String CHILD_MODULE = "cgzchildmod";

    /**
     * Build a jar that resolves as an automatic module of the given name. The jar contains a single resource, since
     * an automatic module needs no classes.
     *
     * @param dir
     *            the directory to create the jar in.
     * @param moduleName
     *            the automatic module name, which is also the jar's basename.
     * @return the jar file.
     * @throws IOException
     *             if the jar could not be written.
     */
    private static File buildAutomaticModuleJar(final File dir, final String moduleName) throws IOException {
        final var jarFile = new File(dir, moduleName + ".jar");
        try (var outputStream = Files.newOutputStream(jarFile.toPath());
                var zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry("stuff/whatever.txt"));
            zipOutputStream.write(moduleName.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return jarFile;
    }

    /**
     * Define a {@link ModuleLayer} containing the automatic module in the given jar, as a child of the given parent
     * layer.
     *
     * @param jarFile
     *            the jar to resolve as an automatic module.
     * @param moduleName
     *            the name of the automatic module in that jar.
     * @param parentLayer
     *            the parent layer.
     * @return the new {@link ModuleLayer}.
     */
    private static ModuleLayer defineModuleLayer(final File jarFile, final String moduleName,
            final ModuleLayer parentLayer) {
        final var finder = ModuleFinder.of(jarFile.toPath());
        assertThat(finder.find(moduleName)).isPresent();
        final var configuration = parentLayer.configuration().resolve(finder, ModuleFinder.of(),
                Set.of(moduleName));
        return ModuleLayer.defineModulesWithOneLoader(configuration, List.of(parentLayer),
                ModuleFinderLayerOrderTest.class.getClassLoader()).layer();
    }

    /**
     * Find the non-system modules that the probe lists, in order, given the module layers to search.
     *
     * @param moduleLayers
     *            the module layers to search.
     * @return the names of the non-system modules, in the order the probe lists them.
     */
    private static List<String> nonSystemModuleNames(final ModuleLayer... moduleLayers) {
        final var classpathSpec = new ClasspathSpec();
        final var classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();
        classLoaderAndModuleLayerSpec.overrideModuleLayers(moduleLayers);
        final var moduleFinder = new ClassLoaderProbe(classpathSpec, classLoaderAndModuleLayerSpec, new LogNode())
                .getModuleFinder();
        assertThat(moduleFinder).isNotNull();
        final var nonSystemModuleReferences = moduleFinder.getNonSystemModuleReferences();
        assertThat(nonSystemModuleReferences).isNotNull();
        return nonSystemModuleReferences.stream().map(moduleReference -> moduleReference.descriptor().name())
                .filter(name -> name.equals(PARENT_MODULE) || name.equals(CHILD_MODULE)).toList();
    }

    /**
     * A child layer's modules must be listed before its parent layer's, since that is the order in which the child
     * layer's loader searches them. Naming the parent layer redundantly alongside the child must not list it twice.
     *
     * @param tempDir
     *            the temp dir.
     * @throws Exception
     *             if the test jars or module layers could not be created.
     */
    @Test
    public void childLayerModulesAreListedBeforeParentLayerModules(@TempDir final File tempDir) throws Exception {
        final var parentJar = buildAutomaticModuleJar(tempDir, PARENT_MODULE);
        final var childJar = buildAutomaticModuleJar(tempDir, CHILD_MODULE);
        final var parentLayer = defineModuleLayer(parentJar, PARENT_MODULE, ModuleLayer.boot());
        final var childLayer = defineModuleLayer(childJar, CHILD_MODULE, parentLayer);

        // The parent layer is reached through the child layer's parents, so naming the child alone is enough
        assertThat(nonSystemModuleNames(childLayer)).containsExactly(CHILD_MODULE, PARENT_MODULE);
        // Naming the parent as well must not list it twice, nor move the child
        assertThat(nonSystemModuleNames(childLayer, parentLayer)).containsExactly(CHILD_MODULE, PARENT_MODULE);
    }

    /**
     * A layer that the caller names takes the position its own name gives it: naming the parent layer first asks
     * for its modules to be searched first, and that is not overridden by the child layer's delegation order.
     *
     * @param tempDir
     *            the temp dir.
     * @throws Exception
     *             if the test jars or module layers could not be created.
     */
    @Test
    public void aNamedLayerKeepsThePositionItsOwnNameGivesIt(@TempDir final File tempDir) throws Exception {
        final var parentJar = buildAutomaticModuleJar(tempDir, PARENT_MODULE);
        final var childJar = buildAutomaticModuleJar(tempDir, CHILD_MODULE);
        final var parentLayer = defineModuleLayer(parentJar, PARENT_MODULE, ModuleLayer.boot());
        final var childLayer = defineModuleLayer(childJar, CHILD_MODULE, parentLayer);

        assertThat(nonSystemModuleNames(parentLayer, childLayer)).containsExactly(PARENT_MODULE, CHILD_MODULE);
    }
}
