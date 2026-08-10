package nonapi.io.github.classgraph.fileslice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Tests that a {@link PathSlice} for a whole file is memory-mapped when {@code enableMemoryMapping()} was called,
 * and that a mapped slice reads back the same content as an unmapped one.
 */
public class PathSliceTest {
    /** The content of the test file, long enough that sub-slices of it are worth reading. */
    private static final byte[] CONTENT = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);

    /**
     * Create the resources owned by a scan.
     *
     * @param enableMemoryMapping
     *            the value of {@code ScanSpec#enableMemoryMapping}
     * @return the scan resources
     */
    private static ScanResources scanResources(final boolean enableMemoryMapping) {
        final var scanSpec = new ScanSpec();
        scanSpec.enableMemoryMapping = enableMemoryMapping;
        return new ScanResources(scanSpec, new ReflectionUtils());
    }

    /**
     * Write the test file.
     *
     * @param tempDir
     *            the temporary directory to write the file to
     * @return the path of the test file
     * @throws IOException
     *             if the file could not be written
     */
    private static Path writeTestFile(final Path tempDir) throws IOException {
        final var file = tempDir.resolve("content.bin");
        Files.write(file, CONTENT);
        return file;
    }

    /** A whole-file slice is memory-mapped if memory mapping is enabled, and reads back the file content. */
    @Test
    public void aWholeFileSliceIsMemoryMappedIfMappingIsEnabled(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var scanResources = scanResources(/* enableMemoryMapping = */ true);
        final var slice = new PathSlice(file, scanResources, /* log = */ null);
        try {
            // A mapped slice is read from a direct ByteBuffer, an unmapped slice from a heap ByteBuffer
            assertThat(slice.read().isDirect()).isTrue();
            assertThat(slice.load()).isEqualTo(CONTENT);
            assertThat(slice.loadAsString()).isEqualTo(new String(CONTENT, StandardCharsets.UTF_8));
            try (var inputStream = slice.open()) {
                assertThat(inputStream.readAllBytes()).isEqualTo(CONTENT);
            }
        } finally {
            slice.close();
        }
    }

    /** A sub-slice of a memory-mapped slice reads back the corresponding range of the file content. */
    @Test
    public void aSubSliceOfAMappedSliceReadsTheRightRange(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var scanResources = scanResources(/* enableMemoryMapping = */ true);
        final var slice = new PathSlice(file, scanResources, /* log = */ null);
        try {
            final var subSlice = slice.slice(10, 5, /* isDeflatedZipEntry = */ false,
                    /* inflatedLengthHint = */ 0L);
            assertThat(subSlice.loadAsString()).isEqualTo("abcde");
            final var reader = subSlice.randomAccessReader();
            assertThat(reader.readString(0, 5)).isEqualTo("abcde");
            try (var inputStream = subSlice.open()) {
                assertThat(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abcde");
            }
        } finally {
            slice.close();
        }
    }

    /** A whole-file slice is not memory-mapped if memory mapping was not enabled. */
    @Test
    public void aWholeFileSliceIsNotMappedIfMappingIsDisabled(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var scanResources = scanResources(/* enableMemoryMapping = */ false);
        final var slice = new PathSlice(file, scanResources, /* log = */ null);
        try {
            assertThat(slice.read().isDirect()).isFalse();
            assertThat(slice.load()).isEqualTo(CONTENT);
        } finally {
            slice.close();
        }
    }

    /**
     * A slice of a single resource file is not memory-mapped, even if memory mapping is enabled, since mapping and
     * unmapping a file that is read once and then closed costs more than reading it.
     */
    @Test
    public void aResourceSliceIsNotMemoryMapped(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var scanResources = scanResources(/* enableMemoryMapping = */ true);
        final var slice = new PathSlice(file, scanResources, /* checkAccess = */ false,
                /* memoryMapIfEnabled = */ false, /* log = */ null);
        try {
            assertThat(slice.read().isDirect()).isFalse();
            assertThat(slice.load()).isEqualTo(CONTENT);
        } finally {
            slice.close();
        }
    }
}
