package io.github.classgraph.vfs.internal.slice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.internal.VfsSession;
import io.github.classgraph.vfs.internal.VfsSpec;

/**
 * Tests that a {@link PathSlice} for a whole file is memory-mapped when the vfs scan spec says files are
 * memory-mapped, and that a mapped slice reads back the same content as an unmapped one.
 */
public class PathSliceTest {
    /** The content of the test file, long enough that sub-slices of it are worth reading. */
    private static final byte[] CONTENT = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);

    /**
     * Create the resources owned by a scan.
     *
     * @param memoryMapFiles
     *            the value to override {@code VfsSpec#memoryMapFiles} with, so that both paths are tested whatever
     *            the platform's own choice is
     * @return the session
     */
    private static VfsSession session(final boolean memoryMapFiles) {
        final var vfsSpec = new VfsSpec();
        vfsSpec.memoryMapFiles = memoryMapFiles;
        return new VfsSession(vfsSpec, new InterruptionChecker());
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
        final var session = session(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, session, /* log = */ null);
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
        final var session = session(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, session, /* log = */ null);
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

    /**
     * The buffer returned for a sub-slice covers only the sub-slice: it starts at position zero, its capacity is
     * the length of the sub-slice, and there is no route from it to the bytes that surround the sub-slice in the
     * file. It is also read-only, since it may be a view of a mapping shared by every reader of the file.
     */
    @Test
    public void theBufferOfASubSliceCoversOnlyTheSubSlice(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        for (final var memoryMapFiles : new boolean[] { true, false }) {
            final var session = session(memoryMapFiles);
            final var slice = new PathSlice(file, session, /* log = */ null);
            try {
                final var buf = slice.slice(10, 5, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L)
                        .read();
                assertThat(buf.position()).isZero();
                assertThat(buf.capacity()).isEqualTo(5);
                assertThat(buf.remaining()).isEqualTo(5);
                assertThat(buf.get(0)).isEqualTo((byte) 'a');
                assertThat(buf.isReadOnly()).isTrue();
                // Clearing the buffer must not widen it to the rest of the file
                buf.clear();
                assertThat(buf.remaining()).isEqualTo(5);
            } finally {
                slice.close();
            }
        }
    }

    /** The buffer returned for a whole-file slice is read-only too. */
    @Test
    public void theBufferOfAWholeFileSliceIsReadOnly(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        for (final var memoryMapFiles : new boolean[] { true, false }) {
            final var session = session(memoryMapFiles);
            final var slice = new PathSlice(file, session, /* log = */ null);
            try {
                final var buf = slice.read();
                assertThat(buf.isReadOnly()).isTrue();
                assertThat(buf.position()).isZero();
                assertThat(buf.capacity()).isEqualTo(CONTENT.length);
            } finally {
                slice.close();
            }
        }
    }

    /** A whole-file slice is not memory-mapped if memory mapping was not enabled. */
    @Test
    public void aWholeFileSliceIsNotMappedIfMappingIsDisabled(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var session = session(/* memoryMapFiles = */ false);
        final var slice = new PathSlice(file, session, /* log = */ null);
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
        final var session = session(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, session, /* checkAccess = */ false, /* memoryMapWholeFile = */ false,
                /* log = */ null);
        try {
            assertThat(slice.read().isDirect()).isFalse();
            assertThat(slice.load()).isEqualTo(CONTENT);
        } finally {
            slice.close();
        }
    }
}
