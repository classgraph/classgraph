package io.github.classgraph.issues.issue704;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

/**
 * The same file was returned twice when it was reachable both as a module resource and as a classpath resource
 * (#704).
 *
 * <p>
 * In the original report, Maven Surefire spliced the test output directory into the module under test with
 * {@code --patch-module <module>=target/test-classes}, while also leaving {@code target/test-classes} on the
 * classpath, so the module and the classpath element both listed the same file, and
 * {@link ScanResult#getAllResources()} returned it twice with identical {@link Resource#getURI()} values.
 *
 * <p>
 * A module cannot be patched from within a running JVM ({@code --patch-module} is read only at launch), but the
 * same collision occurs whenever one jar is on both the module path and the classpath, which is what these tests
 * set up. Two <i>different</i> files that happen to share a relative path are not duplicates, and must both still
 * be returned.
 *
 * <p>
 * The JPMS API is called reflectively because the tests are compiled with {@code --release 8}; the tests are
 * skipped on JDK 8, which has no modules.
 */
public class Issue704Test {

    /** The relative path of the resource that the module and the classpath element both contain. */
    private static final String RESOURCE_PATH = "stuff/whatever.cypher";

    /**
     * Build a jar containing a single resource at {@link #RESOURCE_PATH}.
     *
     * @param dir
     *            the directory to create the jar in.
     * @param jarName
     *            the name of the jar (which determines the automatic module name).
     * @param content
     *            the content of the resource.
     * @return the jar file.
     * @throws IOException
     *             if the jar could not be written.
     */
    private static File buildJar(final File dir, final String jarName, final String content) throws IOException {
        final File jarFile = new File(dir, jarName);
        try (OutputStream outputStream = Files.newOutputStream(jarFile.toPath());
                ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry(RESOURCE_PATH));
            zipOutputStream.write(content.getBytes("UTF-8"));
            zipOutputStream.closeEntry();
        }
        return jarFile;
    }

    /**
     * Get the classpath element URIs of a scan that contain the given substring. (The test jars are added to the
     * default classloaders rather than replacing them, so the rest of the classpath is present too, and has to be
     * filtered out.)
     *
     * @param scanResult
     *            the scan result.
     * @param substring
     *            the substring to look for in the URI.
     * @return the matching classpath element URIs.
     */
    private static List<URI> classpathURIsMatching(final ScanResult scanResult, final String substring) {
        final List<URI> matchingURIs = new java.util.ArrayList<>();
        for (final URI uri : scanResult.getClasspathURIs()) {
            if (uri.toString().contains(substring)) {
                matchingURIs.add(uri);
            }
        }
        return matchingURIs;
    }

    /**
     * Define a {@code ModuleLayer} containing the automatic module in the given jar, using reflection so that this
     * class still compiles with {@code --release 8}.
     *
     * @param jarFile
     *            the jar to resolve as an automatic module.
     * @return the new {@code ModuleLayer}, or null if this JVM does not support modules.
     * @throws Exception
     *             if the layer could not be defined.
     */
    private static Object defineModuleLayer(final File jarFile) throws Exception {
        final Class<?> moduleFinderClass;
        try {
            moduleFinderClass = Class.forName("java.lang.module.ModuleFinder");
        } catch (final ClassNotFoundException e) {
            // JDK 8 or below
            return null;
        }
        final Class<?> moduleLayerClass = Class.forName("java.lang.ModuleLayer");
        final Class<?> configurationClass = Class.forName("java.lang.module.Configuration");
        final Class<?> moduleReferenceClass = Class.forName("java.lang.module.ModuleReference");
        final Class<?> moduleDescriptorClass = Class.forName("java.lang.module.ModuleDescriptor");

        // ModuleFinder finder = ModuleFinder.of(jarFile.toPath())
        final Method moduleFinderOf = moduleFinderClass.getMethod("of", Path[].class);
        final Object finder = moduleFinderOf.invoke(null, (Object) new Path[] { jarFile.toPath() });
        // ModuleFinder empty = ModuleFinder.of()
        final Object emptyFinder = moduleFinderOf.invoke(null, (Object) new Path[0]);

        // String moduleName = finder.findAll().iterator().next().descriptor().name()
        @SuppressWarnings("unchecked")
        final Set<Object> moduleReferences = (Set<Object>) moduleFinderClass.getMethod("findAll").invoke(finder);
        assertThat(moduleReferences).hasSize(1);
        final Object moduleReference = moduleReferences.iterator().next();
        final Object descriptor = moduleReferenceClass.getMethod("descriptor").invoke(moduleReference);
        final String moduleName = (String) moduleDescriptorClass.getMethod("name").invoke(descriptor);

        // ModuleLayer bootLayer = ModuleLayer.boot()
        final Object bootLayer = moduleLayerClass.getMethod("boot").invoke(null);
        // Configuration config = bootLayer.configuration().resolve(finder, ModuleFinder.of(), Set.of(moduleName))
        final Object bootConfiguration = moduleLayerClass.getMethod("configuration").invoke(bootLayer);
        final Object configuration = configurationClass
                .getMethod("resolve", moduleFinderClass, moduleFinderClass, Collection.class)
                .invoke(bootConfiguration, finder, emptyFinder, Collections.singleton(moduleName));
        // ModuleLayer layer = bootLayer.defineModulesWithOneLoader(config, List.of(bootLayer), classLoader).layer()
        final Object controller = moduleLayerClass
                .getMethod("defineModulesWithOneLoader", configurationClass, List.class, ClassLoader.class)
                .invoke(bootLayer, configuration, Collections.singletonList(bootLayer),
                        Issue704Test.class.getClassLoader());
        return controller.getClass().getMethod("layer").invoke(controller);
    }

    /**
     * A file that is reachable both as a module resource and as a classpath resource should be returned once, not
     * once per classpath element that reaches it.
     *
     * @param tempDir
     *            the temp dir.
     * @throws Exception
     *             if the test jar or module layer could not be created.
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void sameFileReachedThroughModuleAndClasspathIsReturnedOnce(@TempDir final File tempDir)
            throws Exception {
        final File jarFile = buildJar(tempDir, "issue704a.jar", "MATCH (n) RETURN n;");
        final Object moduleLayer = defineModuleLayer(jarFile);
        assertThat(moduleLayer).isNotNull();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] { jarFile.toURI().toURL() },
                /* parent = */ null);
                ScanResult scanResult = new ClassGraph() //
                        .addModuleLayer(moduleLayer) //
                        .addClassLoader(classLoader) //
                        .acceptPaths("stuff") //
                        .scan()) {
            final List<URI> uris = scanResult.getAllResources().getURIs();
            assertThat(uris).hasSize(1);
            assertThat(uris.get(0).toString()).endsWith("issue704a.jar!/" + RESOURCE_PATH);
            // The jar is the same file whether it is reached as a module or as a classpath element, so it is a
            // single classpath element, not two
            assertThat(classpathURIsMatching(scanResult, "issue704a.jar")).hasSize(1);
        }
    }

    /**
     * The same file reached through two different paths -- one of them through a symlinked parent directory -- is
     * still the same file, so it should still be returned once. (On macOS this is not a corner case: the temp
     * directory {@code /var/folders/...} is reached through the symlink {@code /var -> /private/var}, so the module
     * path and the classpath disagree on the path of the same jar.)
     *
     * @param tempDir
     *            the temp dir.
     * @throws Exception
     *             if the test jar or module layer could not be created.
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void sameFileReachedThroughASymlinkIsReturnedOnce(@TempDir final File tempDir) throws Exception {
        final File realDir = new File(tempDir, "real");
        realDir.mkdirs();
        final Path symlinkedDir = tempDir.toPath().resolve("symlink");
        try {
            Files.createSymbolicLink(symlinkedDir, realDir.toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Symlinks are not supported (e.g. on Windows without developer mode enabled)
            assumeTrue(false, "Could not create a symlink");
        }
        final File jarFile = buildJar(realDir, "issue704d.jar", "MATCH (n) RETURN n;");
        final File symlinkedJarFile = symlinkedDir.resolve(jarFile.getName()).toFile();
        final Object moduleLayer = defineModuleLayer(symlinkedJarFile);
        assertThat(moduleLayer).isNotNull();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] { jarFile.toURI().toURL() },
                /* parent = */ null);
                ScanResult scanResult = new ClassGraph() //
                        .addModuleLayer(moduleLayer) //
                        .addClassLoader(classLoader) //
                        .acceptPaths("stuff") //
                        .scan()) {
            assertThat(scanResult.getAllResources().getURIs()).hasSize(1);
            assertThat(classpathURIsMatching(scanResult, "issue704d.jar")).hasSize(1);
        }
    }

    /**
     * Two different files that happen to share a relative path are not duplicates of each other, so both must still
     * be returned -- otherwise deduplicating the
     * {@link #sameFileReachedThroughModuleAndClasspathIsReturnedOnce(File)} case would lose resources.
     *
     * @param tempDir
     *            the temp dir.
     * @throws Exception
     *             if the test jars or module layer could not be created.
     */
    @Test
    @EnabledForJreRange(min = JRE.JAVA_9)
    public void differentFilesWithTheSamePathAreBothReturned(@TempDir final File tempDir) throws Exception {
        final File moduleJarFile = buildJar(tempDir, "issue704b.jar", "MATCH (n) RETURN n;");
        final File classpathJarFile = buildJar(tempDir, "issue704c.jar", "a completely different file");
        final Object moduleLayer = defineModuleLayer(moduleJarFile);
        assertThat(moduleLayer).isNotNull();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] { classpathJarFile.toURI().toURL() },
                /* parent = */ null);
                ScanResult scanResult = new ClassGraph() //
                        .addModuleLayer(moduleLayer) //
                        .addClassLoader(classLoader) //
                        .acceptPaths("stuff") //
                        .scan()) {
            final List<String> uriStrs = new java.util.ArrayList<>();
            for (final URI uri : scanResult.getAllResources().getURIs()) {
                uriStrs.add(uri.toString());
            }
            assertThat(uriStrs).hasSize(2);
            assertThat(uriStrs.get(0)).endsWith("issue704b.jar!/" + RESOURCE_PATH);
            assertThat(uriStrs.get(1)).endsWith("issue704c.jar!/" + RESOURCE_PATH);
            // These are two different files, so they are two different classpath elements
            assertThat(classpathURIsMatching(scanResult, "issue704")).hasSize(2);
            // Sanity check that these really are two different files
            assertThat(Arrays.asList(moduleJarFile.length(), classpathJarFile.length())).doesNotHaveDuplicates();
        }
    }
}
