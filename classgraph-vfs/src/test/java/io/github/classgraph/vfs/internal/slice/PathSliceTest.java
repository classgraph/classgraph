package io.github.classgraph.vfs.internal.slice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.VfsSpec;
import io.github.classgraph.vfs.internal.VfsSession;

/**
 * Tests that a {@link PathSlice} for a whole file is memory-mapped when the vfs scan spec says files are
 * memory-mapped, that a mapped slice reads back the same content as an unmapped one, and that closing a slice both
 * stops every reader of it and leaves nothing holding its memory mapping.
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
        vfsSpec.setMemoryMappingFiles(memoryMapFiles);
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

    /**
     * A reader that was taken before the slice was closed holds a view of the memory mapping that closing the slice
     * releases, and every other reader of the {@link io.github.classgraph.vfs.Vfs} reports a read of released
     * storage as an {@link IOException}, so this one does too -- rather than letting the arena's
     * {@link IllegalStateException} out on JDK 22 and later, or quietly reading on through a mapping the garbage
     * collector has not caught up with below JDK 22.
     */
    @Test
    public void readingAMappedSliceAfterItWasClosedThrows(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var session = session(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, session, /* log = */ null);
        final var reader = slice.randomAccessReader();
        assertThat(reader.readByte(0)).isEqualTo(CONTENT[0]);

        slice.close();

        assertThatThrownBy(() -> reader.readByte(0)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing the Vfs");
        assertThatThrownBy(() -> reader.readInt(0)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing the Vfs");
        assertThatThrownBy(() -> reader.read(0, new byte[4], 0, 4)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing the Vfs");
        // And a closed slice hands out no new reader at all, rather than one that throws on its first read
        assertThatThrownBy(slice::randomAccessReader).isInstanceOf(IOException.class)
                .hasMessageContaining("after the Vfs has been closed");
    }

    /**
     * A closed slice has released the file it was reading, so it reports that rather than throwing a
     * {@link NullPointerException} from the file channel it no longer has.
     */
    @Test
    public void anUnmappedSliceCannotBeReadAfterItWasClosed(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var session = session(/* memoryMapFiles = */ false);
        final var slice = new PathSlice(file, session, /* log = */ null);
        assertThat(slice.load()).isEqualTo(CONTENT);

        slice.close();

        assertThatThrownBy(slice::randomAccessReader).isInstanceOf(IOException.class)
                .hasMessageContaining("after the Vfs has been closed");
        assertThatThrownBy(slice::load).isInstanceOf(IOException.class)
                .hasMessageContaining("after the Vfs has been closed");
        assertThatThrownBy(slice::read).isInstanceOf(IOException.class)
                .hasMessageContaining("after the Vfs has been closed");
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
     * A sub-slice reads through the toplevel slice it was cut from, so closing the toplevel slice stops the
     * sub-slice reading too, rather than letting it read on through a copy of the mapping that the close was
     * supposed to have taken away.
     */
    @Test
    public void aSubSliceCannotBeReadAfterTheToplevelSliceWasClosed(@TempDir final Path tempDir)
            throws IOException {
        final var file = writeTestFile(tempDir);
        final var session = session(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, session, /* log = */ null);
        final var subSlice = slice.slice(10, 5, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L);
        final var reader = subSlice.randomAccessReader();
        assertThat(reader.readString(0, 5)).isEqualTo("abcde");

        slice.close();

        assertThatThrownBy(() -> reader.readString(0, 5)).isInstanceOf(IOException.class);
        assertThatThrownBy(subSlice::randomAccessReader).isInstanceOf(IOException.class);
        assertThatThrownBy(subSlice::load).isInstanceOf(IOException.class);
        assertThatThrownBy(subSlice::read).isInstanceOf(IOException.class);
    }

    /**
     * Closing a slice leaves nothing at all holding its memory mapping, even while a sub-slice of it is still
     * alive. Below JDK 22 there is no arena to unmap the file on demand, so the file stays mapped until the garbage
     * collector finds every view of the mapping gone -- and on Windows a file that is still mapped cannot be
     * deleted, which is how a temporary file extracted from a nested jar would be stranded. The mapping is
     * deliberately reachable from nothing the API hands out, so the only way to watch it is by reflection.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the test file could not be written or read
     * @throws ReflectiveOperationException
     *             if the mapped buffer could not be read out of the slice
     */
    @Test
    public void closingASliceReleasesItsMappingEvenWhileASubSliceIsAlive(@TempDir final Path tempDir)
            throws IOException, ReflectiveOperationException {
        final var file = writeTestFile(tempDir);
        final var session = session(/* memoryMapFiles = */ true);
        final var slice = new PathSlice(file, session, /* log = */ null);
        final var subSlice = slice.slice(10, 5, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L);
        assertThat(subSlice.loadAsString()).isEqualTo("abcde");

        final var backingByteBuffer = PathSlice.class.getDeclaredField("backingByteBuffer");
        backingByteBuffer.setAccessible(true);
        final var mapping = new WeakReference<>(backingByteBuffer.get(slice));
        assertThat(mapping.refersTo(null)).isFalse();

        slice.close();

        assertThat(collect(mapping)).isTrue();

        // The sub-slice is used after the check above, which is what keeps it strongly reachable across it: the
        // point of the check is that a live sub-slice is not what holds the mapping alive
        assertThatThrownBy(subSlice::load).isInstanceOf(IOException.class);
    }

    /**
     * Ask for garbage collections until the referent has been collected, or give up.
     *
     * @param ref
     *            a reference to the object that should have become garbage
     * @return true if the referent was collected
     */
    private static boolean collect(final WeakReference<?> ref) {
        for (var i = 0; i < 100 && !ref.refersTo(null); i++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ref.refersTo(null);
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
