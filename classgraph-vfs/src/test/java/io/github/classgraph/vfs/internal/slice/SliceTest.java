package io.github.classgraph.vfs.internal.slice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.concurrency.IdleTimeout;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsSpec;

/**
 * Tests for the identity of a {@link Slice}, for the reading of an {@link InputStream} into a {@link Slice}, and
 * for the closing of the slices that a scan left open.
 */
public class SliceTest {
    /** The content of the test files. Two of them have the same content, and so the same length. */
    private static final byte[] CONTENT = "0123456789".getBytes(StandardCharsets.UTF_8);

    /**
     * Create the resources owned by a scan.
     *
     * @return the vfs
     */
    private static Vfs vfs() {
        return vfs(new VfsSpec().getMaxBufferedJarRAMSize());
    }

    /**
     * Create the resources owned by a scan, buffering at most the given number of bytes of a jar in RAM.
     *
     * @param maxBufferedJarRAMSize
     *            the maximum number of bytes of a jar to buffer in RAM before spilling it to disk
     * @return the vfs
     */
    private static Vfs vfs(final int maxBufferedJarRAMSize) {
        final var vfsSpec = new VfsSpec();
        vfsSpec.setMaxBufferedJarRAMSize(maxBufferedJarRAMSize);
        return new Vfs(vfsSpec, new InterruptionChecker());
    }

    /**
     * Read an {@link InputStream} over {@link #CONTENT} into a slice, and check that the slice holds the whole
     * content, in order.
     *
     * @param vfs
     *            the vfs that owns what is opened
     * @param inputStreamLengthHint
     *            the length of the stream to claim, which may be wrong
     * @return the slice
     * @throws IOException
     *             if the stream could not be read
     */
    private static Slice sliceOfContent(final Vfs vfs, final long inputStreamLengthHint) throws IOException {
        final var slice = Slice.fromInputStream(new ByteArrayInputStream(CONTENT), "content.bin",
                inputStreamLengthHint, vfs, /* log = */ null);
        assertThat(slice.sliceLength).isEqualTo(CONTENT.length);
        assertThat(slice.load()).containsExactly(CONTENT);
        return slice;
    }

    /**
     * Write a file of the standard length.
     *
     * @param tempDir
     *            the temporary directory to write the file to
     * @param filename
     *            the name of the file
     * @return the path of the file
     * @throws IOException
     *             if the file could not be written
     */
    private static Path writeFile(final Path tempDir, final String filename) throws IOException {
        return Files.write(tempDir.resolve(filename), CONTENT);
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
    public void slicesOfDifferentFilesOfTheSameLengthAreDifferentSlices(@TempDir final Path tempDir)
            throws IOException {
        final var vfs = vfs();
        try (var first = new PathSlice(writeFile(tempDir, "first.bin"), vfs, /* log = */ null);
                var second = new PathSlice(writeFile(tempDir, "second.bin"), vfs, /* log = */ null)) {
            assertThat(first).isNotEqualTo(second);
            // A slice is equal to itself, and to a slice of the same range of the same file
            assertThat(first).isEqualTo(first);
            assertThat(first.slice(0, 4)).isEqualTo(first.slice(0, 4));
        }
    }

    /**
     * Two sub-slices that span the same range of two different files are different slices too, since they inherit
     * the identity of the slices they were taken from.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if a file could not be written or opened
     */
    @Test
    public void subSlicesOfDifferentFilesAreDifferentSlices(@TempDir final Path tempDir) throws IOException {
        final var vfs = vfs();
        try (var first = new PathSlice(writeFile(tempDir, "first.bin"), vfs, /* log = */ null);
                var second = new PathSlice(writeFile(tempDir, "second.bin"), vfs, /* log = */ null)) {
            assertThat(first.slice(2, 4)).isNotEqualTo(second.slice(2, 4));
        }
    }

    /**
     * An {@link InputStream} that fits in RAM is read into an {@link ArraySlice}, whether its length is known,
     * unknown, overstated, or given as zero (which some zipfiles do for entries that are not empty).
     *
     * @throws IOException
     *             if a stream could not be read
     */
    @Test
    public void anInputStreamThatFitsInRamIsReadIntoAnArraySlice() throws IOException {
        final var vfs = vfs();
        assertThat(sliceOfContent(vfs, /* inputStreamLengthHint = */ -1L)).isInstanceOf(ArraySlice.class);
        assertThat(sliceOfContent(vfs, CONTENT.length)).isInstanceOf(ArraySlice.class);
        assertThat(sliceOfContent(vfs, CONTENT.length * 2L)).isInstanceOf(ArraySlice.class);
        assertThat(sliceOfContent(vfs, /* inputStreamLengthHint = */ 0L)).isInstanceOf(ArraySlice.class);

        // An empty stream produces an empty slice, rather than failing
        final var empty = Slice.fromInputStream(new ByteArrayInputStream(new byte[0]), "empty.bin",
                /* inputStreamLengthHint = */ -1L, vfs, /* log = */ null);
        assertThat(empty.sliceLength).isZero();
        assertThat(empty.load()).isEmpty();
    }

    /**
     * An {@link InputStream} that is longer than the maximum RAM buffer size is spilled to a temporary file, in
     * order, including the bytes that were already buffered before the stream turned out to be too long.
     *
     * @throws IOException
     *             if a stream could not be read
     */
    @Test
    public void anInputStreamThatIsTooLongToBufferIsSpilledToATemporaryFile() throws IOException {
        // A length hint that is longer than the maximum RAM buffer size spills to disk without buffering
        final var vfs = vfs(/* maxBufferedJarRAMSize = */ CONTENT.length / 2);
        try {
            final var slice = sliceOfContent(vfs, CONTENT.length);
            assertThat(slice).isInstanceOf(PathSlice.class);
            // The slice owns the temporary file it spilled to, and deletes it when it is closed
            final var tempFile = ((PathSlice) slice).getFile();
            assertThat(tempFile).isNotNull().exists();
            slice.close();
            assertThat(tempFile).doesNotExist();
        } finally {
            vfs.close(/* log = */ null);
        }

        // A length hint that understates the length of the stream fills the buffer, and only then turns out to be
        // wrong, so the buffered bytes have to be written to the temporary file ahead of the rest of the stream
        final var understatedSession = vfs(/* maxBufferedJarRAMSize = */ CONTENT.length / 2);
        try {
            final var slice = sliceOfContent(understatedSession, /* inputStreamLengthHint = */ CONTENT.length / 2);
            assertThat(slice).isInstanceOf(PathSlice.class);
            // The caller owns the slice, so closing the Vfs would not close it or delete its temporary file
            final var tempFile = ((PathSlice) slice).getFile();
            assertThat(tempFile).isNotNull().exists();
            slice.close();
            assertThat(tempFile).doesNotExist();
        } finally {
            understatedSession.close(/* log = */ null);
        }
    }

    /**
     * A stream that is longer than {@link Slice#MAX_BUFFER_SIZE}, the largest array that a slice is read into, is
     * spilled to a temporary file, even when the maximum RAM buffer size is set higher than that, e.g. to
     * {@link Integer#MAX_VALUE} to mean "never spill to disk". Before the fix, the buffer was allowed to grow to
     * the maximum RAM buffer size, so once a stream outgrew a buffer of 2^30 bytes, doubling the buffer asked for
     * an array of {@link Integer#MAX_VALUE} bytes. HotSpot refuses that with {@link OutOfMemoryError} ("Requested
     * array size exceeds VM limit") however large the heap is, and the jar was not read, rather than the rest of
     * the stream being spilled to disk.
     *
     * <p>
     * Reading a stream of over a gigabyte needs several gigabytes of heap, so this test instead gives a short
     * stream a declared length one byte longer than {@link Slice#MAX_BUFFER_SIZE}. A stream that declares that
     * length cannot fit in the buffer, so it has to go straight to disk; before the fix it was read into RAM, which
     * is the start of the path that ends in the {@link OutOfMemoryError} for a stream that really is that long.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    public void aStreamTooLongForAnyArrayIsSpilledToDiskWhateverTheMaximumRamBufferSize() throws IOException {
        final var vfs = vfs(/* maxBufferedJarRAMSize = */ Integer.MAX_VALUE);
        try (var slice = sliceOfContent(vfs, /* inputStreamLengthHint = */ Slice.MAX_BUFFER_SIZE + 1L)) {
            assertThat(slice).isInstanceOf(PathSlice.class);
        } finally {
            vfs.close(/* log = */ null);
        }
    }

    /**
     * A stream that turns out to be longer than its length hint, but still short enough to buffer in RAM, is read
     * into a larger buffer rather than being spilled to a temporary file. The hint is only a hint -- a zip entry
     * can understate the length of the entry it describes -- and spilling a jar of a few bytes to disk when
     * megabytes of RAM were allowed for it is not what the setting says will happen.
     *
     * @throws IOException
     *             if a stream could not be read
     */
    @Test
    public void aStreamThatOutgrowsItsLengthHintButStillFitsInRamIsBuffered() throws IOException {
        final var vfs = vfs();
        try {
            assertThat(sliceOfContent(vfs, /* inputStreamLengthHint = */ CONTENT.length / 2))
                    .isInstanceOf(ArraySlice.class);
            assertThat(sliceOfContent(vfs, /* inputStreamLengthHint = */ 1L)).isInstanceOf(ArraySlice.class);
        } finally {
            vfs.close(/* log = */ null);
        }
    }

    /**
     * A declared length is not believed far enough to allocate more than 16MB up front, even when the maximum RAM
     * buffer size would allow it. The declared length of a nested jar is the uncompressed size its zip entry
     * claims, which can be anything, so a small archive could otherwise make every reader of it allocate the whole
     * of the maximum RAM buffer size. The buffer grows by doubling if the content really is that long.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    public void aDeclaredLengthDoesNotAllocateMoreThan16MBUpFront() throws IOException {
        final var maxBufferedJarRAMSize = 64 * 1024 * 1024;
        // The number of bytes that the first read asked for, which is the size of the initial buffer
        final var firstReadLength = new AtomicInteger(-1);
        final var stream = new InputStream() {
            private final InputStream wrapped = new ByteArrayInputStream(CONTENT);

            @Override
            public int read() throws IOException {
                return wrapped.read();
            }

            @Override
            public int read(final byte[] buf, final int off, final int len) throws IOException {
                firstReadLength.compareAndSet(-1, len);
                return wrapped.read(buf, off, len);
            }
        };
        final var vfs = vfs(maxBufferedJarRAMSize);
        try {
            final var slice = Slice.fromInputStream(stream, "overstated.bin",
                    /* inputStreamLengthHint = */ 60 * 1024 * 1024, vfs, /* log = */ null);
            assertThat(slice.load()).containsExactly(CONTENT);
            assertThat(firstReadLength.get()).isLessThanOrEqualTo(16 * 1024 * 1024);
        } finally {
            vfs.close(/* log = */ null);
        }
    }

    /**
     * Reading a stream whose length is not known does not allocate the whole of the maximum RAM buffer size up
     * front, since most jars are a tiny fraction of that size, and several may be read at once.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    public void aStreamOfUnknownLengthDoesNotAllocateTheWholeRamBudgetUpFront() throws IOException {
        final var maxBufferedJarRAMSize = 64 * 1024 * 1024;
        // The number of bytes that the first read asked for, which is the size of the initial buffer
        final var firstReadLength = new AtomicInteger(-1);
        final var stream = new InputStream() {
            private final InputStream wrapped = new ByteArrayInputStream(CONTENT);

            @Override
            public int read() throws IOException {
                return wrapped.read();
            }

            @Override
            public int read(final byte[] buf, final int off, final int len) throws IOException {
                firstReadLength.compareAndSet(-1, len);
                return wrapped.read(buf, off, len);
            }
        };
        final var vfs = vfs(maxBufferedJarRAMSize);
        try {
            final var slice = Slice.fromInputStream(stream, "unknownlength.bin", /* inputStreamLengthHint = */ -1L,
                    vfs, /* log = */ null);
            assertThat(slice.load()).containsExactly(CONTENT);
            assertThat(firstReadLength.get()).isLessThanOrEqualTo(65536);
        } finally {
            vfs.close(/* log = */ null);
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
        final var stream = new InputStream() {
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
        final var vfs = vfs();
        try {
            final var slice = Slice.fromInputStream(stream, "zeroreads.bin", /* inputStreamLengthHint = */ -1L, vfs,
                    /* log = */ null);
            assertThat(slice.sliceLength).isEqualTo(CONTENT.length);
            assertThat(slice.load()).containsExactly(CONTENT);
        } finally {
            vfs.close(/* log = */ null);
        }
    }

    /**
     * A stream that is too long to hold in RAM, and that returns zero from a read of a non-empty buffer, is copied
     * to disk in full, including when it returns zero rather than -1 at its end. Before the fix, the copy to disk
     * read such a stream forever.
     *
     * <p>
     * The timeout runs the test in a separate thread, since a same-thread timeout is only checked once the test
     * method returns, which a non-terminating loop never does.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    @Timeout(value = 60, threadMode = ThreadMode.SEPARATE_THREAD)
    public void aStreamThatReturnsZeroIsSpilledToDiskInFull() throws IOException {
        final var stream = new InputStream() {
            private final InputStream wrapped = new ByteArrayInputStream(CONTENT);

            /** The number of calls to {@link #read(byte[], int, int)} so far. */
            private int numBulkReads;

            @Override
            public int read() throws IOException {
                return wrapped.read();
            }

            @Override
            public int read(final byte[] buf, final int off, final int len) throws IOException {
                if (len == 0 || numBulkReads++ % 2 == 0) {
                    return 0;
                }
                return Math.max(wrapped.read(buf, off, len), 0);
            }
        };
        final var vfs = vfs(/* maxBufferedJarRAMSize = */ 4);
        try {
            final var slice = Slice.fromInputStream(stream, "zeroreads.bin",
                    /* inputStreamLengthHint = */ CONTENT.length, vfs, /* log = */ null);
            assertThat(slice).isInstanceOf(PathSlice.class);
            assertThat(slice.load()).containsExactly(CONTENT);
            final var tempFile = ((PathSlice) slice).getFile();
            assertThat(tempFile).isNotNull().exists();
            slice.close();
            assertThat(tempFile).doesNotExist();
        } finally {
            vfs.close(/* log = */ null);
        }
    }

    /**
     * A stream that returns zero from a read of a non-empty buffer does not make the buffer grow. The buffer is
     * only out of room when it is full, and treating a zero-length read as a full buffer doubles the buffer on
     * every such read, up to the whole of the maximum RAM buffer size, to hold a stream of a few bytes.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    public void aStreamThatReturnsZeroFromAReadDoesNotGrowTheBuffer() throws IOException {
        final var stream = new InputStream() {
            private final InputStream wrapped = new ByteArrayInputStream(CONTENT);

            /** The number of reads into a non-empty buffer left to answer with zero. */
            private int zeroReadsLeft = 1;

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
        // The content fits exactly within the maximum RAM buffer size, so it is only spilled to disk if the zero
        // read is mistaken for a full buffer
        final var vfs = vfs(/* maxBufferedJarRAMSize = */ CONTENT.length);
        try {
            final var slice = Slice.fromInputStream(stream, "zeroreads.bin", /* inputStreamLengthHint = */ -1L, vfs,
                    /* log = */ null);
            assertThat(slice).isInstanceOf(ArraySlice.class);
            assertThat(slice.load()).containsExactly(CONTENT);
        } finally {
            vfs.close(/* log = */ null);
        }
    }

    /**
     * A slice opened over a file that no root owns belongs to whoever opened it, and closing the {@link Vfs} does
     * not close it -- but a stream read through it stops once the {@link Vfs} is closed, so a caller that leaked
     * such a slice cannot go on reading through a virtual filesystem that has been torn down.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if a file could not be written or opened
     */
    @Test
    public void slicesTheCallerOwnsOutliveTheVfsButRefuseToBeRead(@TempDir final Path tempDir) throws IOException {
        final var vfs = vfs();
        final var first = new PathSlice(writeFile(tempDir, "first.bin").toFile(), vfs, /* log = */ null);
        final var second = new PathSlice(writeFile(tempDir, "second.bin").toFile(), vfs, /* log = */ null);

        vfs.close(/* log = */ null);

        try (var firstInputStream = first.open(); var secondInputStream = second.open()) {
            assertThatThrownBy(firstInputStream::read).isInstanceOf(IOException.class)
                    .hasMessageContaining("after the Vfs has been closed");
            assertThatThrownBy(secondInputStream::read).isInstanceOf(IOException.class)
                    .hasMessageContaining("after the Vfs has been closed");
        } finally {
            // The slices belong to this test, so this test closes them
            first.close();
            second.close();
        }
    }

    /**
     * The single byte read to find out whether a stream goes on past a full array counts as progress, as the block
     * reads do, so that a thread waiting for this one does not count the time to that byte as idle. The stream
     * understates its length by one byte, so that byte is the last one read, and the read after it finds the end of
     * the stream.
     *
     * @throws IOException
     *             if the stream could not be read
     * @throws InterruptedException
     *             if the test thread was interrupted
     */
    @Test
    public void theByteReadPastAFullArrayCountsAsProgress() throws IOException, InterruptedException {
        final var vfs = vfs();
        // Started when the single byte is read, so that only progress reported from then on resets it
        final var idleTimeoutFromSingleByteRead = new AtomicReference<IdleTimeout>();
        final var stream = new InputStream() {
            private final InputStream wrapped = new ByteArrayInputStream(CONTENT);

            @Override
            public int read() throws IOException {
                final var idleTimeout = new IdleTimeout(vfs.interruptionChecker(), /* timeoutNanos = */ 1L);
                idleTimeout.nextWaitNanos();
                idleTimeoutFromSingleByteRead.set(idleTimeout);
                return wrapped.read();
            }

            @Override
            public int read(final byte[] buf, final int off, final int len) throws IOException {
                return wrapped.read(buf, off, len);
            }
        };
        try {
            final var slice = Slice.fromInputStream(stream, "understated.bin",
                    /* inputStreamLengthHint = */ CONTENT.length - 1, vfs, /* log = */ null);
            assertThat(slice.load()).containsExactly(CONTENT);
            final var idleTimeout = idleTimeoutFromSingleByteRead.get();
            assertThat(idleTimeout).isNotNull();
            // Without progress since the single byte was read, the 1ns timeout would have run out by now
            Thread.sleep(1L);
            assertThat(idleTimeout.hasRunOut()).isFalse();
        } finally {
            vfs.close(/* log = */ null);
        }
    }
}
