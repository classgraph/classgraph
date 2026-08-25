package nonapi.io.github.classgraph.classpath;

import static nonapi.io.github.classgraph.classpath.SystemJarFinder.getJreLibOrExtJars;
import static nonapi.io.github.classgraph.classpath.SystemJarFinder.getJreRtJarPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.felix.framework.BundleWiringImpl;
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
        removeSystemJars(paths);
        assertEquals(0, paths.size(), "Classpath should have no other entries: " + paths);
    }

    /**
     * Remove rt.jar and all the JRE lib and ext jars from a set of classpath entry paths, checking that they were
     * all present. (All lib and ext jars are scanned when no lib or ext jar accept or reject criteria have been
     * added -- issue #813.)
     *
     * @param paths
     *            the classpath entry paths.
     */
    private static void removeSystemJars(final Set<Path> paths) {
        assertTrue(paths.remove(Paths.get(getJreRtJarPath())),
                "Classpath should have contained rt.jar: " + paths);
        for (final String libOrExtJar : getJreLibOrExtJars()) {
            assertTrue(paths.remove(Paths.get(libOrExtJar)),
                    "Classpath should have contained " + libOrExtJar + ": " + paths);
        }
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
        removeSystemJars(paths);
        assertEquals(0, paths.size(), "Classpath should have no other entries: " + paths);
    }

    /**
     * Test that when lib or ext jars are specifically accepted, only the accepted lib or ext jars are added to the
     * classpath, and that rejecting a lib or ext jar removes only that jar (#813).
     */
    @Test
    @EnabledForJreRange(max = JRE.JAVA_8)
    public void testLibOrExtJarAcceptReject(@TempDir final Path tmpDir) throws Exception {
        final Path classesDir = tmpDir.toAbsolutePath().normalize().toRealPath();
        final List<String> libOrExtJars = new ArrayList<>(getJreLibOrExtJars());
        assertTrue(libOrExtJars.size() > 1, "JRE should have more than one lib or ext jar");
        final String firstLibOrExtJar = libOrExtJars.get(0);

        // Accepting a single lib or ext jar should add only that jar
        ScanSpec scanSpec = new ScanSpec();
        scanSpec.enableSystemJarsAndModules = true;
        scanSpec.ignoreParentClassLoaders = true;
        scanSpec.overrideClasspath = Collections.<Object> singletonList(classesDir);
        scanSpec.libOrExtJarAcceptReject.addToAccept(firstLibOrExtJar);
        Set<Path> paths = classpathEntryPaths(scanSpec);
        assertTrue(paths.contains(Paths.get(firstLibOrExtJar)),
                "Classpath should have contained " + firstLibOrExtJar + ": " + paths);
        for (final String libOrExtJar : libOrExtJars.subList(1, libOrExtJars.size())) {
            assertTrue(!paths.contains(Paths.get(libOrExtJar)),
                    "Classpath should not have contained " + libOrExtJar + ": " + paths);
        }

        // Rejecting a single lib or ext jar should remove only that jar
        scanSpec = new ScanSpec();
        scanSpec.enableSystemJarsAndModules = true;
        scanSpec.ignoreParentClassLoaders = true;
        scanSpec.overrideClasspath = Collections.<Object> singletonList(classesDir);
        scanSpec.libOrExtJarAcceptReject.addToReject(firstLibOrExtJar);
        paths = classpathEntryPaths(scanSpec);
        assertTrue(!paths.contains(Paths.get(firstLibOrExtJar)),
                "Classpath should not have contained " + firstLibOrExtJar + ": " + paths);
        for (final String libOrExtJar : libOrExtJars.subList(1, libOrExtJars.size())) {
            assertTrue(paths.contains(Paths.get(libOrExtJar)),
                    "Classpath should have contained " + libOrExtJar + ": " + paths);
        }
    }

    /**
     * Find the classpath entries for a {@link ScanSpec}, as a set of {@link Path} objects.
     *
     * @param scanSpec
     *            the {@link ScanSpec}.
     * @return the classpath entry paths.
     */
    private static Set<Path> classpathEntryPaths(final ScanSpec scanSpec) {
        final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, new ReflectionUtils(), new LogNode());
        final Set<Path> paths = new TreeSet<>();
        for (final String path : classpathFinder.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
            paths.add(Paths.get(path));
        }
        return paths;
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

    /**
     * Get the classpath element that this test class was loaded from.
     *
     * @return the classpath element path.
     * @throws Exception
     *             if the location of the classpath element could not be found
     */
    private static Path testClasspathElement() throws Exception {
        return Paths.get(ClasspathFinderTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toRealPath();
    }

    /**
     * Get the platform classloader, which only exists in JDK 9+, without referring to
     * {@code ClassLoader#getPlatformClassLoader()}, which does not exist in the JDK 8 API this library is
     * compiled against.
     *
     * @return the platform classloader.
     * @throws Exception
     *             if the method could not be found or invoked, as on JDK 8
     */
    private static ClassLoader platformClassLoader() throws Exception {
        return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
    }

    /**
     * Test that when the JDK's application classloader is passed to {@link ScanSpec#overrideClassLoaders}, both the
     * {@code java.class.path} classpath and the non-system modules are scanned, since those are what the
     * application classloader loads from.
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void testApplicationClassLoaderOverrideScansClasspathAndNonSystemModules() throws Exception {
        // Arrange
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.overrideClassLoaders(ClassLoader.getSystemClassLoader());

        // Act
        final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, new ReflectionUtils(), new LogNode());

        // Assert
        final Set<Path> paths = new TreeSet<>();
        for (final String path : classpathFinder.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
            paths.add(Paths.get(path));
        }
        assertTrue(paths.contains(testClasspathElement()),
                "java.class.path should have been scanned: " + paths);
        final ModuleFinder moduleFinder = classpathFinder.getModuleFinder();
        assertNotNull(moduleFinder, "Modules should have been searched for");
        assertNotNull(moduleFinder.getNonSystemModuleRefs(), "Non-system modules should have been scanned");
    }

    /**
     * More than one {@code ClassLoaderHandler} can handle the same classloader -- a classloader that a handler
     * recognizes by name may also extend {@link URLClassLoader}, say. Each of those handlers contributes the
     * classpath entries it knows how to read, but the classloader itself must appear in the classloader delegation
     * order only once, since that order is the order in which classloaders are tried when a class is loaded.
     *
     * @throws Exception
     *             if the classpath could not be read
     */
    @Test
    public void aClassLoaderHandledByMoreThanOneHandlerIsInTheDelegationOrderOnce() throws Exception {
        // Arrange: a classloader that FelixClassLoaderHandler handles by name, and URLClassLoaderHandler handles
        // because it extends URLClassLoader
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.overrideClassLoaders(new BundleWiringImpl.BundleClassLoader(new URL[0]));

        // Act
        final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, new ReflectionUtils(), new LogNode());

        // Assert
        final List<ClassLoader> classLoaderOrder = Arrays
                .asList(classpathFinder.getClassLoaderOrderRespectingParentDelegation());
        assertEquals(new LinkedHashSet<ClassLoader>(classLoaderOrder).size(), classLoaderOrder.size(),
                "No classloader should be listed twice: " + classLoaderOrder);
    }

    /**
     * Test that when the JDK's platform classloader is passed to {@link ScanSpec#overrideClassLoaders}, the system
     * jars and modules are scanned, but the {@code java.class.path} classpath is not, since the platform
     * classloader cannot load from it.
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void testPlatformClassLoaderOverrideDoesNotScanClasspath() throws Exception {
        // Arrange
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.overrideClassLoaders(platformClassLoader());

        // Act
        final ClasspathFinder classpathFinder = new ClasspathFinder(scanSpec, new ReflectionUtils(), new LogNode());

        // Assert
        final ModuleFinder moduleFinder = classpathFinder.getModuleFinder();
        assertNotNull(moduleFinder, "Modules should have been searched for");
        assertTrue(moduleFinder.getSystemModuleRefs().size() > 0, "System modules should have been scanned");
        final Set<Path> paths = new TreeSet<>();
        for (final String path : classpathFinder.getClasspathOrder().getClasspathEntryUniqueResolvedPaths()) {
            paths.add(Paths.get(path));
        }
        assertTrue(!paths.contains(testClasspathElement()),
                "java.class.path should not have been scanned: " + paths);
    }
}
