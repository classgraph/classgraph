package io.github.classgraph.classpath.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.LogNode;

public class ClassLoaderProbeTest {

    /**
     * A {@link ClasspathSpec} that scans the system modules and the system jars.
     *
     * @return the spec.
     */
    private static ClasspathSpec systemModuleSpec() {
        final var classpathSpec = new ClasspathSpec();
        classpathSpec.scanSystemModules = true;
        classpathSpec.enableSystemJars = true;
        classpathSpec.ignoreParentClassLoaders = true;
        return classpathSpec;
    }

    /**
     * Test that {@link ClasspathSpec#scanSystemModules}, {@link ClasspathSpec#ignoreParentClassLoaders} and
     * {@link ScanSourceSpec#enableClasspathEntries} work in combination:
     * <p>
     * Only the system modules and the given classpath entries should be found.
     */
    @Test
    public void testNamedClasspathEntriesAndEnableSystemModules(@TempDir final Path tmpDir) throws Exception {
        // Arrange
        final var classesDir = tmpDir.toAbsolutePath().normalize().toRealPath();
        final var classpathSpec = systemModuleSpec();
        final var scanSourceSpec = new ScanSourceSpec();
        scanSourceSpec.enableDetectedModuleLayers();
        scanSourceSpec.enableClasspathEntries(List.of(classesDir));

        // Act
        final var classLoaderProbe = new ClassLoaderProbe(classpathSpec, scanSourceSpec, new LogNode());
        final var moduleFinder = classLoaderProbe.getModuleFinder();

        // Assert
        assertNotNull(moduleFinder, "ModuleFinder should be non-null");
        assertFalse(moduleFinder.getSystemModuleReferences().isEmpty(),
                "ModuleFinder should have found system modules");

        final var paths = resolvedPaths(classLoaderProbe);
        assertTrue(paths.remove(classesDir), "Classpath should have contained " + classesDir + ": " + paths);
        assertEquals(0, paths.size(), "Classpath should have no other entries: " + paths);
    }

    /**
     * Test that {@link ClasspathSpec#scanSystemModules}, {@link ClasspathSpec#ignoreParentClassLoaders} and
     * {@link ScanSourceSpec#enableClassLoaders} work in combination:
     * <p>
     * Only the system modules and the given classloaders should be found.
     */
    @Test
    public void testNamedClassLoadersAndEnableSystemModules(@TempDir final Path tmpDir) throws Exception {
        // Arrange
        final var classesDir = tmpDir.toAbsolutePath().normalize().toRealPath();
        final var classpathSpec = systemModuleSpec();
        final var scanSourceSpec = new ScanSourceSpec();
        scanSourceSpec.enableDetectedModuleLayers();
        scanSourceSpec.enableClassLoaders(new URLClassLoader(new URL[] { classesDir.toUri().toURL() }));

        // Act
        final var classLoaderProbe = new ClassLoaderProbe(classpathSpec, scanSourceSpec, new LogNode());
        final var moduleFinder = classLoaderProbe.getModuleFinder();

        // Assert
        assertNotNull(moduleFinder, "ModuleFinder should be non-null");
        assertFalse(moduleFinder.getSystemModuleReferences().isEmpty(),
                "ModuleFinder should have found system modules");

        final var paths = resolvedPaths(classLoaderProbe);
        assertTrue(paths.remove(classesDir), "Classpath should have contained " + classesDir + ": " + paths);
        assertEquals(0, paths.size(), "Classpath should have no other entries: " + paths);
    }

    /**
     * The directory or jar this test class was loaded from, which is on {@code java.class.path}, and is loaded by
     * the application classloader.
     *
     * @return the path of the classpath element containing this test class.
     * @throws Exception
     *             if the location could not be determined.
     */
    private static Path testClasspathElement() throws Exception {
        return Path.of(ClassLoaderProbeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toRealPath();
    }

    /**
     * Get the resolved classpath element paths found by a {@link ClassLoaderProbe}.
     *
     * @param classLoaderProbe
     *            the classpath finder.
     * @return the resolved paths.
     */
    private static Set<Path> resolvedPaths(final ClassLoaderProbe classLoaderProbe) {
        final Set<Path> paths = new TreeSet<>();
        for (final String path : classLoaderProbe.getClasspathOrder().getClasspathEntryUniqueLocations()) {
            paths.add(Path.of(path));
        }
        return paths;
    }

    /**
     * The application classloader does not expose the locations it loads from, so {@code java.class.path} stands in
     * for them: naming the application classloader must find the classpath elements on {@code java.class.path}.
     */
    @Test
    public void applicationClassLoaderScansJavaClassPath() throws Exception {
        final var scanSourceSpec = new ScanSourceSpec();
        scanSourceSpec.enableClassLoaders(ClassLoader.getSystemClassLoader());

        final var classLoaderProbe = new ClassLoaderProbe(new ClasspathSpec(), scanSourceSpec, new LogNode());

        assertTrue(resolvedPaths(classLoaderProbe).contains(testClasspathElement()),
                "java.class.path should have been scanned");
    }

    /**
     * The platform classloader loads only system modules, so naming it must not find the {@code java.class.path}
     * classpath, which the platform classloader cannot load from. Modules are only searched for when a module
     * source is enabled, so naming a classloader does not enable them.
     */
    @Test
    public void platformClassLoaderDoesNotScanClasspath() throws Exception {
        final var classpathSpec = new ClasspathSpec();
        classpathSpec.ignoreParentClassLoaders = true;
        final var scanSourceSpec = new ScanSourceSpec();
        scanSourceSpec.enableClassLoaders(ClassLoader.getPlatformClassLoader());

        final var classLoaderProbe = new ClassLoaderProbe(classpathSpec, scanSourceSpec, new LogNode());

        assertNull(classLoaderProbe.getModuleFinder(), "Modules should not have been searched for");
        final var paths = resolvedPaths(classLoaderProbe);
        assertFalse(paths.contains(testClasspathElement()),
                "java.class.path should not have been scanned: " + paths);
    }
}
