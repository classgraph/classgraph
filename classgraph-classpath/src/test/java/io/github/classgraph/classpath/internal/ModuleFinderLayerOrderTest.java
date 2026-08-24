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
 * A module layer's classloaders delegate to the classloaders of its parent layers, so a parent layer's modules are
 * searched before a child layer's. The layer order must reflect that, and must not depend on which of the layers
 * the caller happened to name -- naming a child layer reaches its parent through {@link ModuleLayer#parents()}, and
 * the parent must land in the same position either way.
 */
public class ModuleFinderLayerOrderTest {

    /** The name of the automatic module in the parent layer. Sorts <i>after</i> the child module's name. */
    private static final String PARENT_MODULE = "cgzparentmod";

    /** The name of the automatic module in the child layer. Sorts <i>before</i> the parent module's name. */
    private static final String CHILD_MODULE = "cgachildmod";

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
     * The parent layer's module must be listed before the child layer's, whichever of the two layers is named, and
     * whether or not the redundant parent layer is named alongside the child.
     *
     * @param tempDir
     *            the temp dir.
     * @throws Exception
     *             if the test jars or module layers could not be created.
     */
    @Test
    public void parentLayerModulesAreListedBeforeChildLayerModules(@TempDir final File tempDir) throws Exception {
        final var parentJar = buildAutomaticModuleJar(tempDir, PARENT_MODULE);
        final var childJar = buildAutomaticModuleJar(tempDir, CHILD_MODULE);
        final var parentLayer = defineModuleLayer(parentJar, PARENT_MODULE, ModuleLayer.boot());
        final var childLayer = defineModuleLayer(childJar, CHILD_MODULE, parentLayer);

        // The parent layer is reached through the child layer's parents, so naming the child alone is enough
        assertThat(nonSystemModuleNames(childLayer)).containsExactly(PARENT_MODULE, CHILD_MODULE);
        // Naming the parent as well must not move it -- it is already in the order, at the same position
        assertThat(nonSystemModuleNames(childLayer, parentLayer)).containsExactly(PARENT_MODULE, CHILD_MODULE);
        assertThat(nonSystemModuleNames(parentLayer, childLayer)).containsExactly(PARENT_MODULE, CHILD_MODULE);
    }
}
