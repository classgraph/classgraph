package io.github.classgraph.classpath.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.classpath.internal.spec.ClassLoaderAndModuleLayerSpec;
import io.github.classgraph.classpath.internal.spec.ClasspathSpec;

public class ClassLoaderProbeTest {

    /**
     * Test that {@link ClasspathSpec#enableSystemJarsAndModules}, {@link ClasspathSpec#ignoreParentClassLoaders},
     * and {@link ClasspathSpec#overrideClasspath} work in combination:
     * <p>
     * Only the system modules and the override classpath should be found.
     */
    @Test
    public void testOverrideClasspathAndEnableSystemModules(@TempDir final Path tmpDir) throws Exception {
        // Arrange
        final var classesDir = tmpDir.toAbsolutePath().normalize().toRealPath();
        final var classpathSpec = new ClasspathSpec();
        classpathSpec.enableSystemJarsAndModules = true;
        classpathSpec.ignoreParentClassLoaders = true;
        classpathSpec.overrideClasspath = List.of(classesDir);
        final var classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();

        // Act
        final var classLoaderProbe = new ClassLoaderProbe(classpathSpec, classLoaderAndModuleLayerSpec,
                new LogNode());
        final var moduleFinder = classLoaderProbe.getModuleFinder();

        // Assert
        assertNotNull(moduleFinder, "ModuleFinder should be non-null");
        assertFalse(moduleFinder.getSystemModuleReferences().isEmpty(),
                "ModuleFinder should have found system modules");

        final Set<Path> paths = new TreeSet<>();
        for (final String path : classLoaderProbe.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
            paths.add(Path.of(path));
        }
        assertTrue(paths.remove(classesDir), "Classpath should have contained " + classesDir + ": " + paths);
        assertEquals(0, paths.size(), "Classpath should have no other entries: " + paths);
    }

    /**
     * Test that {@link ClasspathSpec#enableSystemJarsAndModules}, {@link ClasspathSpec#ignoreParentClassLoaders},
     * and {@link ClassLoaderAndModuleLayerSpec#overrideClassLoaders} work in combination:
     * <p>
     * Only the system modules and the override classloaders should be found.
     */
    @Test
    public void testOverrideClassLoaderAndEnableSystemModules(@TempDir final Path tmpDir) throws Exception {
        // Arrange
        final var classesDir = tmpDir.toAbsolutePath().normalize().toRealPath();
        final var classpathSpec = new ClasspathSpec();
        classpathSpec.enableSystemJarsAndModules = true;
        classpathSpec.ignoreParentClassLoaders = true;
        final var classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();
        classLoaderAndModuleLayerSpec
                .overrideClassLoaders(new URLClassLoader(new URL[] { classesDir.toUri().toURL() }));

        // Act
        final var classLoaderProbe = new ClassLoaderProbe(classpathSpec, classLoaderAndModuleLayerSpec,
                new LogNode());
        final var moduleFinder = classLoaderProbe.getModuleFinder();

        // Assert
        assertNotNull(moduleFinder, "ModuleFinder should be non-null");
        assertFalse(moduleFinder.getSystemModuleReferences().isEmpty(),
                "ModuleFinder should have found system modules");

        final Set<Path> paths = new TreeSet<>();
        for (final String path : classLoaderProbe.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
            paths.add(Path.of(path));
        }
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
        for (final String path : classLoaderProbe.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
            paths.add(Path.of(path));
        }
        return paths;
    }

    /**
     * The application classloader does not expose the locations it loads from, so passing it to
     * {@code overrideClassLoaders()} must scan the two things it does load from: the {@code java.class.path}
     * classpath, and the non-system modules.
     */
    @Test
    public void applicationClassLoaderOverrideScansClasspathAndNonSystemModules() throws Exception {
        final var classpathSpec = new ClasspathSpec();
        final var classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();
        classLoaderAndModuleLayerSpec.overrideClassLoaders(ClassLoader.getSystemClassLoader());

        final var classLoaderProbe = new ClassLoaderProbe(classpathSpec, classLoaderAndModuleLayerSpec,
                new LogNode());

        assertTrue(resolvedPaths(classLoaderProbe).contains(testClasspathElement()),
                "java.class.path should have been scanned");
        final var moduleFinder = classLoaderProbe.getModuleFinder();
        assertNotNull(moduleFinder, "Modules should have been searched for");
        assertNotNull(moduleFinder.getNonSystemModuleReferences(), "Non-system modules should have been scanned");
    }

    /**
     * The platform classloader loads only system modules, so passing it to {@code overrideClassLoaders()} must scan
     * the system modules, and must not scan the {@code java.class.path} classpath, which the platform classloader
     * cannot load from.
     */
    @Test
    public void platformClassLoaderOverrideDoesNotScanClasspath() throws Exception {
        final var classpathSpec = new ClasspathSpec();
        final var classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();
        classLoaderAndModuleLayerSpec.overrideClassLoaders(ClassLoader.getPlatformClassLoader());

        final var classLoaderProbe = new ClassLoaderProbe(classpathSpec, classLoaderAndModuleLayerSpec,
                new LogNode());

        final var moduleFinder = classLoaderProbe.getModuleFinder();
        assertNotNull(moduleFinder, "Modules should have been searched for");
        assertFalse(moduleFinder.getSystemModuleReferences().isEmpty(), "System modules should have been scanned");
        final var paths = resolvedPaths(classLoaderProbe);
        assertFalse(paths.contains(testClasspathElement()),
                "java.class.path should not have been scanned: " + paths);
    }

    /**
     * The platform classloader is mapped to the scanning mechanism that can reach its classes whether it is passed
     * to {@code overrideClassLoaders()} or to {@code addClassLoader()}.
     */
    @Test
    public void addedPlatformClassLoaderEnablesSystemJarsAndModules() {
        final var classpathSpec = new ClasspathSpec();
        final var classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();
        classLoaderAndModuleLayerSpec.addClassLoader(ClassLoader.getPlatformClassLoader());

        final var classLoaderProbe = new ClassLoaderProbe(classpathSpec, classLoaderAndModuleLayerSpec,
                new LogNode());

        final var moduleFinder = classLoaderProbe.getModuleFinder();
        assertNotNull(moduleFinder, "Modules should have been searched for");
        assertFalse(moduleFinder.getSystemModuleReferences().isEmpty(), "System modules should have been scanned");
    }
}
