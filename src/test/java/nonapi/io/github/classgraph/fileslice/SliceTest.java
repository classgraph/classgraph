package nonapi.io.github.classgraph.fileslice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.VersionFinder;

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
     * Create the handler that owns the slices opened during a scan, with a given limit on the amount of RAM that a
     * jar may be buffered in.
     *
     * @param maxBufferedJarRAMSize
     *            the maximum number of bytes that a jar may be buffered in
     * @return the nested jar handler
     */
    private static NestedJarHandler nestedJarHandler(final int maxBufferedJarRAMSize) {
        final ScanSpec scanSpec = new ScanSpec();
        scanSpec.maxBufferedJarRAMSize = maxBufferedJarRAMSize;
        return new NestedJarHandler(scanSpec, new InterruptionChecker(), new ReflectionUtils());
    }

    /**
     * Read the standard content from an {@link InputStream}, telling the reader the given length for it.
     *
     * @param nestedJarHandler
     *            the handler to read the stream with
     * @param inputStreamLengthHint
     *            the length to claim for the stream, or -1L for an unknown length
     * @return the slice the content was read into
     * @throws IOException
     *             if the stream could not be read
     */
    private static Slice sliceOfContent(final NestedJarHandler nestedJarHandler, final long inputStreamLengthHint)
            throws IOException {
        final Slice slice = nestedJarHandler.readAllBytesWithSpilloverToDisk(new ByteArrayInputStream(CONTENT),
                "content.bin", inputStreamLengthHint, /* log = */ null);
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
     * An {@link InputStream} that is longer than the maximum RAM buffer size is spilled to a temporary file, in
     * order, including the bytes that were already buffered before the stream turned out to be too long.
     *
     * @throws IOException
     *             if a stream could not be read
     */
    @Test
    public void anInputStreamThatIsTooLongToBufferIsSpilledToATemporaryFile() throws IOException {
        // A length hint that is longer than the maximum RAM buffer size spills to disk without buffering
        final NestedJarHandler nestedJarHandler = nestedJarHandler(/* maxBufferedJarRAMSize = */ CONTENT.length / 2);
        try {
            assertThat(sliceOfContent(nestedJarHandler, CONTENT.length)).isInstanceOf(FileSlice.class);
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }

        // A length hint that understates the length of the stream fills the buffer, and only then turns out to be
        // wrong, so the buffered bytes have to be written to the temporary file ahead of the rest of the stream
        final NestedJarHandler understatedHandler = nestedJarHandler(
                /* maxBufferedJarRAMSize = */ CONTENT.length / 2);
        try {
            assertThat(sliceOfContent(understatedHandler, /* inputStreamLengthHint = */ CONTENT.length / 2))
                    .isInstanceOf(FileSlice.class);
        } finally {
            understatedHandler.close(/* log = */ null);
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
        final NestedJarHandler nestedJarHandler = nestedJarHandler();
        try {
            assertThat(sliceOfContent(nestedJarHandler, /* inputStreamLengthHint = */ CONTENT.length / 2))
                    .isInstanceOf(ArraySlice.class);
            assertThat(sliceOfContent(nestedJarHandler, /* inputStreamLengthHint = */ 1L))
                    .isInstanceOf(ArraySlice.class);
        } finally {
            nestedJarHandler.close(/* log = */ null);
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
        // The number of bytes that the first read asked for, which is the size of the initial buffer
        final AtomicInteger firstReadLength = new AtomicInteger(-1);
        final InputStream stream = new InputStream() {
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
        final NestedJarHandler nestedJarHandler = nestedJarHandler(/* maxBufferedJarRAMSize = */ 64 * 1024 * 1024);
        try {
            final Slice slice = nestedJarHandler.readAllBytesWithSpilloverToDisk(stream, "unknownlength.bin",
                    /* inputStreamLengthHint = */ -1L, /* log = */ null);
            assertThat(slice.load()).containsExactly(CONTENT);
            assertThat(firstReadLength.get()).isLessThanOrEqualTo(65536);
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
     * A stream that returns zero from a read of a non-empty buffer does not make the buffer grow. The buffer is
     * only out of room when it is full, and treating a zero-length read as a full buffer doubles the buffer on
     * every such read, up to the whole of the maximum RAM buffer size, to hold a stream of a few bytes.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    public void aStreamThatReturnsZeroFromAReadDoesNotGrowTheBuffer() throws IOException {
        final InputStream stream = new InputStream() {
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
        final NestedJarHandler nestedJarHandler = nestedJarHandler(/* maxBufferedJarRAMSize = */ CONTENT.length);
        try {
            final Slice slice = nestedJarHandler.readAllBytesWithSpilloverToDisk(stream, "zeroreads.bin",
                    /* inputStreamLengthHint = */ -1L, /* log = */ null);
            assertThat(slice).isInstanceOf(ArraySlice.class);
            assertThat(slice.load()).containsExactly(CONTENT);
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A file is memory-mapped on every JDK when the scan spec asks for it. On JDK 22 or later the mapping is
     * unmapped by closing the arena it was made in; below that it is unmapped by {@code Unsafe::invokeCleaner},
     * the only method there is that can unmap a file on demand.
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
     * A file that a scan memory-mapped can be deleted once the scan has been closed, since closing the scan
     * unmaps it. Windows refuses to delete a file that is still mapped. (Every other operating system lets a
     * mapped file be deleted, so this test only bites on Windows.)
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
     * A reader taken before the slice was closed holds a duplicate of the memory mapping that closing the slice
     * unmaps. Every other reader reports a read of a file the scan has released as an {@link IOException}, so
     * this one does too, on every JDK: on JDK 22 or later rather than letting the arena's
     * {@link IllegalStateException} out, and below JDK 22 rather than reading an address range that closing the
     * slice freed, which would take a SIGSEGV that kills the JVM.
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
     * the sub-slice from being read too. A reader taken before the close holds a duplicate of the mapping, which
     * below JDK 22 points at an address range that the close freed, so only the closed flag stops it from
     * reading memory that is no longer there.
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
     * Closing a slice leaves nothing at all holding its memory mapping, even while a sub-slice of it is still
     * alive. Below JDK 22 the close unmaps the file by freeing its address range, so anything left holding the
     * mapped buffer would be holding a view of memory that is no longer there, and reading through it would take
     * a SIGSEGV that kills the JVM.
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
     * A stream that has been closed holds nothing of the memory mapping of the file it was reading, even while the
     * stream itself is still alive. A stream reads through a reader, and a reader of a mapped file holds a
     * duplicate of the mapped buffer, so a closed stream that still held its reader would be holding a view of the
     * address range that closing the slice freed. A stream handed out by {@link io.github.classgraph.Resource} can
     * be kept by the caller for as long as it likes, so what a closed one still refers to is worth checking.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written, opened or read
     * @throws ReflectiveOperationException
     *             if the mapping could not be read out of the slice
     */
    // #939
    @Test
    public void closingAStreamReleasesTheViewItHeldOfTheMapping(@TempDir final File tempDir)
            throws IOException, ReflectiveOperationException {
        final NestedJarHandler nestedJarHandler = memoryMappingNestedJarHandler();
        final FileSlice slice = new FileSlice(writeFile(tempDir, "mapped.bin"), nestedJarHandler, /* log = */ null);
        final InputStream inputStream = slice.open();
        assertThat(inputStream.read()).isEqualTo(CONTENT[0]);

        final Field backingByteBuffer = FileSlice.class.getDeclaredField("backingByteBuffer");
        backingByteBuffer.setAccessible(true);
        final ReferenceQueue<Object> collected = new ReferenceQueue<>();
        final WeakReference<Object> mapping = new WeakReference<>(backingByteBuffer.get(slice), collected);

        inputStream.close();
        nestedJarHandler.close(/* log = */ null);

        assertThat(wasCollected(mapping, collected)).isTrue();

        // The stream is used after the check above, which is what keeps it strongly reachable across it: the
        // point of the check is that a closed stream is not what holds the mapping alive
        assertThatThrownBy(inputStream::read).isInstanceOf(IOException.class);
    }

    /** The file through which Linux says which files this process has memory-mapped. */
    private static final Path PROC_SELF_MAPS = Paths.get("/proc/self/maps");

    /**
     * Whether a file is currently memory-mapped by this JVM. Only Linux can tell, through {@code /proc/self/maps},
     * so a test that calls this has to skip itself everywhere else.
     *
     * @param file
     *            the file to look for
     * @return true if the file is memory-mapped
     * @throws IOException
     *             if {@code /proc/self/maps} could not be read
     */
    private static boolean isMemoryMapped(final File file) throws IOException {
        final String fileName = file.getName();
        for (final String line : Files.readAllLines(PROC_SELF_MAPS, StandardCharsets.UTF_8)) {
            if (line.endsWith(fileName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A file that a slice memory-mapped is unmapped by the time the slice has closed, rather than at whatever
     * later moment the garbage collector would have got to it. Windows refuses to delete, rename or overwrite a
     * file while it is mapped, so a scan that left the files it mapped to the collector would leave them locked
     * for as long as the JVM went without a collection -- which in a large heap can be minutes, or forever.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written, opened or read
     */
    // #939
    @Test
    public void closingASliceUnmapsTheFileBeforeItReturns(@TempDir final File tempDir) throws IOException {
        assumeTrue(Files.isReadable(PROC_SELF_MAPS), "only Linux can tell whether a file is still mapped");
        final NestedJarHandler nestedJarHandler = memoryMappingNestedJarHandler();
        final File file = writeFile(tempDir, "unmapped-when-closed.bin");
        final FileSlice slice = new FileSlice(file, nestedJarHandler, /* log = */ null);
        assertThat(slice.read().isDirect()).isTrue();
        assertThat(isMemoryMapped(file)).isTrue();

        slice.close();

        assertThat(isMemoryMapped(file)).isFalse();
        nestedJarHandler.close(/* log = */ null);
    }

    /**
     * A view of the mapping keeps the file mapped after the slice that mapped it has closed, and the file is
     * unmapped as soon as the view is released. Below JDK 22, unmapping a file frees the address range whether or
     * not anything is still reading it, so a buffer that was handed to a caller has to hold the mapping open --
     * reading it after the file was unmapped would read memory that is no longer there, and take a SIGSEGV that
     * kills the JVM. (From JDK 22 the file is unmapped by closing the arena that mapped it, which makes such a
     * read throw {@link IllegalStateException} instead, so the arena is closed as soon as the slice is.)
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written, opened or read
     */
    // #939
    @Test
    public void aViewOfTheMappingKeepsTheFileMappedUntilItIsReleased(@TempDir final File tempDir)
            throws IOException {
        assumeTrue(Files.isReadable(PROC_SELF_MAPS), "only Linux can tell whether a file is still mapped");
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION < 22, "from JDK 22 the arena is closed with the slice");
        final NestedJarHandler nestedJarHandler = memoryMappingNestedJarHandler();
        final File file = writeFile(tempDir, "held-open-by-a-view.bin");
        final FileSlice slice = new FileSlice(file, nestedJarHandler, /* log = */ null);
        final Runnable releaseMappingView = slice.acquireMappingView();
        final ByteBuffer byteBuffer = slice.read();

        slice.close();

        assertThat(isMemoryMapped(file)).isTrue();
        // The file is still mapped, so the buffer taken before the close is still readable
        assertThat(byteBuffer.get(0)).isEqualTo(CONTENT[0]);

        releaseMappingView.run();

        assertThat(isMemoryMapped(file)).isFalse();
        nestedJarHandler.close(/* log = */ null);
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

    /**
     * Register a file as a temporary file that the handler created, so that closing the handler deletes it. Only
     * {@link NestedJarHandler#makeTempFile(String, boolean)} creates temporary files, and it creates them in the
     * system temporary directory, whose permissions a test cannot change -- so the test below, which needs a
     * delete of a temporary file to fail, writes its own file somewhere it can refuse the delete, and records it
     * here.
     *
     * @param nestedJarHandler
     *            the handler to register the file with
     * @param tempFile
     *            the file to register
     * @throws ReflectiveOperationException
     *             if the set of temporary files could not be reached
     */
    @SuppressWarnings("unchecked")
    private static void registerAsTempFile(final NestedJarHandler nestedJarHandler, final File tempFile)
            throws ReflectiveOperationException {
        final Field tempFiles = NestedJarHandler.class.getDeclaredField("tempFiles");
        tempFiles.setAccessible(true);
        ((Set<File>) tempFiles.get(nestedJarHandler)).add(tempFile);
    }

    /**
     * A temporary file whose delete could not go ahead while the caller could still read a buffer of it is deleted
     * once the last view of its mapping is released. Below JDK 22, a file that a view holds open is still mapped
     * when the scan closes, and Windows refuses to delete a mapped file, so the delete has to wait for the unmap
     * rather than be given up on: no collection can unmap a file whose buffer the caller is holding, and leaving
     * the file to the {@link File#deleteOnExit()} hook leaves an extracted nested jar on disk for the rest of the
     * life of the JVM.
     *
     * <p>
     * On Windows the mapping itself is what refuses the delete, which is the case this test is about; everywhere
     * else the delete has to be refused some other way, so the test clears the write permission that POSIX
     * requires in order to unlink the file from its directory. The test skips itself if the delete succeeds
     * anyway, which is what happens when running as root.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written, opened or read
     * @throws ReflectiveOperationException
     *             if the file could not be registered as a temporary file
     */
    // #939
    @Test
    public void aTemporaryFileIsDeletedOnceTheLastViewOfItsMappingIsReleased(@TempDir final File tempDir)
            throws IOException, ReflectiveOperationException {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION < 22, "from JDK 22 the arena is closed with the slice");
        final NestedJarHandler nestedJarHandler = memoryMappingNestedJarHandler();
        final File extractedDir = new File(tempDir, "extracted");
        assertThat(extractedDir.mkdir()).isTrue();
        final File tempFile = writeFile(extractedDir, "extracted.jar");
        registerAsTempFile(nestedJarHandler, tempFile);
        final FileSlice slice = new FileSlice(tempFile, nestedJarHandler, /* log = */ null);
        assertThat(slice.read().isDirect()).isTrue();
        final Runnable releaseMappingView = slice.acquireMappingView();

        // (setWritable returns false on a Windows directory, where it is not needed, so it is called for its
        // effect rather than its result)
        extractedDir.setWritable(false);
        final boolean deleteWasRefused;
        try {
            nestedJarHandler.close(/* log = */ null);
            deleteWasRefused = tempFile.exists();
        } finally {
            extractedDir.setWritable(true);
        }

        // Releasing the last view unmaps the file, which is the moment the delete can be retried. This is run
        // before the assumption below, so that an abort leaves nothing mapped for the temporary directory
        // cleanup to trip over.
        releaseMappingView.run();

        assumeTrue(deleteWasRefused, "the delete succeeded while the file was still mapped");
        assertThat(tempFile.exists()).isFalse();
    }
}
