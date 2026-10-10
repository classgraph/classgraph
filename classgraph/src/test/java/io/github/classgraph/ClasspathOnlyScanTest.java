package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ClassGraph#getClasspathFiles()}, {@link ClassGraph#getClasspathURIs()},
 * {@link ClassGraph#getClasspathURLs()} and {@link ClassGraph#getModuleReferences()} only find the classpath
 * elements and modules, so they do not walk the files inside them, unless a classpath element filter that looks for
 * a resource path inside each element is set. They list the same elements that a full scan does.
 */
public class ClasspathOnlyScanTest {
    /**
     * Write a zipfile holding the given entries, each with its name as its content.
     *
     * @param out
     *            the stream to write the zipfile to.
     * @param entryNames
     *            the names of the entries.
     * @throws IOException
     *             if the zipfile could not be written.
     */
    private static void writeZip(final OutputStream out, final String... entryNames) throws IOException {
        try (var zipOut = new ZipOutputStream(out)) {
            for (final String entryName : entryNames) {
                zipOut.putNextEntry(new ZipEntry(entryName));
                zipOut.write(entryName.getBytes());
                zipOut.closeEntry();
            }
        }
    }

    /**
     * Write a jarfile holding the given entries.
     *
     * @param jarFile
     *            the jarfile to write.
     * @param entryNames
     *            the names of the entries.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJar(final Path jarFile, final String... entryNames) throws IOException {
        try (var out = new FileOutputStream(jarFile.toFile())) {
            writeZip(out, entryNames);
        }
    }

    /**
     * Write a jarfile holding one resource and a nested jarfile {@code lib/inner.jar}, which holds one resource of
     * its own.
     *
     * @param jarFile
     *            the jarfile to write.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJarWithNestedJar(final Path jarFile) throws IOException {
        final var innerJar = new ByteArrayOutputStream();
        writeZip(innerJar, "inner.txt");
        try (var zipOut = new ZipOutputStream(new FileOutputStream(jarFile.toFile()))) {
            zipOut.putNextEntry(new ZipEntry("outer.txt"));
            zipOut.write("outer.txt".getBytes());
            zipOut.closeEntry();
            zipOut.putNextEntry(new ZipEntry("lib/inner.jar"));
            zipOut.write(innerJar.toByteArray());
            zipOut.closeEntry();
        }
    }

    /**
     * The classpath-only lookup opens each classpath element, but does not walk the files inside it, so the
     * {@link ScanResult} it builds holds no resources.
     *
     * @param tempDir
     *            a temporary directory to write the classpath elements into.
     * @throws IOException
     *             if the classpath elements could not be written.
     */
    @Test
    public void classpathOnlyLookupDoesNotWalkTheClasspathElements(@TempDir final Path tempDir) throws IOException {
        final var dir = Files.createDirectories(tempDir.resolve("classes"));
        Files.writeString(dir.resolve("dir.txt"), "dir.txt");
        final var jar = tempDir.resolve("lib.jar");
        writeJar(jar, "jar.txt");

        try (var executorService = new AutoCloseableExecutorService(2);
                var scanResult = new ClassGraph().enableClasspathEntries(dir, jar)
                        .getClasspathScanResult(executorService)) {
            assertThat(scanResult.getClasspathFiles()).containsExactly(dir.toRealPath().toFile(),
                    jar.toRealPath().toFile());
            assertThat(scanResult.getAllResources()).isEmpty();
        }
    }

    /**
     * The classpath-only getters list the same directories, jarfiles and nested jarfiles, in the same order, as a
     * full scan of the same classpath does.
     *
     * @param tempDir
     *            a temporary directory to write the classpath elements into.
     * @throws IOException
     *             if the classpath elements could not be written.
     */
    @Test
    public void classpathOnlyGettersListWhatAScanFinds(@TempDir final Path tempDir) throws IOException {
        final var dir = Files.createDirectories(tempDir.resolve("classes"));
        Files.writeString(dir.resolve("dir.txt"), "dir.txt");
        final var jar = tempDir.resolve("lib.jar");
        writeJar(jar, "jar.txt");
        final var outerJar = tempDir.resolve("outer.jar");
        writeJarWithNestedJar(outerJar);
        final var nestedJar = outerJar + "!/lib/inner.jar";

        final var classGraph = new ClassGraph().enableClasspathEntries(dir, jar, outerJar, nestedJar);
        try (var scanResult = classGraph.scan()) {
            // Check that the scan found every element, so that the comparisons below are not of two empty lists
            assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder("dir.txt", "jar.txt",
                    "outer.txt", "lib/inner.jar", "inner.txt");
            assertThat(scanResult.getClasspathURIs()).hasSize(4);

            assertThat(classGraph.getClasspathFiles()).containsExactlyElementsOf(scanResult.getClasspathFiles());
            assertThat(classGraph.getClasspath()).isEqualTo(scanResult.getClasspath());
            assertThat(classGraph.getClasspathURIs()).containsExactlyElementsOf(scanResult.getClasspathURIs());
            assertThat(classGraph.getClasspathURLs()).containsExactlyElementsOf(scanResult.getClasspathURLs());
        }
    }

    /**
     * {@link ClassGraph#getModuleReferences()} lists the same modules as a full scan does.
     */
    @Test
    public void getModuleReferencesListsWhatAScanFinds() {
        final var classGraph = new ClassGraph().enableSystemModules().acceptModules("java.logging");
        try (var scanResult = classGraph.scan()) {
            assertThat(scanResult.getModuleReferences()).hasSize(1);
            assertThat(classGraph.getModuleReferences())
                    .containsExactlyElementsOf(scanResult.getModuleReferences());
        }
    }

    /**
     * A classpath element filter that looks for a resource path inside each element still works in the
     * classpath-only lookup, since the files inside each element are walked when such a filter is set.
     *
     * @param tempDir
     *            a temporary directory to write the classpath elements into.
     * @throws IOException
     *             if the classpath elements could not be written.
     */
    @Test
    public void resourcePathFilterStillFiltersTheClasspath(@TempDir final Path tempDir) throws IOException {
        final var dir = Files.createDirectories(tempDir.resolve("classes"));
        Files.writeString(dir.resolve("dir.txt"), "dir.txt");
        final var jar = tempDir.resolve("lib.jar");
        writeJar(jar, "marker.txt");

        assertThat(new ClassGraph().enableClasspathEntries(dir, jar)
                .acceptClasspathElementsContainingResourcePath("marker.txt").getClasspathFiles())
                .containsExactly(jar.toRealPath().toFile());
        assertThat(new ClassGraph().enableClasspathEntries(dir, jar)
                .rejectClasspathElementsContainingResourcePath("marker.txt").getClasspathFiles())
                .containsExactly(dir.toRealPath().toFile());
    }
}
