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
    /** The content written into the stored zip entry of the jarfile that the tests below scan. */
    private static final byte[] STORED_ENTRY_CONTENT = "the content of a stored zip entry"
            .getBytes(StandardCharsets.UTF_8);

    /** The file through which Linux says which files this process has memory-mapped. */
    private static final Path PROC_SELF_MAPS = Path.of("/proc/self/maps");

    /**
     * Write a jarfile holding a single stored entry named {@code stored.txt}. A stored entry is read in place from
     * the mapping of the jarfile; a deflated entry would instead be inflated into a buffer of its own, which would
     * not exercise the mapping.
     *
     * @param jarPath
     *            where to write the jarfile
     * @throws IOException
     *             if the jarfile could not be written
     */
    private static void writeJarWithAStoredEntry(final Path jarPath) throws IOException {
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            final var storedEntry = new ZipEntry("stored.txt");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(STORED_ENTRY_CONTENT.length);
            storedEntry.setCompressedSize(STORED_ENTRY_CONTENT.length);
            final var crc = new CRC32();
            crc.update(STORED_ENTRY_CONTENT);
            storedEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(storedEntry);
            zipOut.write(STORED_ENTRY_CONTENT);
            zipOut.closeEntry();
        }
    }

    /**
     * Whether a file is currently memory-mapped by this JVM. Only Linux can tell, through {@code /proc/self/maps},
     * so a caller has to check that that file is readable before believing the answer.
     *
     * @param file
     *            the file to look for
     * @return true if the file is memory-mapped
     * @throws IOException
     *             if {@code /proc/self/maps} could not be read
     */
    private static boolean isMemoryMapped(final Path file) throws IOException {
        final var fileName = file.getFileName().toString();
        return Files.readAllLines(PROC_SELF_MAPS).stream().anyMatch(line -> line.endsWith(fileName));
    }

    /**
     * Scanning a jar with memory mapping works on all JDK versions, and the mapping is released again when the
     * {@link io.github.classgraph.ScanResult} is closed.
     */
    @Test
    public void scanJarWithMemoryMapping() {
        final var classGraph = new ClassGraph().enableClassInfo()
                .acceptPackages("org.springframework.boot.loader.util")
                .enableClasspathEntries(Issue939Test.class.getClassLoader().getResource("issue209.jar"));
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
        final var jarPath = tempDir.resolve("mapped-jar-entry.jar");
        writeJarWithAStoredEntry(jarPath);

        final var classGraph = new ClassGraph().acceptPathsNonRecursive("").enableClasspathEntries(jarPath);
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
        assertThat(byteBuffer.get(0)).isEqualTo(STORED_ENTRY_CONTENT[0]);
        buffer.close();
    }

    /**
     * A jarfile that a scan memory-mapped can be deleted as soon as the {@link io.github.classgraph.ScanResult} is
     * closed, with no collection and no retry in between. Windows refuses to delete a file while it is mapped, so
     * this is the property that makes memory mapping usable there at all. That a slice unmaps its file when it
     * closes is tested in the vfs library; what this adds is that closing a scan result reaches that.
     *
     * @param tempDir
     *            a temporary directory to write the jarfile to be scanned into
     * @throws IOException
     *             if the jarfile could not be written, read or deleted
     */
    // #939
    @Test
    public void aMappedJarCanBeDeletedOnceTheScanIsClosed(@TempDir final Path tempDir) throws IOException {
        final var jarPath = tempDir.resolve("deleted-after-the-scan.jar");
        writeJarWithAStoredEntry(jarPath);

        final var classGraph = new ClassGraph().acceptPathsNonRecursive("").enableClasspathEntries(jarPath);
        // Files are memory-mapped on Windows only, so the platform's choice is overridden here to exercise the
        // mapping path whatever platform this test runs on
        VfsSpecAccess.vfsSpecOf(classGraph).setMemoryMappingFiles(true);
        // Only Linux can be asked which files are mapped, so the two assertions that the jarfile was mapped, and
        // then was not, are skipped elsewhere. The delete runs everywhere, and it is the delete that fails if a
        // scan on Windows leaves the files it mapped in place.
        final var canTellWhatIsMapped = Files.isReadable(PROC_SELF_MAPS);
        try (var scanResult = classGraph.scan()) {
            final var resources = scanResult.getResourcesWithPath("stored.txt");
            assertThat(resources).hasSize(1);
            try (var buffer = resources.get(0).read()) {
                final var byteBuffer = buffer.getByteBuffer();
                assertThat(byteBuffer).isNotNull();
                assertThat(byteBuffer.get(0)).isEqualTo(STORED_ENTRY_CONTENT[0]);
            }
            if (canTellWhatIsMapped) {
                assertThat(isMemoryMapped(jarPath)).as("mapped while the scan is open").isTrue();
            }
        }

        if (canTellWhatIsMapped) {
            assertThat(isMemoryMapped(jarPath)).as("still mapped after the scan closed").isFalse();
        }
        Files.delete(jarPath);
        assertThat(jarPath).doesNotExist();
    }
}
