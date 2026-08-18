package io.github.classgraph.issues.issue939;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.VfsSpecAccess;
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.vfs.CloseableByteBuffer;

/**
 * A memory-mapped file has to be unmapped when the scan that mapped it is closed, since Windows refuses to delete,
 * rename or overwrite a file while it is mapped. From JDK 22 the file is mapped in a
 * {@code java.lang.foreign.Arena} and unmapped by closing it. Below JDK 22 there are no arenas, and the only way to
 * unmap a file is {@code Unsafe::invokeCleaner}, which frees the address range whether or not anything is still
 * reading it -- so a file is unmapped there once the scan has closed and the caller has closed every buffer it was
 * handed. The mechanics of both are tested by {@code OffHeapMemoryTest} and {@code PathSliceTest}, in the vfs
 * library; this checks the behaviour a caller sees, on every supported JDK version.
 */
public class Issue939Test {
    /**
     * Scanning a jar with memory mapping works on all JDK versions, and the mapping is released again when the
     * {@link io.github.classgraph.ScanResult} is closed.
     */
    @Test
    public void scanJarWithMemoryMapping() {
        final var classGraph = new ClassGraph().enableClassInfo()
                .acceptPackages("org.springframework.boot.loader.util")
                .overrideClasspath(Issue939Test.class.getClassLoader().getResource("issue209.jar"));
        // Files are memory-mapped on Windows only, so the platform's choice is overridden here to exercise the
        // mapping path whatever platform this test runs on
        VfsSpecAccess.vfsSpecOf(classGraph).setMemoryMappingFiles(true);
        try (var scanResult = classGraph.scan()) {
            assertThat(scanResult.getAllClasses().getNames())
                    .contains("org.springframework.boot.loader.util.SystemPropertyUtils");
        }
    }

    /**
     * A buffer that the caller has not closed yet keeps the file it is a view of mapped, even after the scan that
     * mapped the file has been closed. Below JDK 22 the file is unmapped by freeing its address range, so a scan
     * that unmapped a file the caller could still read would not merely hand back stale content -- the read would
     * take a SIGSEGV that kills the JVM. (From JDK 22 the arena is closed with the scan and such a read throws
     * {@link IllegalStateException}, which is what {@code CloseableByteBuffer} documents, so this only covers the
     * JDK versions where the memory really does go away.)
     *
     * @param tempDir
     *            a temporary directory to write the jarfile to be scanned into
     * @throws IOException
     *             if the jarfile could not be written or read
     */
    // #939
    @Test
    public void anOpenBufferKeepsTheFileMappedAfterTheScanIsClosed(@TempDir final Path tempDir) throws IOException {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION < 22, "from JDK 22 the arena is closed with the scan");
        final var content = "the content of a stored zip entry".getBytes(StandardCharsets.UTF_8);
        final var jarPath = tempDir.resolve("mapped-jar-entry.jar");
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            // A stored entry is read in place from the mapping of the jarfile; a deflated entry would instead be
            // inflated into a buffer of its own, which would not exercise the mapping
            final var storedEntry = new ZipEntry("stored.txt");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(content.length);
            storedEntry.setCompressedSize(content.length);
            final var crc = new CRC32();
            crc.update(content);
            storedEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(storedEntry);
            zipOut.write(content);
            zipOut.closeEntry();
        }

        final var classGraph = new ClassGraph().acceptPathsNonRecursive("").overrideClasspath(jarPath);
        // Files are memory-mapped on Windows only, so the platform's choice is overridden here to exercise the
        // mapping path whatever platform this test runs on
        VfsSpecAccess.vfsSpecOf(classGraph).setMemoryMappingFiles(true);
        final CloseableByteBuffer buffer;
        try (var scanResult = classGraph.scan()) {
            final var resources = scanResult.getResourcesWithPath("stored.txt");
            assertThat(resources).hasSize(1);
            buffer = resources.get(0).read();
            assertThat(buffer.getByteBuffer()).isNotNull();
        }

        // The scan is closed, but this buffer is not, so the jarfile it is a view of is still mapped
        final var byteBuffer = buffer.getByteBuffer();
        assertThat(byteBuffer).isNotNull();
        assertThat(byteBuffer.get(0)).isEqualTo(content[0]);
        buffer.close();
    }
}
