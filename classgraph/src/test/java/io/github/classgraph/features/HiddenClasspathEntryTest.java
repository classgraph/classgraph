package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ChildJvm;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.base.internal.reflection.ReflectionUtils;

/**
 * A classpath entry that is listed in no system property, and is reachable only through the internal
 * {@code URLClassPath} field of a classloader, is still found and scanned.
 *
 * <p>
 * Two of the three ways to produce such an entry need a JVM that was launched with the right command line option,
 * so those tests run a child JVM and read back what it found.
 */
// #537
class HiddenClasspathEntryTest {
    /** The directory of the resource that only the hidden classpath entry contains. */
    static final String RESOURCE_DIR = "hiddenclasspathentry";

    /** The path of the resource that only the hidden classpath entry contains. */
    static final String RESOURCE_PATH = RESOURCE_DIR + "/appended.txt";

    /**
     * Build a jar containing only the resource at {@link #RESOURCE_PATH}.
     *
     * @param dir
     *            the directory to create the jar in.
     * @param jarName
     *            the name of the jar.
     * @return the jar file.
     * @throws IOException
     *             if the jar could not be written.
     */
    private static File buildResourceJar(final File dir, final String jarName) throws IOException {
        final var jarFile = new File(dir, jarName);
        try (var outputStream = Files.newOutputStream(jarFile.toPath());
                var zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry(RESOURCE_PATH));
            zipOutputStream.write("appended".getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return jarFile;
    }

    /**
     * Build a Java agent jar whose {@code premain} method is
     * {@link ClasspathAppendingAgent#premain(String, java.lang.instrument.Instrumentation)}. The jar contains
     * nothing but its manifest, since the agent class is already on the classpath of the child JVM.
     *
     * @param dir
     *            the directory to create the jar in.
     * @return the agent jar file.
     * @throws IOException
     *             if the jar could not be written.
     */
    private static File buildAgentJar(final File dir) throws IOException {
        final var manifest = new Manifest();
        final var mainAttributes = manifest.getMainAttributes();
        mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mainAttributes.putValue("Premain-Class", ClasspathAppendingAgent.class.getName());
        final var agentJarFile = new File(dir, "agent.jar");
        try (var outputStream = Files.newOutputStream(agentJarFile.toPath())) {
            // No entries -- the manifest is the whole jar, so all that is left is to write the central
            // directory. finish() does that without closing outputStream, which the try does.
            new JarOutputStream(outputStream, manifest).finish();
        }
        return agentJarFile;
    }

    /**
     * Get the path of the {@code java} executable of the currently-running JVM.
     *
     * @return the path of the {@code java} executable.
     */
    private static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    /**
     * Run {@link HiddenClasspathEntryScanner} in a child JVM launched with the given JVM options, on the classpath
     * of the currently-running JVM.
     *
     * @param jvmOptions
     *            the JVM options to launch the child JVM with.
     * @return everything the child JVM wrote to stdout and stderr.
     * @throws Exception
     *             if the child JVM could not be run.
     */
    private static String runChildJvm(final List<String> jvmOptions) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(HiddenClasspathEntryScanner.class.getName());
        return ChildJvm.run(new ProcessBuilder(command));
    }

    /**
     * A jar appended to the system classloader's search path by a Java agent is scanned, even though
     * {@code Instrumentation#appendToSystemClassLoaderSearch(java.util.jar.JarFile)} does not add it to the
     * {@code java.class.path} system property.
     *
     * @param tempDir
     *            the temp dir.
     * @throws Exception
     *             if the test jars could not be created, or the child JVM could not be run.
     */
    @Test
    void jarAppendedBySystemClassLoaderSearchIsScanned(@TempDir final File tempDir) throws Exception {
        final var appendedJarFile = buildResourceJar(tempDir, "agent-appended.jar");
        final var agentJarFile = buildAgentJar(tempDir);
        final var output = runChildJvm(List.of("-javaagent:" + agentJarFile + "=" + appendedJarFile));
        assertThat(output).as("Child JVM output:%n%s", output).contains("FOUND=true");
    }

    /**
     * A jar appended to the boot classpath is scanned. The boot classpath append is not readable as a system
     * property, and the bootstrap classloader that holds it is not reachable through
     * {@link ClassLoader#getParent()}, so this is reachable only through the JDK's internals. (A Java agent whose
     * manifest has a {@code Boot-Class-Path} attribute produces the same thing.)
     *
     * @param tempDir
     *            the temp dir.
     * @throws Exception
     *             if the test jar could not be created, or the child JVM could not be run.
     */
    @Test
    void jarAppendedToTheBootClasspathIsScanned(@TempDir final File tempDir) throws Exception {
        final var appendedJarFile = buildResourceJar(tempDir, "boot-appended.jar");
        final var output = runChildJvm(List.of("-Xbootclasspath/a:" + appendedJarFile));
        assertThat(output).as("Child JVM output:%n%s", output).contains("FOUND=true");
    }

    /**
     * Add a URL to the {@code unopenedUrls} field of a classloader's {@code jdk.internal.loader.URLClassPath},
     * without adding it to the {@code path} field that {@link URLClassLoader#getURLs()} returns.
     *
     * <p>
     * This is the state the JDK itself produces when it expands the {@code Class-Path} manifest attribute of a jar
     * it has opened, but ClassGraph expands that attribute itself, so an entry that got there by that route would
     * be found either way. Putting an entry there directly is the only way to check that the field is really read.
     *
     * @param classLoader
     *            the classloader whose {@code URLClassPath} should be modified.
     * @param url
     *            the URL to add.
     * @return true if the field was found and modified.
     */
    @SuppressWarnings("unchecked")
    private static boolean addUnlistedURL(final URLClassLoader classLoader, final URL url) {
        final var ucp = ReflectionUtils.getFieldVal(false, classLoader, "ucp");
        if (ucp == null) {
            return false;
        }
        final var unopenedUrls = ReflectionUtils.getFieldVal(false, ucp, "unopenedUrls");
        if (!(unopenedUrls instanceof Collection)) {
            return false;
        }
        synchronized (unopenedUrls) {
            ((Collection<URL>) unopenedUrls).add(url);
        }
        return true;
    }

    /**
     * A classpath entry that a classloader's {@code URLClassPath} holds only in the internal fields that
     * {@link URLClassLoader#getURLs()} does not expose is still found and scanned.
     *
     * @param tempDir
     *            the temp dir.
     * @throws Exception
     *             if the test jar could not be created.
     */
    @Test
    void entryHeldOnlyInTheURLClassPathInternalsIsScanned(@TempDir final File tempDir) throws Exception {
        final var unlistedJarFile = buildResourceJar(tempDir, "unlisted.jar");
        try (var classLoader = new URLClassLoader(new URL[0], /* parent = */ null)) {
            assumeTrue(addUnlistedURL(classLoader, unlistedJarFile.toURI().toURL()),
                    "Could not reach the URLClassPath internals");
            // The jar is in no system property and is not returned by getURLs(), so this is the only way to find it
            assertThat(classLoader.getURLs()).isEmpty();
            try (var scanResult = new ClassGraph().enableClassLoaders(classLoader).acceptPaths(RESOURCE_DIR)
                    .scan()) {
                assertThat(scanResult.getResourcesWithPath(RESOURCE_PATH)).isNotEmpty();
            }
        }
    }

    /**
     * A classpath entry that a classloader's {@code URLClassPath} holds only in its internal fields is still found
     * and scanned after the classloader has opened it, when it is no longer listed as unopened.
     *
     * @param tempDir
     *            the temp dir.
     * @throws Exception
     *             if the test jar could not be created.
     */
    @Test
    void openedUnlistedEntryIsScanned(@TempDir final File tempDir) throws Exception {
        final var unlistedJarFile = buildResourceJar(tempDir, "unlisted.jar");
        try (var classLoader = new URLClassLoader(new URL[0], /* parent = */ null)) {
            assumeTrue(addUnlistedURL(classLoader, unlistedJarFile.toURI().toURL()),
                    "Could not reach the URLClassPath internals");
            // Looking up the resource opens the jar, which moves it out of the unopened entries
            assertThat(classLoader.getResource(RESOURCE_PATH)).isNotNull();
            assertThat(classLoader.getURLs()).isEmpty();
            try (var scanResult = new ClassGraph().enableClassLoaders(classLoader).acceptPaths(RESOURCE_DIR)
                    .scan()) {
                assertThat(scanResult.getResourcesWithPath(RESOURCE_PATH)).isNotEmpty();
            }
        }
    }

    /**
     * An opened classpath directory whose name contains the path separator character is read as one entry, not
     * split into two at that character. Splitting {@code app:v2} on Linux, or {@code app;v2} on Windows, would add
     * the unrelated sibling directory {@code app}, which the classloader does not load from.
     *
     * @param tempDir
     *            the temp dir.
     * @throws Exception
     *             if the test files could not be created.
     */
    @Test
    void openedDirWhoseNameContainsThePathSeparatorDoesNotAddItsSibling(@TempDir final File tempDir)
            throws Exception {
        final var dir = new File(tempDir, "app" + File.pathSeparator + "v2");
        final var siblingDir = new File(tempDir, "app");
        final var innerPath = RESOURCE_DIR + "/inner.txt";
        final var siblingPath = RESOURCE_DIR + "/sibling.txt";
        Files.createDirectories(new File(dir, RESOURCE_DIR).toPath());
        Files.createDirectories(new File(siblingDir, RESOURCE_DIR).toPath());
        Files.writeString(new File(dir, innerPath).toPath(), "inner");
        Files.writeString(new File(siblingDir, siblingPath).toPath(), "sibling");
        try (var classLoader = new URLClassLoader(new URL[] { dir.toURI().toURL() }, /* parent = */ null)) {
            final var ucp = ReflectionUtils.getFieldVal(false, classLoader, "ucp");
            assumeTrue(ucp != null && ReflectionUtils.getFieldVal(false, ucp, "loaders") != null,
                    "Could not reach the URLClassPath internals");
            // Looking up a resource opens the directory
            assertThat(classLoader.getResource(innerPath)).isNotNull();
            assertThat(classLoader.getResource(siblingPath)).isNull();
            try (var scanResult = new ClassGraph().enableClassLoaders(classLoader).acceptPaths(RESOURCE_DIR)
                    .scan()) {
                assertThat(scanResult.getResourcesWithPath(innerPath)).isNotEmpty();
                assertThat(scanResult.getResourcesWithPath(siblingPath)).isEmpty();
            }
        }
    }

    /**
     * A jar that only the JAR index ({@code META-INF/INDEX.LIST}) of a classpath jar lists is found and scanned,
     * both before and after the classloader opens it. A JDK that reads JAR indexes records such a jar only in the
     * {@code lmap} field of the {@code URLClassPath}, never in its {@code loaders} field. JDK 18 stopped reading
     * JAR indexes unless the system property {@code jdk.net.URLClassPath.enableJarIndex} is set, and JDK 21 removed
     * them, so on JDK 18 and later this test is normally skipped.
     *
     * @param tempDir
     *            the temp dir.
     * @throws Exception
     *             if the test jars could not be created.
     */
    @Test
    void jarListedOnlyInAJarIndexIsScanned(@TempDir final File tempDir) throws Exception {
        final var indexedPath = RESOURCE_DIR + "/indexed/indexed.txt";
        final var indexedJarFile = new File(tempDir, "indexed.jar");
        try (var outputStream = Files.newOutputStream(indexedJarFile.toPath());
                var zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry(indexedPath));
            zipOutputStream.write("indexed".getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        final var mainJarFile = new File(tempDir, "main.jar");
        try (var outputStream = Files.newOutputStream(mainJarFile.toPath());
                var zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry("META-INF/INDEX.LIST"));
            zipOutputStream.write(("JarIndex-Version: 1.0\n\nmain.jar\n" + RESOURCE_DIR + "\n\nindexed.jar\n"
                    + RESOURCE_DIR + "/indexed\n").getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry(RESOURCE_PATH));
            zipOutputStream.write("main".getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        try (var classLoader = new URLClassLoader(new URL[] { mainJarFile.toURI().toURL() }, /* parent = */ null)) {
            // Looking up a resource opens main.jar and reads its index, which lists indexed.jar in lmap only
            assertThat(classLoader.getResource(RESOURCE_PATH)).isNotNull();
            final var ucp = ReflectionUtils.getFieldVal(false, classLoader, "ucp");
            final var lmap = ucp == null ? null : ReflectionUtils.getFieldVal(false, ucp, "lmap");
            assumeTrue(
                    lmap instanceof Map && ((Map<?, ?>) lmap).keySet().stream()
                            .anyMatch(key -> String.valueOf(key).endsWith("/indexed.jar")),
                    "Could not reach the URLClassPath internals, or this JDK does not read JAR indexes");
            try (var scanResult = new ClassGraph().enableClassLoaders(classLoader).acceptPaths(RESOURCE_DIR)
                    .scan()) {
                assertThat(scanResult.getResourcesWithPath(indexedPath)).isNotEmpty();
            }
            // Looking up a resource in the indexed package opens indexed.jar, which is still not in loaders
            assertThat(classLoader.getResource(indexedPath)).isNotNull();
            try (var scanResult = new ClassGraph().enableClassLoaders(classLoader).acceptPaths(RESOURCE_DIR)
                    .scan()) {
                assertThat(scanResult.getResourcesWithPath(indexedPath)).isNotEmpty();
            }
        }
    }
}
