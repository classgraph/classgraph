package nonapi.io.github.classgraph.classpath;

import static nonapi.io.github.classgraph.classpath.SystemJarFinder.getJreRtJarPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.LogNode;

public class ClasspathFinderTest {

    /**
     * Test that {@link ScanSpec#enableSystemJarsAndModules}, {@link ScanSpec#ignoreParentClassLoaders}, and
     * {@link ScanSpec#overrideClasspath} work in combination:
     * <p>
     * Only the system jars and the override classpath should be found.
     */
    @Test
    @EnabledForJreRange(max = JRE.JAVA_8)
    public void testOverrideClasspathAndEnableSystemJars(@TempDir final Path tmpDir) throws Exception {
        // Arrange
        final Path classesDir = tmpDir.toAbsolutePath().normalize().toRealPath();
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.enableSystemJarsAndModules = true;
        scanSpec.ignoreParentClassLoaders = true;
        scanSpec.overrideClasspath = Collections.singletonList(classesDir);

        // Act
        final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, new ReflectionUtils(), new LogNode());

        // Assert
        final Set<Path> paths = new TreeSet<>();
        for (final String path : classpathFinder.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
            paths.add(Paths.get(path));
        }
        assertTrue(paths.remove(classesDir), "Classpath should have contained " + classesDir + ": " + paths);
        assertTrue(paths.remove(Paths.get(getJreRtJarPath())),
                "Classpath should have contained system jars: " + paths);
        assertEquals(0, paths.size(), "Classpath should have no other entries: " + paths);
    }

    /**
     * Test that {@link ScanSpec#enableSystemJarsAndModules}, {@link ScanSpec#ignoreParentClassLoaders}, and
     * {@link ScanSpec#overrideClassLoaders} work in combination:
     * <p>
     * Only the system jars and the override classloaders should be found.
     */
    @Test
    @EnabledForJreRange(max = JRE.JAVA_8)
    public void testOverrideClassLoaderAndEnableSystemJars(@TempDir final Path tmpDir) throws Exception {
        // Arrange
        final Path classesDir = tmpDir.toAbsolutePath().normalize().toRealPath();
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.enableSystemJarsAndModules = true;
        scanSpec.ignoreParentClassLoaders = true;
        scanSpec.overrideClassLoaders(new URLClassLoader(new URL[] { classesDir.toUri().toURL() }));

        // Act
        final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, new ReflectionUtils(), new LogNode());

        // Assert
        final Set<Path> paths = new TreeSet<>();
        for (final String path : classpathFinder.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
            paths.add(Paths.get(path));
        }
        assertTrue(paths.remove(classesDir), "Classpath should have contained " + classesDir + ": " + paths);
        assertTrue(paths.remove(Paths.get(getJreRtJarPath())),
                "Classpath should have contained system jars: " + paths);
        assertEquals(0, paths.size(), "Classpath should have no other entries: " + paths);
    }

    /**
     * Test that {@link ScanSpec#enableSystemJarsAndModules}, {@link ScanSpec#ignoreParentClassLoaders}, and
     * {@link ScanSpec#overrideClasspath} work in combination:
     * <p>
     * Only the system modules and the override classpath should be found.
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void testOverrideClasspathAndEnableSystemModules(@TempDir final Path tmpDir) throws Exception {
        // Arrange
        final Path classesDir = tmpDir.toAbsolutePath().normalize().toRealPath();
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.enableSystemJarsAndModules = true;
        scanSpec.ignoreParentClassLoaders = true;
        scanSpec.overrideClasspath = Collections.<Object> singletonList(classesDir);

        // Act
        final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, new ReflectionUtils(), new LogNode());
        final ModuleFinder moduleFinder = classpathFinder.getModuleFinder();

        // Assert
        assertNotNull(moduleFinder, "ModuleFinder should be non-null");
        assertTrue(moduleFinder.getSystemModuleRefs().size() > 0, "ModuleFinder should have found system modules");

        final Set<Path> paths = new TreeSet<>();
        for (final String path : classpathFinder.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
            paths.add(Paths.get(path));
        }
        assertTrue(paths.remove(classesDir), "Classpath should have contained " + classesDir + ": " + paths);
        assertEquals(0, paths.size(), "Classpath should have no other entries: " + paths);
    }

    /**
     * Test that {@link ScanSpec#enableSystemJarsAndModules}, {@link ScanSpec#ignoreParentClassLoaders}, and
     * {@link ScanSpec#overrideClassLoaders} work in combination:
     * <p>
     * Only the system modules and the override classloaders should be found.
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void testOverrideClassLoaderAndEnableSystemModules(@TempDir final Path tmpDir) throws Exception {
        // Arrange
        final Path classesDir = tmpDir.toAbsolutePath().normalize().toRealPath();
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.enableSystemJarsAndModules = true;
        scanSpec.ignoreParentClassLoaders = true;
        scanSpec.overrideClassLoaders(new URLClassLoader(new URL[] { classesDir.toUri().toURL() }));

        // Act
        final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, new ReflectionUtils(), new LogNode());
        final ModuleFinder moduleFinder = classpathFinder.getModuleFinder();

        // Assert
        assertNotNull(moduleFinder, "ModuleFinder should be non-null");
        assertTrue(moduleFinder.getSystemModuleRefs().size() > 0, "ModuleFinder should have found system modules");

        final Set<Path> paths = new TreeSet<>();
        for (final String path : classpathFinder.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
            paths.add(Paths.get(path));
        }
        assertTrue(paths.remove(classesDir), "Classpath should have contained " + classesDir + ": " + paths);
        assertEquals(0, paths.size(), "Classpath should have no other entries: " + paths);
    }
}
