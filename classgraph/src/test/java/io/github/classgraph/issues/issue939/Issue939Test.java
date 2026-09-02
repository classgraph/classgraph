package io.github.classgraph.issues.issue939;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * A memory-mapped file has to be unmapped when the scan that mapped it is closed, since Windows refuses to delete,
 * rename or overwrite a file while it is mapped. From JDK 22 the file is mapped in a
 * {@code java.lang.foreign.Arena} and unmapped by closing it; below JDK 22 there are no arenas, and the only way to
 * unmap a file is {@code Unsafe::invokeCleaner}. Either way the scan closes every buffer it handed out first, so
 * that nothing is left holding a view of the mapping when it goes. The mechanics of both are tested by
 * {@code OffHeapMemoryTest} and {@code PathSliceTest}, in the vfs library; this checks the behavior a caller sees.
 *
 * <p>
 * Files are memory-mapped on Windows only, so these tests run there only.
 */
public class Issue939Test {
    /** The content written into the stored zip entry of the jarfile that the tests below scan. */
    private static final byte[] STORED_ENTRY_CONTENT = "the content of a stored zip entry"
            .getBytes(StandardCharsets.UTF_8);

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
     * Scanning a jar with memory mapping works on all JDK versions, and the mapping is released again when the
     * {@link io.github.classgraph.ScanResult} is closed.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void scanJarWithMemoryMapping() {
        final var classGraph = new ClassGraph().enableClassInfo()
                .acceptPackages("org.springframework.boot.loader.util")
                .enableClasspathEntries(Issue939Test.class.getClassLoader().getResource("issue209.jar"));
        try (var scanResult = classGraph.scan()) {
            assertThat(scanResult.getAllClasses().getNames())
                    .contains("org.springframework.boot.loader.util.SystemPropertyUtils");
        }
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
    @EnabledOnOs(OS.WINDOWS)
    public void aMappedJarCanBeDeletedOnceTheScanIsClosed(@TempDir final Path tempDir) throws IOException {
        final var jarPath = tempDir.resolve("deleted-after-the-scan.jar");
        writeJarWithAStoredEntry(jarPath);

        final var classGraph = new ClassGraph().acceptPathsNonRecursive("").enableClasspathEntries(jarPath);
        try (var scanResult = classGraph.scan()) {
            final var resources = scanResult.getResourcesWithPath("stored.txt");
            assertThat(resources).hasSize(1);
            try (var buffer = resources.get(0).read()) {
                final var byteBuffer = buffer.getByteBuffer();
                assertThat(byteBuffer).isNotNull();
                assertThat(byteBuffer.get(0)).isEqualTo(STORED_ENTRY_CONTENT[0]);
            }
        }

        Files.delete(jarPath);
        assertThat(jarPath).doesNotExist();
    }
}
