package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsSpec;

/**
 * A scan reads with a copy of the {@link VfsSpec} of the {@link ClassGraph} that started it, so that everything in
 * the scan is read with the same settings, and nothing the scan hands out can change the {@link ClassGraph}.
 */
public class VfsSpecIsolationTest {
    /**
     * Write a jarfile that holds a text file, and a jarfile stored (not deflated) under {@code "lib/inner.jar"}
     * that holds another text file.
     *
     * @param outerJar
     *            the path to write the jarfile to.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    private static void writeJarContainingJar(final Path outerJar) throws IOException {
        final var innerJarBytes = new ByteArrayOutputStream();
        try (var zipOut = new ZipOutputStream(innerJarBytes)) {
            zipOut.putNextEntry(new ZipEntry("pkg/inner.txt"));
            zipOut.write("inner".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        final var innerJar = innerJarBytes.toByteArray();
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(outerJar))) {
            final var storedEntry = new ZipEntry("lib/inner.jar");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(innerJar.length);
            storedEntry.setCompressedSize(innerJar.length);
            final var crc = new CRC32();
            crc.update(innerJar);
            storedEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(storedEntry);
            zipOut.write(innerJar);
            zipOut.closeEntry();
            zipOut.putNextEntry(new ZipEntry("outer.txt"));
            zipOut.write("outer".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
    }

    /**
     * The {@link VfsSpec} that a scan's resources lead back to cannot be changed, so code that is handed a
     * {@link Resource} cannot change how the {@link ClassGraph} that found it reads its next scan. The
     * {@link ClassGraph}'s own settings can still be changed between scans.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile to.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    @Test
    public void aScanResultCannotChangeTheSettingsOfTheClassGraphThatFoundIt(@TempDir final Path tempDir)
            throws IOException {
        final var outerJar = tempDir.resolve("outer.jar");
        writeJarContainingJar(outerJar);
        final var classGraph = new ClassGraph().enableClasspathEntries(outerJar.toString(),
                outerJar + "!/lib/inner.jar");

        try (var scanResult = classGraph.scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder("lib/inner.jar",
                    "outer.txt", "pkg/inner.txt");
            final var vfsSpec = scanResult.getResourcesWithPath("outer.txt").get(0).getVfsEntry().getRoot().getVfs()
                    .getVfsSpec();
            assertThatThrownBy(vfsSpec::disableNestedJars).isInstanceOf(IllegalStateException.class);
        }
        try (var scanResult = classGraph.scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder("lib/inner.jar",
                    "outer.txt", "pkg/inner.txt");
        }
        try (var scanResult = classGraph.disableNestedJarScanning().scan()) {
            assertThat(scanResult.getAllResources().getPaths()).containsExactlyInAnyOrder("lib/inner.jar",
                    "outer.txt");
        }
    }

    /**
     * A classpath element filters out versioned entries according to the settings of the {@link Vfs} it reads them
     * through, so that an entry is never filtered by one setting after being read with another.
     */
    @Test
    public void aClasspathElementFiltersWithTheSettingsOfItsVfs() {
        final var workUnit = new ClasspathEntryWorkUnit(Path.of("."), /* classLoader = */ null,
                /* parentClasspathElement = */ null, /* classpathElementIdx = */ 0, /* packageRootPrefix = */ "",
                ClassLoaderHandler.NO_PACKAGE_ROOT_PREFIXES, ClassLoaderHandler.NO_LIB_DIR_PREFIXES);
        final var versionedPath = "META-INF/versions/9/com/xyz/Widget.class";
        // The ScanSpec is left at its defaults, which honor multi-release versioning, unlike the Vfs
        try (var vfs = new Vfs(new VfsSpec().disableMultiReleaseVersions())) {
            assertThat(new ClasspathElementDir(workUnit, vfs, new ScanSpec()).isIgnoredVersionedPath(versionedPath))
                    .isFalse();
        }
        try (var vfs = new Vfs()) {
            assertThat(new ClasspathElementDir(workUnit, vfs, new ScanSpec()).isIgnoredVersionedPath(versionedPath))
                    .isTrue();
        }
    }
}
