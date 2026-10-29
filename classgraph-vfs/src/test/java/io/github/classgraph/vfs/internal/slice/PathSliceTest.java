package io.github.classgraph.vfs.internal.slice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsSpec;

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
     * @return the vfs
     */
    private static Vfs vfs() {
        return new Vfs(new VfsSpec(), new InterruptionChecker());
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

    /**
     * Write a test file several times longer than {@link PathSlice#MIN_CHANNEL_READ_SIZE}, so that reading a range
     * of it can be long enough for a mapped file to read the range through a file channel.
     *
     * @param tempDir
     *            the temporary directory to write the file to
     * @return the content of the file
     * @throws IOException
     *             if the file could not be written
     */
    private static byte[] writeLargeTestFile(final Path tempDir) throws IOException {
        final var content = new byte[300_000];
        for (var i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31 + (i >> 8));
        }
        Files.write(tempDir.resolve("large.bin"), content);
        return content;
    }

    /**
     * A read of a range long enough to go through a file channel, where a file is mapped, returns the bytes of that
     * range, stops at the end of the slice, and reads nothing at or past the end, just as a read through the
     * mapping does.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the test file could not be written or read
     */
    @Test
    public void aLargeReadReturnsTheBytesOfItsRange(@TempDir final Path tempDir) throws IOException {
        final var content = writeLargeTestFile(tempDir);
        final var vfs = vfs();
        final var slice = new PathSlice(tempDir.resolve("large.bin"), vfs, /* log = */ null);
        try {
            assertThat(slice.load()).isEqualTo(content);
            final var subSlice = slice.slice(1234, 200_000);
            assertThat(subSlice.load()).isEqualTo(Arrays.copyOfRange(content, 1234, 201_234));
            try (var inputStream = subSlice.open()) {
                assertThat(inputStream.readAllBytes()).isEqualTo(Arrays.copyOfRange(content, 1234, 201_234));
            }

            final var reader = subSlice.randomAccessReader();
            final var dst = new byte[150_000];
            // Read into the middle of the array, from a range that runs past the end of the slice
            assertThat(reader.read(100_000, dst, 7, 140_000)).isEqualTo(100_000);
            assertThat(Arrays.copyOfRange(dst, 7, 100_007))
                    .isEqualTo(Arrays.copyOfRange(content, 101_234, 201_234));
            assertThat(dst[0]).isZero();
            assertThat(dst[100_007]).isZero();
            assertThat(reader.read(200_000, dst, 0, 100_000)).isEqualTo(-1);
        } finally {
            slice.close();
        }
    }

    /**
     * A sub-slice cannot make a large read after the toplevel slice was closed, where a file is mapped and the read
     * would go through a file channel, just as it cannot make a small one.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the test file could not be written or read
     */
    @Test
    public void aLargeReadFailsAfterTheToplevelSliceWasClosed(@TempDir final Path tempDir) throws IOException {
        writeLargeTestFile(tempDir);
        final var vfs = vfs();
        final var slice = new PathSlice(tempDir.resolve("large.bin"), vfs, /* log = */ null);
        final var subSlice = slice.slice(1000, 200_000);
        assertThat(subSlice.load()).hasSize(200_000);

        slice.close();

        assertThatThrownBy(subSlice::load).isInstanceOf(IOException.class);
    }

    /**
     * A large read by an interrupted thread fails, wherever it goes through a file channel, but leaves nothing
     * broken: once the thread's interrupt status is cleared it reads the file again, and reads by other threads are
     * not affected.
     *
     * @param tempDir
     *            a temporary directory
     * @throws Exception
     *             if the test file could not be written or read
     */
    @Test
    public void anInterruptedLargeReadDoesNotStopLaterReads(@TempDir final Path tempDir) throws Exception {
        final var content = writeLargeTestFile(tempDir);
        final var vfs = vfs();
        final var slice = new PathSlice(tempDir.resolve("large.bin"), vfs, /* log = */ null);
        try {
            assertThat(slice.load()).isEqualTo(content);

            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(slice::load).isInstanceOf(InterruptedIOException.class);
                // The interruption is not swallowed
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }

            assertThat(slice.load()).isEqualTo(content);
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(slice::load).get()).isEqualTo(content);
            }
        } finally {
            slice.close();
        }
    }

    /**
     * Once a slice has been closed, nothing still has its file open, even after large reads on many threads at
     * once, which on Windows each open a file channel of their own where the file is mapped. Windows does not let a
     * file that is open be deleted.
     *
     * @param tempDir
     *            a temporary directory
     * @throws Exception
     *             if the test file could not be written, read or deleted
     */
    @Test
    public void aFileCanBeDeletedAfterParallelLargeReadsAndClose(@TempDir final Path tempDir) throws Exception {
        final var content = writeLargeTestFile(tempDir);
        final var file = tempDir.resolve("large.bin");
        final var vfs = vfs();
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        final var numThreads = 8;
        try (ExecutorService executor = Executors.newFixedThreadPool(numThreads)) {
            final var start = new CountDownLatch(1);
            final var results = new ArrayList<Future<byte[]>>();
            for (var i = 0; i < numThreads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    byte[] loaded = null;
                    for (var j = 0; j < 20; j++) {
                        loaded = slice.load();
                    }
                    return loaded;
                }));
            }
            start.countDown();
            for (final var result : results) {
                assertThat(result.get()).isEqualTo(content);
            }
        }

        slice.close();

        Files.delete(file);
        assertThat(file).doesNotExist();
    }

    /**
     * A whole-file slice is memory-mapped, and reads back the content. Only on Windows does
     * {@link PathSlice#read()} return a view of the mapping, since only there can a mapped file not be truncated
     * under the caller.
     */
    @Test
    public void aWholeFileSliceIsMemoryMapped(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs();
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        try {
            assertThat(slice.randomAccessReader()).isInstanceOf(MappedFileReader.class);
            // A view of the mapping is a direct ByteBuffer, and a copy is a heap ByteBuffer
            assertThat(slice.read().isDirect()).isEqualTo(VersionFinder.OS == OperatingSystem.Windows);
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
        final var vfs = vfs();
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        try {
            final var subSlice = slice.slice(10, 5);
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
        final var vfs = vfs();
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        try {
            final var buf = slice.slice(10, 5).read();
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

    /** The buffer returned for a whole-file slice is read-only too. */
    @Test
    public void theBufferOfAWholeFileSliceIsReadOnly(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs();
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        try {
            final var buf = slice.read();
            assertThat(buf.isReadOnly()).isTrue();
            assertThat(buf.position()).isZero();
            assertThat(buf.capacity()).isEqualTo(CONTENT.length);
        } finally {
            slice.close();
        }
    }

    /**
     * A reader that was taken before the slice was closed holds a view of the memory mapping that closing the slice
     * releases, and every other reader of the {@link io.github.classgraph.vfs.Vfs} reports a read of released
     * storage as an {@link IOException}, so this one does too, rather than letting the arena's
     * {@link IllegalStateException} out.
     */
    @Test
    public void readingAMappedSliceAfterItWasClosedThrows(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs();
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        final var reader = slice.randomAccessReader();
        assertThat(reader.readByte(0)).isEqualTo(CONTENT[0]);

        slice.close();

        assertThatThrownBy(() -> reader.readByte(0)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing what it was read through");
        assertThatThrownBy(() -> reader.readInt(0)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing what it was read through");
        assertThatThrownBy(() -> reader.read(0, new byte[4], 0, 4)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped by closing what it was read through");
        // And a closed slice hands out no new reader at all, rather than one that throws on its first read
        assertThatThrownBy(slice::randomAccessReader).isInstanceOf(IOException.class)
                .hasMessageContaining("after it has been closed");
    }

    /**
     * A closed slice has released the file it was reading, so it reports that rather than throwing a
     * {@link NullPointerException} from the file channel it no longer has.
     */
    @Test
    public void anUnmappedSliceCannotBeReadAfterItWasClosed(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs();
        final var slice = PathSlice.forDirEntry(file, vfs);
        assertThat(slice.load()).isEqualTo(CONTENT);

        slice.close();

        assertThatThrownBy(slice::randomAccessReader).isInstanceOf(IOException.class)
                .hasMessageContaining("after it has been closed");
        assertThatThrownBy(slice::load).isInstanceOf(IOException.class)
                .hasMessageContaining("after it has been closed");
        assertThatThrownBy(slice::read).isInstanceOf(IOException.class)
                .hasMessageContaining("after it has been closed");
    }

    /**
     * A file that is truncated while it is memory-mapped is reported as truncated by an {@link IOException}, rather
     * than by the {@link InternalError} that the JVM throws for a read of a mapped page that is no longer in the
     * file, even once the read has been JIT-compiled. (Windows does not allow a mapped file to be truncated.)
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the test file could not be written or truncated
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void readingAMappedFileThatWasTruncatedThrowsAnIOException(@TempDir final Path tempDir)
            throws IOException {
        final var content = writeLargeTestFile(tempDir);
        final var file = tempDir.resolve("large.bin");
        final var vfs = vfs();
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        try {
            final var reader = slice.randomAccessReader();
            assertThat(reader).isInstanceOf(MappedFileReader.class);
            assertThat(reader.readByte(200_000)).isEqualTo(content[200_000]);
            // Make each kind of read often enough that it is JIT-compiled, since a fault in a compiled single-value
            // read or bulk copy is thrown after the read returns, and a fault in a compiled bulk copy of more than
            // 64 bytes kills the JVM on aarch64
            final var buf = new byte[8192];
            final var smallBuf = new byte[100];
            final var heapBuf = ByteBuffer.allocate(100);
            final var directBuf = ByteBuffer.allocateDirect(8192);
            var sum = 0L;
            for (var i = 0; i < 100_000; i++) {
                final var off = (i * 64L) & 0xFFFF;
                assertThat(reader.read(off, buf, 0, buf.length)).isEqualTo(buf.length);
                assertThat(reader.read(off, smallBuf, 0, smallBuf.length)).isEqualTo(smallBuf.length);
                assertThat(reader.read(off, smallBuf, 0, 8)).isEqualTo(8);
                assertThat(reader.read(off, heapBuf, 0, 100)).isEqualTo(100);
                assertThat(reader.read(off, directBuf, 0, 8192)).isEqualTo(8192);
                sum += reader.readByte(off) + reader.readShort(off) + reader.readInt(off) + reader.readLong(off);
            }
            assertThat(sum).isNotZero();

            try (var raf = new RandomAccessFile(file.toFile(), "rw")) {
                raf.setLength(4096);
            }

            // Each kind of read through the mapping, beyond the new end of the file
            assertThatThrownBy(() -> reader.readByte(200_000)).isInstanceOf(IOException.class)
                    .hasMessageContaining("truncated");
            assertThatThrownBy(() -> reader.readShort(200_000)).isInstanceOf(IOException.class)
                    .hasMessageContaining("truncated");
            assertThatThrownBy(() -> reader.readInt(200_000)).isInstanceOf(IOException.class)
                    .hasMessageContaining("truncated");
            assertThatThrownBy(() -> reader.readLong(200_000)).isInstanceOf(IOException.class)
                    .hasMessageContaining("truncated");
            assertThatThrownBy(() -> reader.read(200_000, buf, 0, buf.length)).isInstanceOf(IOException.class)
                    .hasMessageContaining("truncated");
            assertThatThrownBy(() -> reader.read(200_000, smallBuf, 0, 100)).isInstanceOf(IOException.class)
                    .hasMessageContaining("truncated");
            assertThatThrownBy(() -> reader.read(200_000, smallBuf, 0, 8)).isInstanceOf(IOException.class)
                    .hasMessageContaining("truncated");
            assertThatThrownBy(() -> reader.read(200_000, ByteBuffer.allocate(100), 0, 100))
                    .isInstanceOf(IOException.class).hasMessageContaining("truncated");
            assertThatThrownBy(() -> reader.read(200_000, directBuf, 0, 8192)).isInstanceOf(IOException.class)
                    .hasMessageContaining("truncated");
            // A large read, which goes through a file channel
            assertThatThrownBy(slice::load).isInstanceOf(IOException.class).hasMessageContaining("truncated");
            // And the buffer that read() returns is a copy, not a view that would fault when the caller read it
            final var subSlice = slice.slice(200_000, 100);
            assertThatThrownBy(subSlice::read).isInstanceOf(IOException.class).hasMessageContaining("truncated");
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
        final var vfs = vfs();
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        final var subSlice = slice.slice(10, 5);
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
     * alive, since the close unmaps the file, and a mapped buffer that outlives the mapping can no longer be read.
     * The mapping is deliberately reachable from nothing the API hands out, so the only way to watch it is by
     * reflection.
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
        final var vfs = vfs();
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        final var subSlice = slice.slice(10, 5);
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
     * A stream that has been closed holds nothing of the memory mapping of the file it was reading, even while the
     * stream itself is still alive. A stream reads through a reader, and a reader of a mapped file holds a
     * duplicate of the mapped buffer, so a closed stream that still held its reader would be holding a view of the
     * address range that closing the slice freed. A stream handed out through the
     * {@link io.github.classgraph.vfs.Vfs} API can be kept by the caller for as long as it likes, so what a closed
     * one still refers to is worth checking.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the test file could not be written or read
     * @throws ReflectiveOperationException
     *             if the mapped buffer could not be read out of the slice
     */
    // #939
    @Test
    public void closingAStreamReleasesTheViewItHeldOfTheMapping(@TempDir final Path tempDir)
            throws IOException, ReflectiveOperationException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs();
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        final var inputStream = slice.open();
        assertThat(inputStream.read()).isEqualTo(CONTENT[0]);

        final var backingByteBuffer = PathSlice.class.getDeclaredField("backingByteBuffer");
        backingByteBuffer.setAccessible(true);
        final var mapping = new WeakReference<>(backingByteBuffer.get(slice));
        assertThat(mapping.refersTo(null)).isFalse();

        inputStream.close();
        slice.close();

        assertThat(collect(mapping)).isTrue();

        // The stream is used after the check above, which is what keeps it strongly reachable across it: the point
        // of the check is that a closed stream is not what holds the mapping alive
        assertThatThrownBy(inputStream::read).isInstanceOf(IOException.class);
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
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ref.refersTo(null);
    }

    /**
     * A file that a slice memory-mapped can be overwritten and deleted once the slice has been closed, since
     * closing the slice unmaps it. Windows refuses to overwrite a file that is still mapped, and some versions of
     * Windows refuse to delete one. (Every other operating system allows both, so this test only bites on Windows.)
     */
    // #939
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void aMappedFileCanBeOverwrittenAndDeletedOnceTheSliceIsClosed(@TempDir final Path tempDir)
            throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs();
        try {
            final var slice = new PathSlice(file, vfs, /* log = */ null);
            assertThat(slice.read().isDirect()).isTrue();
            // Closing the slice unmaps the file. Closing the vfs would not, since a slice that no root owns belongs
            // to whoever opened it
            slice.close();
        } finally {
            vfs.close(/* log = */ null);
        }

        writeTestFile(tempDir);
        Files.delete(file);
        assertThat(Files.exists(file)).isFalse();
    }

    /**
     * A slice of a single resource file is not memory-mapped, even where files are mapped, since mapping and
     * unmapping a file that is read once and then closed costs more than reading it.
     */
    @Test
    public void aResourceSliceIsNotMemoryMapped(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir);
        final var vfs = vfs();
        final var slice = PathSlice.forDirEntry(file, vfs);
        try {
            assertThat(slice.read().isDirect()).isFalse();
            assertThat(slice.load()).isEqualTo(CONTENT);
        } finally {
            slice.close();
        }
    }

    /** A slice can be opened from a {@link File} as well as from a {@link Path}, and reads the same content. */
    @Test
    public void aSliceCanBeOpenedFromAFile(@TempDir final Path tempDir) throws IOException {
        final var file = writeTestFile(tempDir).toFile();
        final var vfs = vfs();
        final var slice = new PathSlice(file, vfs, /* log = */ null);
        try {
            assertThat(slice.getFile()).isEqualTo(file);
            assertThat(slice.load()).isEqualTo(CONTENT);
        } finally {
            slice.close();
        }
    }

    /**
     * A file whose path cannot be represented as a {@link Path} is still opened, through the {@link File} API. The
     * only such file that can be created is an NTFS alternate data stream, whose name contains a ':', which
     * {@link Path} rejects on Windows.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the file could not be written or opened
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void aFileThatIsNotAValidPathIsOpenedThroughTheFileApi(@TempDir final Path tempDir) throws IOException {
        // Write the content to an alternate data stream of a host file
        final var hostFile = tempDir.resolve("host.dat");
        Files.write(hostFile, new byte[0]);
        final var streamFile = new File(hostFile + ":content.bin");
        try (var raf = new RandomAccessFile(streamFile, "rw")) {
            raf.write(CONTENT);
        }
        // The stream's name is not a valid Path, which is what sends PathSlice down the File API
        assertThatThrownBy(streamFile::toPath).isInstanceOf(InvalidPathException.class);

        final var vfs = vfs();
        final var slice = new PathSlice(streamFile, vfs, /* log = */ null);
        try {
            assertThat(slice.getFile()).isEqualTo(streamFile);
            assertThat(slice.load()).isEqualTo(CONTENT);
        } finally {
            slice.close();
        }
    }

    /**
     * Closing a slice over a temporary file needs no ClassGraph class that was not already loaded when the slice
     * was opened, even when the temporary file cannot be deleted at the first attempt. A slice may be closed from a
     * shutdown hook or a container's teardown code, after the classloader that loaded ClassGraph has been closed,
     * and that classloader can then no longer load anything.
     *
     * @throws Exception
     *             if the slice could not be opened or closed.
     */
    // #331
    @Test
    public void closingATempFileSliceNeedsNoClassThatWasNotLoadedWhenItWasOpened() throws Exception {
        final var classgraphLocations = new URL[] { Slice.class.getProtectionDomain().getCodeSource().getLocation(),
                InterruptionChecker.class.getProtectionDomain().getCodeSource().getLocation() };
        final AutoCloseable slice;
        try (var classLoader = new URLClassLoader(classgraphLocations, ClassLoader.getPlatformClassLoader())) {
            final var vfsSpecClass = classLoader.loadClass(VfsSpec.class.getName());
            final var vfsSpec = vfsSpecClass.getConstructor().newInstance();
            vfsSpecClass.getMethod("setMaxBufferedJarRAMSize", int.class).invoke(vfsSpec, 4);
            final var interruptionCheckerClass = classLoader.loadClass(InterruptionChecker.class.getName());
            final var vfsClass = classLoader.loadClass(Vfs.class.getName());
            final var vfs = vfsClass.getConstructor(vfsSpecClass, interruptionCheckerClass).newInstance(vfsSpec,
                    interruptionCheckerClass.getConstructor().newInstance());
            final var sliceClass = classLoader.loadClass(Slice.class.getName());
            // Longer than maxBufferedJarRAMSize, so the stream is spilled to a temporary file
            slice = (AutoCloseable) sliceClass
                    .getMethod("fromInputStream", InputStream.class, String.class, long.class, vfsClass,
                            classLoader.loadClass(LogNode.class.getName()))
                    .invoke(null, new ByteArrayInputStream(CONTENT), "test.jar", -1L, vfs, null);
            final var tempFile = (File) slice.getClass().getMethod("getFile").invoke(slice);
            // Delete the temporary file out from under the slice, so that the slice's own attempt to delete it
            // fails, and the slice has to try to free whatever is keeping the file open
            try {
                Files.delete(tempFile.toPath());
            } catch (final FileSystemException _) {
                // Windows does not let a file be deleted while the slice has it memory-mapped. Closing the slice
                // then unmaps the file and deletes it, which must not need a class that was not loaded either
                assertThat(OS.current()).isEqualTo(OS.WINDOWS);
            }
        }
        // The classloader has been closed, so a class that the slice did not load before now cannot be loaded
        slice.close();
    }
}
