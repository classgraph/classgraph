package io.github.classgraph.issues.issue704;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

/**
 * The same file was returned twice when it was reachable both as a module resource and as a classpath resource.
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
 */
public class Issue704Test {

    /**
     * The relative path of the resource that the module and the classpath element both contain.
     */
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
        final var jarFile = new File(dir, jarName);
        try (var outputStream = Files.newOutputStream(jarFile.toPath());
                var zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry(RESOURCE_PATH));
            zipOutputStream.write(content.getBytes(StandardCharsets.UTF_8));
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
        return scanResult.getClasspathURIs().stream().filter(uri -> uri.toString().contains(substring)).toList();
    }

    /**
     * Define a {@link ModuleLayer} containing the automatic module in the given jar.
     *
     * @param jarFile
     *            the jar to resolve as an automatic module.
     * @return the new {@link ModuleLayer}.
     */
    private static ModuleLayer defineModuleLayer(final File jarFile) {
        final var finder = ModuleFinder.of(jarFile.toPath());
        final var moduleReferences = finder.findAll();
        assertThat(moduleReferences).hasSize(1);
        final var moduleName = moduleReferences.iterator().next().descriptor().name();

        final var bootLayer = ModuleLayer.boot();
        final var configuration = bootLayer.configuration().resolve(finder, ModuleFinder.of(), Set.of(moduleName));
        return ModuleLayer
                .defineModulesWithOneLoader(configuration, List.of(bootLayer), Issue704Test.class.getClassLoader())
                .layer();
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
    public void sameFileReachedThroughModuleAndClasspathIsReturnedOnce(@TempDir final File tempDir)
            throws Exception {
        final var jarFile = buildJar(tempDir, "issue704a.jar", "MATCH (n) RETURN n;");
        final var moduleLayer = defineModuleLayer(jarFile);
        assertThat(moduleLayer).isNotNull();

        try (var classLoader = new URLClassLoader(new URL[] { jarFile.toURI().toURL() }, /* parent = */ null);
                var scanResult = new ClassGraph() //
                        .addModuleLayer(moduleLayer) //
                        .addClassLoader(classLoader) //
                        .acceptPaths("stuff") //
                        .scan()) {
            final var uris = scanResult.getAllResources().getURIs();
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
    public void sameFileReachedThroughASymlinkIsReturnedOnce(@TempDir final File tempDir) throws Exception {
        final var realDir = new File(tempDir, "real");
        realDir.mkdirs();
        final var symlinkedDir = tempDir.toPath().resolve("symlink");
        try {
            Files.createSymbolicLink(symlinkedDir, realDir.toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Symlinks are not supported (e.g. on Windows without developer mode enabled)
            assumeTrue(false, "Could not create a symlink");
        }
        final var jarFile = buildJar(realDir, "issue704d.jar", "MATCH (n) RETURN n;");
        final var symlinkedJarFile = symlinkedDir.resolve(jarFile.getName()).toFile();
        final var moduleLayer = defineModuleLayer(symlinkedJarFile);
        assertThat(moduleLayer).isNotNull();

        try (var classLoader = new URLClassLoader(new URL[] { jarFile.toURI().toURL() }, /* parent = */ null);
                var scanResult = new ClassGraph() //
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
    public void differentFilesWithTheSamePathAreBothReturned(@TempDir final File tempDir) throws Exception {
        final var moduleJarFile = buildJar(tempDir, "issue704b.jar", "MATCH (n) RETURN n;");
        final var classpathJarFile = buildJar(tempDir, "issue704c.jar", "a completely different file");
        final var moduleLayer = defineModuleLayer(moduleJarFile);
        assertThat(moduleLayer).isNotNull();

        try (var classLoader = new URLClassLoader(new URL[] { classpathJarFile.toURI().toURL() },
                /* parent = */ null);
                var scanResult = new ClassGraph() //
                        .addModuleLayer(moduleLayer) //
                        .addClassLoader(classLoader) //
                        .acceptPaths("stuff") //
                        .scan()) {
            final var uriStrs = scanResult.getAllResources().getURIs().stream().map(URI::toString).toList();
            assertThat(uriStrs).hasSize(2);
            assertThat(uriStrs.get(0)).endsWith("issue704b.jar!/" + RESOURCE_PATH);
            assertThat(uriStrs.get(1)).endsWith("issue704c.jar!/" + RESOURCE_PATH);
            // These are two different files, so they are two different classpath elements
            assertThat(classpathURIsMatching(scanResult, "issue704")).hasSize(2);
            // Sanity check that these really are two different files
            assertThat(List.of(moduleJarFile.length(), classpathJarFile.length())).doesNotHaveDuplicates();
        }
    }
}
