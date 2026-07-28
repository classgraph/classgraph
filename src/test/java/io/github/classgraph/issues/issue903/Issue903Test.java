package io.github.classgraph.issues.issue903;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * '!' is a legal character in a file or directory name on every platform ClassGraph supports, but it was
 * unconditionally treated as a nested jar separator, so any classpath element with a '!' in its path was mangled
 * into a nested jar path and could not be opened (#903).
 *
 * <p>
 * The {@link java.net.JarURLConnection} spec defines the separator as {@code "!/"} and provides no escape for a
 * literal '!' (a backslash is not an escape -- the JDK passes {@code "\!"} through verbatim), so the separator
 * cannot be identified by syntax alone. It is instead identified by testing whether the path before the '!' names
 * an existing jarfile.
 */
class Issue903Test {
    private static final String RESOURCE_PATH = "issue903/resource.txt";

    /** Write RESOURCE_PATH into a directory. */
    private static void writeResourceDir(final File dir) throws IOException {
        final File resourceFile = new File(dir, RESOURCE_PATH);
        assertThat(resourceFile.getParentFile().mkdirs()).isTrue();
        Files.write(resourceFile.toPath(), "issue903".getBytes(StandardCharsets.UTF_8));
    }

    /** Write a jarfile containing RESOURCE_PATH. */
    private static void writeResourceJar(final File jarFile) throws IOException {
        try (OutputStream out = new FileOutputStream(jarFile); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(RESOURCE_PATH));
            zip.write("issue903".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /** Scan a classpath element, and return the accepted resource paths. */
    private static java.util.List<String> scanPaths(final File classpathElt) throws IOException {
        // ClassGraph canonicalizes classpath element paths, and the temporary directory is reached through a
        // symlink on macOS ("/var" -> "/private/var") and through an 8.3 short name on Windows
        // ("C:\Users\RUNNER~1" -> "C:\Users\runneradmin"), so compare canonical paths
        final File classpathEltCanonical = classpathElt.getCanonicalFile();
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(classpathElt.getPath())
                .acceptPaths("issue903").scan()) {
            assertThat(scanResult.getClasspathFiles()).containsExactly(classpathEltCanonical);
            return scanResult.getAllResources().getPaths();
        }
    }

    /** A directory with a '!' in the middle of its name. */
    @Test
    void directoryWithBangInName(@TempDir final Path tempDir) throws IOException {
        final File dir = new File(tempDir.toFile(), "a!b");
        assertThat(dir.mkdir()).isTrue();
        writeResourceDir(dir);
        assertThat(scanPaths(dir)).containsExactly(RESOURCE_PATH);
    }

    /** A directory whose name ends with '!' -- this was truncated, since "jar:file:x.jar!/" means "all of x.jar". */
    @Test
    void directoryWithTrailingBangInName(@TempDir final Path tempDir) throws IOException {
        final File dir = new File(tempDir.toFile(), "a!");
        assertThat(dir.mkdir()).isTrue();
        writeResourceDir(dir);
        assertThat(scanPaths(dir)).containsExactly(RESOURCE_PATH);
    }

    /** A jarfile inside a directory with a '!' in its name. */
    @Test
    void jarInDirectoryWithBangInName(@TempDir final Path tempDir) throws IOException {
        final File dir = new File(tempDir.toFile(), "a!b");
        assertThat(dir.mkdir()).isTrue();
        final File jarFile = new File(dir, "test.jar");
        writeResourceJar(jarFile);
        assertThat(scanPaths(jarFile)).containsExactly(RESOURCE_PATH);
    }

    /** A jarfile with a '!' in its own name. */
    @Test
    void jarWithBangInName(@TempDir final Path tempDir) throws IOException {
        final File jarFile = new File(tempDir.toFile(), "a!b.jar");
        writeResourceJar(jarFile);
        assertThat(scanPaths(jarFile)).containsExactly(RESOURCE_PATH);
    }

    /** A '!' that really is a nested jar separator must still be treated as one. */
    @Test
    void nestedJarSeparatorStillWorks(@TempDir final Path tempDir) throws IOException {
        // Build outer.jar containing inner.jar, which in turn contains RESOURCE_PATH
        final File innerJar = new File(tempDir.toFile(), "inner.jar");
        writeResourceJar(innerJar);
        final byte[] innerJarBytes = Files.readAllBytes(innerJar.toPath());
        final File outerJar = new File(tempDir.toFile(), "outer.jar");
        try (OutputStream out = new FileOutputStream(outerJar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("lib/inner.jar"));
            zip.write(innerJarBytes);
            zip.closeEntry();
        }
        try (ScanResult scanResult = new ClassGraph()
                .overrideClasspath(outerJar.getPath() + "!/lib/inner.jar").acceptPaths("issue903").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly(RESOURCE_PATH);
        }
    }

    /**
     * The {@code "jar:jar:file:...!/...!/"} form emitted by servlet containers for a jar nested within a WAR file
     * ends with the nested jar separator "!/", marking the whole of the inner jar. The trailing '!' is a separator,
     * not part of a directory name, even though it is not the outermost separator in the path.
     */
    @Test
    void trailingNestedJarSeparatorOfInnerJarIsStripped(@TempDir final Path tempDir) throws IOException {
        final File innerJar = new File(tempDir.toFile(), "inner.jar");
        writeResourceJar(innerJar);
        final byte[] innerJarBytes = Files.readAllBytes(innerJar.toPath());
        final File outerWar = new File(tempDir.toFile(), "outer.war");
        try (OutputStream out = new FileOutputStream(outerWar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("WEB-INF/lib/inner.jar"));
            zip.write(innerJarBytes);
            zip.closeEntry();
        }
        try (ScanResult scanResult = new ClassGraph()
                .overrideClasspath("jar:jar:file:" + outerWar.getPath() + "!/WEB-INF/lib/inner.jar!/")
                .acceptPaths("issue903").scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactly(RESOURCE_PATH);
        }
    }
}
