package nonapi.io.github.classgraph.fileslice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/** Tests for the identity of a {@link Slice}, and for the closing of the slices that a scan left open. */
public class SliceTest {
    /** The content of the test files. Both of them have the same content, and so the same length. */
    private static final byte[] CONTENT = "0123456789".getBytes(StandardCharsets.UTF_8);

    /**
     * Create the handler that owns the slices opened during a scan.
     *
     * @return the nested jar handler
     */
    private static NestedJarHandler nestedJarHandler() {
        return new NestedJarHandler(new ScanSpec(), new InterruptionChecker(), new ReflectionUtils());
    }

    /**
     * Create the handler that owns the slices opened during a scan, with memory mapping enabled.
     *
     * @return the nested jar handler
     */
    private static NestedJarHandler memoryMappingNestedJarHandler() {
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.enableMemoryMapping = true;
        return new NestedJarHandler(scanSpec, new InterruptionChecker(), new ReflectionUtils());
    }

    /**
     * Write a file of the standard length.
     *
     * @param tempDir
     *            the temporary directory to write the file to
     * @param filename
     *            the name of the file
     * @return the file
     * @throws IOException
     *             if the file could not be written
     */
    private static File writeFile(final File tempDir, final String filename) throws IOException {
        final File file = new File(tempDir, filename);
        Files.write(file.toPath(), CONTENT);
        return file;
    }

    /**
     * Two slices of the same length taken from two different files are different slices, even though they span the
     * same range. Otherwise one of them stands in for the other wherever slices are collected in a set or a map.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if a file could not be written or opened
     */
    @Test
    public void slicesOfDifferentFilesOfTheSameLengthAreDifferentSlices(@TempDir final File tempDir)
            throws IOException {
        final NestedJarHandler nestedJarHandler = nestedJarHandler();
        final FileSlice first = new FileSlice(writeFile(tempDir, "first.bin"), nestedJarHandler, /* log = */ null);
        final FileSlice second = new FileSlice(writeFile(tempDir, "second.bin"), nestedJarHandler,
                /* log = */ null);
        try {
            assertThat(first).isNotEqualTo(second);
            // A slice is equal to itself, and to a slice of the same range of the same file
            assertThat(first).isEqualTo(first);
            assertThat(first.slice(0, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L))
                    .isEqualTo(first.slice(0, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L));
            // Two sub-slices that span the same range of two different files are different slices too, since they
            // inherit the identity of the slices they were taken from
            assertThat(first.slice(2, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L))
                    .isNotEqualTo(
                            second.slice(2, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L));
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A stream that returns zero from a read of a non-empty buffer is not treated as the end of the stream.
     *
     * <p>
     * {@link InputStream#read(byte[], int, int)} is supposed to block until at least one byte has been read, but a
     * stream that returns zero instead is the reason the end of the stream has to be probed for at all. The probe
     * used to be a read into a one-byte array, which has the same ambiguity, so a stream that returned zero twice
     * in a row was read as an empty stream, silently discarding its whole content.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    public void aStreamThatReturnsZeroFromAReadIsNotTreatedAsEndOfStream() throws IOException {
        final InputStream stream = new InputStream() {
            private final InputStream wrapped = new ByteArrayInputStream(CONTENT);

            /** The number of reads into a non-empty buffer left to answer with zero. */
            private int zeroReadsLeft = 2;

            @Override
            public int read() throws IOException {
                return wrapped.read();
            }

            @Override
            public int read(final byte[] buf, final int off, final int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                if (zeroReadsLeft > 0) {
                    zeroReadsLeft--;
                    return 0;
                }
                return wrapped.read(buf, off, len);
            }
        };
        final NestedJarHandler nestedJarHandler = nestedJarHandler();
        try {
            final Slice slice = nestedJarHandler.readAllBytesWithSpilloverToDisk(stream, "zeroreads.bin",
                    /* inputStreamLengthHint = */ -1L, /* log = */ null);
            assertThat(slice.sliceLength).isEqualTo(CONTENT.length);
            assertThat(slice.load()).containsExactly(CONTENT);
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A file is memory-mapped on every JDK when the scan spec asks for it. On JDK 22 or later the mapping is
     * unmapped by closing the arena it was made in; below that it is left to the JDK's own cleaner, which unmaps
     * it once no view of it is reachable any more. What is never called is {@code Unsafe::invokeCleaner}, which
     * frees the address range whether or not another thread is still reading it.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written or opened
     */
    @Test
    public void aFileIsMemoryMappedWhenTheScanSpecAsksForIt(@TempDir final File tempDir) throws IOException {
        final NestedJarHandler nestedJarHandler = memoryMappingNestedJarHandler();
        try {
            final FileSlice slice = new FileSlice(writeFile(tempDir, "mapped.bin"), nestedJarHandler,
                    /* log = */ null);
            // A mapped slice is read from a direct ByteBuffer, an unmapped slice from a heap ByteBuffer
            assertThat(slice.read().isDirect()).isTrue();
            assertThat(slice.load()).containsExactly(CONTENT);
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A file that a scan memory-mapped can be deleted once the scan has been closed. Windows refuses to delete a
     * file that is still mapped, and below JDK 22 the mapping is released only when the garbage collector finds
     * the mapped buffer unreachable, so closing the scan has to ask for a collection to make the file deletable
     * again. (Every other operating system lets a mapped file be deleted, so this test only bites on Windows.)
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written, opened or deleted
     */
    // #939
    @Test
    public void aMappedFileCanBeDeletedOnceTheScanIsClosed(@TempDir final File tempDir) throws IOException {
        final NestedJarHandler nestedJarHandler = memoryMappingNestedJarHandler();
        final File file = writeFile(tempDir, "mapped.bin");
        final FileSlice slice = new FileSlice(file, nestedJarHandler, /* log = */ null);
        assertThat(slice.read().isDirect()).isTrue();

        // Closing the handler closes the slice, which drops the last reference to the mapped buffer
        nestedJarHandler.close(/* log = */ null);

        Files.delete(file.toPath());
        assertThat(file.exists()).isFalse();
    }

    /**
     * A reader taken before the slice was closed holds a view of the memory mapping that closing the slice
     * releases. Every other reader reports a read of a file the scan has released as an {@link IOException}, so
     * this one does too, on every JDK: on JDK 22 or later rather than letting the arena's
     * {@link IllegalStateException} out, and below JDK 22 rather than quietly returning content from a mapping
     * that the garbage collector has not got round to unmapping yet.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written, opened or read
     */
    @Test
    public void readingAMappedSliceAfterItWasClosedThrowsIOException(@TempDir final File tempDir)
            throws IOException {
        final NestedJarHandler nestedJarHandler = memoryMappingNestedJarHandler();
        final FileSlice slice = new FileSlice(writeFile(tempDir, "mapped.bin"), nestedJarHandler, /* log = */ null);
        final RandomAccessReader reader = slice.randomAccessReader();
        assertThat(reader.readByte(0)).isEqualTo(CONTENT[0]);

        nestedJarHandler.close(/* log = */ null);

        assertThatThrownBy(() -> reader.readByte(0)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing the ScanResult");
        assertThatThrownBy(() -> reader.readShort(0)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing the ScanResult");
        assertThatThrownBy(() -> reader.readInt(0)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing the ScanResult");
        assertThatThrownBy(() -> reader.readLong(0)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing the ScanResult");
        assertThatThrownBy(() -> reader.read(0, new byte[4], 0, 4)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing the ScanResult");
    }

    /**
     * A sub-slice of a memory-mapped file reads through the toplevel slice, so closing the toplevel slice stops
     * the sub-slice from being read too. A reader taken before the close keeps a view of the mapping, and below
     * JDK 22 that view stays readable until the garbage collector unmaps the file, so only the closed flag stops
     * it from returning file content.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written, opened or read
     */
    @Test
    public void aSubSliceCannotBeReadAfterTheToplevelSliceWasClosed(@TempDir final File tempDir)
            throws IOException {
        final NestedJarHandler nestedJarHandler = memoryMappingNestedJarHandler();
        final FileSlice slice = new FileSlice(writeFile(tempDir, "mapped.bin"), nestedJarHandler, /* log = */ null);
        final Slice subSlice = slice.slice(2, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L);
        final RandomAccessReader reader = subSlice.randomAccessReader();
        assertThat(new String(subSlice.load(), StandardCharsets.UTF_8)).isEqualTo("2345");

        nestedJarHandler.close(/* log = */ null);

        assertThatThrownBy(() -> reader.readByte(0)).isInstanceOf(IOException.class);
        assertThatThrownBy(subSlice::load).isInstanceOf(IOException.class);
        assertThatThrownBy(subSlice::read).isInstanceOf(IOException.class);
    }

    /**
     * Closing a slice releases its memory mapping even while a sub-slice of it is still alive. Below JDK 22 a
     * mapping is unmapped only once the garbage collector finds that nothing can read it any more, so a sub-slice
     * that kept a duplicate of the mapping would keep the file mapped -- and, on Windows, locked open -- however
     * long ago it was closed.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written, opened or read
     * @throws ReflectiveOperationException
     *             if the mapping could not be read out of the slice
     */
    @Test
    public void closingASliceReleasesItsMappingEvenWhileASubSliceIsAlive(@TempDir final File tempDir)
            throws IOException, ReflectiveOperationException {
        final NestedJarHandler nestedJarHandler = memoryMappingNestedJarHandler();
        final FileSlice slice = new FileSlice(writeFile(tempDir, "mapped.bin"), nestedJarHandler, /* log = */ null);
        final Slice subSlice = slice.slice(2, 4, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L);
        // The file really is mapped, so that what is checked below is the release of a mapping, not its absence
        assertThat(slice.read().isDirect()).isTrue();
        assertThat(new String(subSlice.load(), StandardCharsets.UTF_8)).isEqualTo("2345");

        // Watch the mapping through a reference queue rather than through WeakReference#get(), which can keep
        // its referent alive for another collection cycle on a garbage collector that uses load barriers. The
        // WeakReference has to be kept in a local, since an unreachable WeakReference is never enqueued -- but
        // keeping the reference does not keep its referent alive, which is the whole point of a weak reference
        final Field backingByteBuffer = FileSlice.class.getDeclaredField("backingByteBuffer");
        backingByteBuffer.setAccessible(true);
        final ReferenceQueue<Object> collected = new ReferenceQueue<>();
        final WeakReference<Object> mapping = new WeakReference<>(backingByteBuffer.get(slice), collected);

        nestedJarHandler.close(/* log = */ null);

        assertThat(wasCollected(mapping, collected)).isTrue();

        // The sub-slice is used after the check above, which is what keeps it strongly reachable across it: the
        // point of the check is that a live sub-slice is not what holds the mapping alive
        assertThatThrownBy(subSlice::load).isInstanceOf(IOException.class);
    }

    /**
     * Wait for a weak reference to be enqueued, asking for garbage collection until it is.
     *
     * @param reference
     *            the weak reference to wait for
     * @param collected
     *            the queue the weak reference was registered with
     * @return true if the referent was collected
     */
    private static boolean wasCollected(final Reference<?> reference, final ReferenceQueue<?> collected) {
        for (int i = 0; i < 100; i++) {
            System.gc();
            try {
                if (collected.remove(10) == reference) {
                    return true;
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Closing the handler that owns the slices opened during a scan closes every slice that was left open,
     * including slices that span the same range of two different files. Otherwise one of the files stays open,
     * which on Windows stops it from being deleted.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if a file could not be written or opened
     */
    @Test
    public void closingTheHandlerClosesEverySliceThatWasLeftOpen(@TempDir final File tempDir) throws IOException {
        final NestedJarHandler nestedJarHandler = nestedJarHandler();
        final FileSlice first = new FileSlice(writeFile(tempDir, "first.bin"), nestedJarHandler, /* log = */ null);
        final FileSlice second = new FileSlice(writeFile(tempDir, "second.bin"), nestedJarHandler,
                /* log = */ null);

        nestedJarHandler.close(/* log = */ null);

        // A closed slice has released the file it was reading, so it can no longer be read
        assertThatThrownBy(first::load).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(second::load).isInstanceOf(NullPointerException.class);
    }
}
