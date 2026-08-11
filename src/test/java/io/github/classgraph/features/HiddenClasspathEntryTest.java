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
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;

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
        try (var outputStream = Files.newOutputStream(agentJarFile.toPath());
                var jarOutputStream = new JarOutputStream(outputStream, manifest)) {
            // No entries -- the manifest is the whole jar
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
        final var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output;
        try (var inputStream = process.getInputStream()) {
            output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(process.waitFor()).as("Child JVM output:%n%s", output).isZero();
        return output;
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
        final var reflectionUtils = new ReflectionUtils();
        final var ucp = reflectionUtils.getFieldVal(false, classLoader, "ucp");
        if (ucp == null) {
            return false;
        }
        final var unopenedUrls = reflectionUtils.getFieldVal(false, ucp, "unopenedUrls");
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
            try (var scanResult = new ClassGraph().overrideClassLoaders(classLoader).acceptPaths(RESOURCE_DIR)
                    .scan()) {
                assertThat(scanResult.getResourcesWithPath(RESOURCE_PATH)).isNotEmpty();
            }
        }
    }
}
