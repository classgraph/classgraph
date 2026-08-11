package nonapi.io.github.classgraph.classpath;

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

import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ClassLoaderAndModuleLayerSpec;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

public class ClasspathFinderTest {

    /**
     * Test that {@link ScanSpec#enableSystemJarsAndModules}, {@link ScanSpec#ignoreParentClassLoaders}, and
     * {@link ScanSpec#overrideClasspath} work in combination:
     * <p>
     * Only the system modules and the override classpath should be found.
     */
    @Test
    public void testOverrideClasspathAndEnableSystemModules(@TempDir final Path tmpDir) throws Exception {
        // Arrange
        final var classesDir = tmpDir.toAbsolutePath().normalize().toRealPath();
        final var scanSpec = new ScanSpec();
        scanSpec.enableSystemJarsAndModules = true;
        scanSpec.ignoreParentClassLoaders = true;
        scanSpec.overrideClasspath = List.of(classesDir);
        final var classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();

        // Act
        final var classpathFinder = new ClasspathFinder(scanSpec, classLoaderAndModuleLayerSpec,
                new ReflectionUtils(), new LogNode());
        final var moduleFinder = classpathFinder.getModuleFinder();

        // Assert
        assertNotNull(moduleFinder, "ModuleFinder should be non-null");
        assertFalse(moduleFinder.getSystemModuleRefs().isEmpty(), "ModuleFinder should have found system modules");

        final Set<Path> paths = new TreeSet<>();
        for (final String path : classpathFinder.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
            paths.add(Path.of(path));
        }
        assertTrue(paths.remove(classesDir), "Classpath should have contained " + classesDir + ": " + paths);
        assertEquals(0, paths.size(), "Classpath should have no other entries: " + paths);
    }

    /**
     * Test that {@link ScanSpec#enableSystemJarsAndModules}, {@link ScanSpec#ignoreParentClassLoaders}, and
     * {@link ClassLoaderAndModuleLayerSpec#overrideClassLoaders} work in combination:
     * <p>
     * Only the system modules and the override classloaders should be found.
     */
    @Test
    public void testOverrideClassLoaderAndEnableSystemModules(@TempDir final Path tmpDir) throws Exception {
        // Arrange
        final var classesDir = tmpDir.toAbsolutePath().normalize().toRealPath();
        final var scanSpec = new ScanSpec();
        scanSpec.enableSystemJarsAndModules = true;
        scanSpec.ignoreParentClassLoaders = true;
        final var classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();
        classLoaderAndModuleLayerSpec
                .overrideClassLoaders(new URLClassLoader(new URL[] { classesDir.toUri().toURL() }));

        // Act
        final var classpathFinder = new ClasspathFinder(scanSpec, classLoaderAndModuleLayerSpec,
                new ReflectionUtils(), new LogNode());
        final var moduleFinder = classpathFinder.getModuleFinder();

        // Assert
        assertNotNull(moduleFinder, "ModuleFinder should be non-null");
        assertFalse(moduleFinder.getSystemModuleRefs().isEmpty(), "ModuleFinder should have found system modules");

        final Set<Path> paths = new TreeSet<>();
        for (final String path : classpathFinder.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
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
        return Path.of(ClasspathFinderTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toRealPath();
    }

    /**
     * Get the resolved classpath element paths found by a {@link ClasspathFinder}.
     *
     * @param classpathFinder
     *            the classpath finder.
     * @return the resolved paths.
     */
    private static Set<Path> resolvedPaths(final ClasspathFinder classpathFinder) {
        final Set<Path> paths = new TreeSet<>();
        for (final String path : classpathFinder.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
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
        final var scanSpec = new ScanSpec();
        final var classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();
        classLoaderAndModuleLayerSpec.overrideClassLoaders(ClassLoader.getSystemClassLoader());

        final var classpathFinder = new ClasspathFinder(scanSpec, classLoaderAndModuleLayerSpec,
                new ReflectionUtils(), new LogNode());

        assertTrue(resolvedPaths(classpathFinder).contains(testClasspathElement()),
                "java.class.path should have been scanned");
        final var moduleFinder = classpathFinder.getModuleFinder();
        assertNotNull(moduleFinder, "Modules should have been searched for");
        assertNotNull(moduleFinder.getNonSystemModuleRefs(), "Non-system modules should have been scanned");
    }

    /**
     * The platform classloader loads only system modules, so passing it to {@code overrideClassLoaders()} must scan
     * the system modules, and must not scan the {@code java.class.path} classpath, which the platform classloader
     * cannot load from.
     */
    @Test
    public void platformClassLoaderOverrideDoesNotScanClasspath() throws Exception {
        final var scanSpec = new ScanSpec();
        final var classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();
        classLoaderAndModuleLayerSpec.overrideClassLoaders(ClassLoader.getPlatformClassLoader());

        final var classpathFinder = new ClasspathFinder(scanSpec, classLoaderAndModuleLayerSpec,
                new ReflectionUtils(), new LogNode());

        final var moduleFinder = classpathFinder.getModuleFinder();
        assertNotNull(moduleFinder, "Modules should have been searched for");
        assertFalse(moduleFinder.getSystemModuleRefs().isEmpty(), "System modules should have been scanned");
        final var paths = resolvedPaths(classpathFinder);
        assertFalse(paths.contains(testClasspathElement()),
                "java.class.path should not have been scanned: " + paths);
    }

    /**
     * The platform classloader is mapped to the scanning mechanism that can reach its classes whether it is passed
     * to {@code overrideClassLoaders()} or to {@code addClassLoader()}.
     */
    @Test
    public void addedPlatformClassLoaderEnablesSystemJarsAndModules() {
        final var scanSpec = new ScanSpec();
        final var classLoaderAndModuleLayerSpec = new ClassLoaderAndModuleLayerSpec();
        classLoaderAndModuleLayerSpec.addClassLoader(ClassLoader.getPlatformClassLoader());

        final var classpathFinder = new ClasspathFinder(scanSpec, classLoaderAndModuleLayerSpec,
                new ReflectionUtils(), new LogNode());

        final var moduleFinder = classpathFinder.getModuleFinder();
        assertNotNull(moduleFinder, "Modules should have been searched for");
        assertFalse(moduleFinder.getSystemModuleRefs().isEmpty(), "System modules should have been scanned");
    }
}
