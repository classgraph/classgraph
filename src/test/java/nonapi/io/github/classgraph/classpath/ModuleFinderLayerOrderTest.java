package nonapi.io.github.classgraph.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ModuleRef;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * A module layer's own modules are searched before its parent layers' modules: the
 * {@code jdk.internal.loader.Loader} that a layer creates looks a package up in the modules defined to itself
 * before consulting the parent layers' modules or its parent classloader. The layer order must mirror that, so a
 * child layer's modules are listed before its parents'. A layer must also be listed only once, however many of the
 * layers in a chain the caller names.
 *
 * <p>
 * The JPMS API is called reflectively because the tests are compiled with {@code --release 8}; the tests are
 * skipped on JDK 8, which has no modules.
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
        final File jarFile = new File(dir, moduleName + ".jar");
        try (OutputStream outputStream = Files.newOutputStream(jarFile.toPath());
                ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry("stuff/whatever.txt"));
            zipOutputStream.write(moduleName.getBytes("UTF-8"));
            zipOutputStream.closeEntry();
        }
        return jarFile;
    }

    /**
     * Define a {@code ModuleLayer} containing the automatic module in the given jar, as a child of the given parent
     * layer, using reflection so that this class still compiles with {@code --release 8}.
     *
     * @param jarFile
     *            the jar to resolve as an automatic module.
     * @param moduleName
     *            the name of the automatic module in that jar.
     * @param parentLayer
     *            the parent layer, or null for the boot layer.
     * @return the new {@code ModuleLayer}.
     * @throws Exception
     *             if the layer could not be defined.
     */
    private static Object defineModuleLayer(final File jarFile, final String moduleName, final Object parentLayer)
            throws Exception {
        final Class<?> moduleFinderClass = Class.forName("java.lang.module.ModuleFinder");
        final Class<?> moduleLayerClass = Class.forName("java.lang.ModuleLayer");
        final Class<?> configurationClass = Class.forName("java.lang.module.Configuration");

        // ModuleFinder finder = ModuleFinder.of(jarFile.toPath()), and ModuleFinder.of() for the empty finder
        final Method moduleFinderOf = moduleFinderClass.getMethod("of", Path[].class);
        final Object finder = moduleFinderOf.invoke(null, (Object) new Path[] { jarFile.toPath() });
        final Object emptyFinder = moduleFinderOf.invoke(null, (Object) new Path[0]);

        final Object parent = parentLayer != null ? parentLayer : moduleLayerClass.getMethod("boot").invoke(null);
        // Configuration config = parent.configuration().resolve(finder, ModuleFinder.of(), Set.of(moduleName))
        final Object parentConfiguration = moduleLayerClass.getMethod("configuration").invoke(parent);
        final Object configuration = configurationClass
                .getMethod("resolve", moduleFinderClass, moduleFinderClass, Collection.class)
                .invoke(parentConfiguration, finder, emptyFinder, Collections.singleton(moduleName));
        // ModuleLayer layer = parent.defineModulesWithOneLoader(config, List.of(parent), classLoader).layer()
        final Object controller = moduleLayerClass
                .getMethod("defineModulesWithOneLoader", configurationClass, List.class, ClassLoader.class)
                .invoke(parent, configuration, Collections.singletonList(parent),
                        ModuleFinderLayerOrderTest.class.getClassLoader());
        return controller.getClass().getMethod("layer").invoke(controller);
    }

    /**
     * Find the non-system modules that {@link ModuleFinder} lists, in order, given the module layers to search.
     *
     * @param moduleLayers
     *            the module layers to search.
     * @return the names of the non-system modules, in the order they are listed.
     */
    private static List<String> nonSystemModuleNames(final Object... moduleLayers) {
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.overrideModuleLayers(moduleLayers);
        final ModuleFinder moduleFinder = new ModuleFinder(/* callStack = */ null, scanSpec,
                /* scanNonSystemModules = */ true, /* scanSystemModules = */ false, new ReflectionUtils(),
                /* log = */ null);
        final List<ModuleRef> nonSystemModuleRefs = moduleFinder.getNonSystemModuleRefs();
        assertThat(nonSystemModuleRefs).isNotNull();
        final List<String> moduleNames = new ArrayList<>();
        for (final ModuleRef moduleRef : nonSystemModuleRefs) {
            final String name = moduleRef.getName();
            if (PARENT_MODULE.equals(name) || CHILD_MODULE.equals(name)) {
                moduleNames.add(name);
            }
        }
        return moduleNames;
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
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void childLayerModulesAreListedBeforeParentLayerModules(@TempDir final File tempDir) throws Exception {
        final File parentJar = buildAutomaticModuleJar(tempDir, PARENT_MODULE);
        final File childJar = buildAutomaticModuleJar(tempDir, CHILD_MODULE);
        final Object parentLayer = defineModuleLayer(parentJar, PARENT_MODULE, /* parentLayer = */ null);
        final Object childLayer = defineModuleLayer(childJar, CHILD_MODULE, parentLayer);

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
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void aNamedLayerKeepsThePositionItsOwnNameGivesIt(@TempDir final File tempDir) throws Exception {
        final File parentJar = buildAutomaticModuleJar(tempDir, PARENT_MODULE);
        final File childJar = buildAutomaticModuleJar(tempDir, CHILD_MODULE);
        final Object parentLayer = defineModuleLayer(parentJar, PARENT_MODULE, /* parentLayer = */ null);
        final Object childLayer = defineModuleLayer(childJar, CHILD_MODULE, parentLayer);

        assertThat(nonSystemModuleNames(parentLayer, childLayer)).containsExactly(PARENT_MODULE, CHILD_MODULE);
    }
}
