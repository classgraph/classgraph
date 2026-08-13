package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When memory mapping is enabled, the mapping covers the whole jarfile, and an entry starts partway into it. The
 * {@link ByteBuffer} returned by {@link Resource#read()} has to cover the entry and nothing else.
 */
public class MemoryMappedResourceBufferTest {
    /** The contents of the entry written here. */
    private static final byte[] CONTENTS = "File contents".getBytes(StandardCharsets.UTF_8);

    /**
     * The buffer returned for a resource of a memory-mapped jarfile starts at position zero, has a capacity equal
     * to the length of the resource, and cannot be widened to reach the rest of the jarfile.
     *
     * @param tempDir
     *            a temporary directory to write the test jarfile to
     * @throws IOException
     *             if the test jarfile could not be written or read
     */
    @Test
    public void theBufferOfAResourceCoversTheResourceAndNothingElse(@TempDir final Path tempDir)
            throws IOException {
        final Path jarPath = tempDir.resolve("mapped-jar-entry.jar");
        try (OutputStream fileOut = Files.newOutputStream(jarPath);
                ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            // A stored entry is read in place from the mapping of the jarfile; a deflated entry would instead be
            // inflated into a buffer of its own, which would not exercise the mapping
            final ZipEntry storedEntry = new ZipEntry("stored.txt");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(CONTENTS.length);
            storedEntry.setCompressedSize(CONTENTS.length);
            final CRC32 crc = new CRC32();
            crc.update(CONTENTS);
            storedEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(storedEntry);
            zipOut.write(CONTENTS);
            zipOut.closeEntry();
        }

        try (ScanResult scanResult = new ClassGraph().acceptPathsNonRecursive("").enableMemoryMapping()
                .overrideClasspath(jarPath.toString()).scan()) {
            final ResourceList resources = scanResult.getResourcesWithPath("stored.txt");
            assertThat(resources).hasSize(1);
            final Resource resource = resources.get(0);
            try {
                final ByteBuffer byteBuffer = resource.read();
                assertThat(byteBuffer.position()).isZero();
                assertThat(byteBuffer.capacity()).isEqualTo(CONTENTS.length);
                assertThat(byteBuffer.remaining()).isEqualTo(CONTENTS.length);
                // Absolute reads are relative to the start of the resource, not to the start of the jarfile
                assertThat(byteBuffer.get(0)).isEqualTo(CONTENTS[0]);
                // Clearing the buffer must not widen it to the rest of the jarfile
                byteBuffer.clear();
                assertThat(byteBuffer.remaining()).isEqualTo(CONTENTS.length);
            } finally {
                resource.close();
            }
        }
    }
}
