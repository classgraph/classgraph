package nonapi.io.github.classgraph.fileslice.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link RandomAccessFileChannelReader}. */
public class RandomAccessFileChannelReaderTest {
    /**
     * A {@link FileChannel} that never transfers more than one byte per read, which a real {@link FileChannel} is
     * also permitted to do (a read from a network filesystem can be short). Every method other than the positional
     * read delegates to the wrapped channel, or is unsupported if the reader never calls it.
     */
    private static class ShortReadFileChannel extends FileChannel {
        private final FileChannel wrapped;

        ShortReadFileChannel(final FileChannel wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public int read(final ByteBuffer dst, final long position) throws IOException {
            if (!dst.hasRemaining()) {
                return 0;
            }
            // Read a single byte, however many were asked for
            final Buffer dstBuffer = dst;
            final int oldLimit = dstBuffer.limit();
            dstBuffer.limit(dstBuffer.position() + 1);
            try {
                return wrapped.read(dst, position);
            } finally {
                dstBuffer.limit(oldLimit);
            }
        }

        @Override
        public long size() throws IOException {
            return wrapped.size();
        }

        @Override
        protected void implCloseChannel() throws IOException {
            wrapped.close();
        }

        @Override
        public int read(final ByteBuffer dst) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long read(final ByteBuffer[] dsts, final int offset, final int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int write(final ByteBuffer src) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long write(final ByteBuffer[] srcs, final int offset, final int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int write(final ByteBuffer src, final long position) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileChannel position(final long newPosition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileChannel truncate(final long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void force(final boolean metaData) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferTo(final long position, final long count, final WritableByteChannel target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferFrom(final ReadableByteChannel src, final long position, final long count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MappedByteBuffer map(final MapMode mode, final long position, final long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock lock(final long position, final long size, final boolean shared) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock tryLock(final long position, final long size, final boolean shared) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A {@link ShortReadFileChannel} that is closed during its second read, by another thread, or by the
     * interruption of the thread that reads it.
     */
    private static final class ClosedDuringSecondReadFileChannel extends ShortReadFileChannel {
        /** True if the thread that reads the channel is interrupted, false if another thread closes it. */
        private final boolean byInterruption;

        /** The number of reads so far. */
        private int numReads;

        ClosedDuringSecondReadFileChannel(final FileChannel wrapped, final boolean byInterruption) {
            super(wrapped);
            this.byInterruption = byInterruption;
        }

        @Override
        public int read(final ByteBuffer dst, final long position) throws IOException {
            if (++numReads == 2) {
                close();
                throw byInterruption ? new ClosedByInterruptException() : new AsynchronousCloseException();
            }
            return super.read(dst, position);
        }
    }

    /**
     * A {@link RandomAccessFileChannelReader.FileChannelSource} that returns the first channel until it is closed,
     * and the second channel after that.
     *
     * @param first
     *            the first channel
     * @param second
     *            the second channel
     * @return the source
     */
    private static RandomAccessFileChannelReader.FileChannelSource firstUntilClosed(final FileChannel first,
            final FileChannel second) {
        return new RandomAccessFileChannelReader.FileChannelSource() {
            @Override
            public FileChannel fileChannel() {
                return first.isOpen() ? first : second;
            }
        };
    }

    /** Write a file of the given length, where the byte at index i is i. */
    private static Path writeTestFile(final Path dir, final int numBytes) throws IOException {
        final byte[] content = new byte[numBytes];
        for (int i = 0; i < numBytes; i++) {
            content[i] = (byte) i;
        }
        final Path file = dir.resolve("test.bin");
        Files.write(file, content);
        return file;
    }

    /**
     * A short read must be completed by reading again, rather than being reported to the caller as a truncated
     * file. Every multi-byte read in {@link RandomAccessFileChannelReader} treats a short read as a premature EOF,
     * so a channel that returns fewer bytes than were asked for must not be taken at its word.
     */
    @Test
    public void shortReadsAreCompleted(@TempDir final Path tmpDir) throws IOException {
        final Path file = writeTestFile(tmpDir, 64);
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ);
                ShortReadFileChannel shortReadChannel = new ShortReadFileChannel(fileChannel)) {
            final RandomAccessFileChannelReader reader = new RandomAccessFileChannelReader(shortReadChannel, 0L,
                    64L);

            // Little endian, so the long at offset 8 is bytes 15..8 from most to least significant
            assertThat(reader.readLong(8)).isEqualTo(0x0f0e0d0c0b0a0908L);
            assertThat(reader.readInt(4)).isEqualTo(0x07060504);
            assertThat(reader.readUnsignedShort(2)).isEqualTo(0x0302);
            assertThat(reader.readUnsignedByte(1)).isEqualTo(1);

            final byte[] arr = new byte[64];
            assertThat(reader.read(0, arr, 0, 64)).isEqualTo(64);
            for (int i = 0; i < 64; i++) {
                assertThat(arr[i]).isEqualTo((byte) i);
            }
        }
    }

    /**
     * The destination buffer can have less room left than the caller asked for. The array-backed and
     * ByteBuffer-backed readers read only as much as there is room for, so this reader has to as well, rather than
     * letting {@link java.nio.ByteBuffer#limit(int)} throw {@link IllegalArgumentException}.
     */
    @Test
    public void readIsClampedToTheSpaceLeftInTheDestinationBuffer(@TempDir final Path tmpDir) throws IOException {
        final Path file = writeTestFile(tmpDir, 64);
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            final RandomAccessFileChannelReader reader = new RandomAccessFileChannelReader(fileChannel, 0L, 64L);
            final ByteBuffer dstBuf = ByteBuffer.allocate(8);

            // Only 4 of the 64 requested bytes fit after position 4
            assertThat(reader.read(0, dstBuf, 4, 64)).isEqualTo(4);
            assertThat(dstBuf.get(4)).isEqualTo((byte) 0);
            assertThat(dstBuf.get(7)).isEqualTo((byte) 3);

            // No room left at all
            assertThat(reader.read(0, dstBuf, 8, 64)).isEqualTo(-1);
        }
    }

    /**
     * The {@link java.nio.ByteBuffer} that wraps the destination array is reused between calls, so its limit is
     * left where the previous read stopped. A second read that starts further into the same array than the previous
     * read ended must still work: positioning past a stale limit threw {@link IllegalArgumentException}, which is
     * not one of the exceptions that {@link java.io.InputStream#read(byte[], int, int)} is allowed to throw for a
     * valid range.
     *
     * @param tmpDir
     *            a temporary directory to write the file to read
     * @throws IOException
     *             if the file could not be written or read
     */
    @Test
    public void aLaterReadCanStartPastWherePreviousReadEnded(@TempDir final Path tmpDir) throws IOException {
        final Path file = writeTestFile(tmpDir, 64);
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            final RandomAccessFileChannelReader reader = new RandomAccessFileChannelReader(fileChannel, 0L, 64L);
            final byte[] arr = new byte[16];

            assertThat(reader.read(0, arr, 0, 4)).isEqualTo(4);
            // Leave a gap in the destination array, so this read starts past where the previous read ended
            assertThat(reader.read(8, arr, 8, 4)).isEqualTo(4);
            assertThat(arr[8]).isEqualTo((byte) 8);
            assertThat(arr[11]).isEqualTo((byte) 11);
            // The bytes in the gap were not written
            assertThat(arr[4]).isEqualTo((byte) 0);
        }
    }

    /**
     * A read that another thread closes the channel during asks the source for the channel again, and carries on
     * from where it stopped, rather than failing because of something that happened to another thread.
     */
    @Test
    public void aReadCarriesOnWithTheNextChannelWhenAnotherThreadClosesTheChannel(@TempDir final Path tmpDir)
            throws IOException {
        final Path file = writeTestFile(tmpDir, 64);
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ);
                ClosedDuringSecondReadFileChannel closingChannel = new ClosedDuringSecondReadFileChannel(
                        fileChannel, false);
                FileChannel nextChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            final RandomAccessFileChannelReader reader = new RandomAccessFileChannelReader(
                    firstUntilClosed(closingChannel, nextChannel), 0L, 64L);
            final byte[] arr = new byte[64];
            assertThat(reader.read(0, arr, 0, 64)).isEqualTo(64);
            for (int i = 0; i < 64; i++) {
                assertThat(arr[i]).isEqualTo((byte) i);
            }
            assertThat(closingChannel.isOpen()).isFalse();
        }
    }

    /**
     * A read that is stopped by the interruption of the thread doing the read stops, rather than carrying on with
     * the next channel, since the interruption is a reason to stop.
     */
    @Test
    public void aReadStopsWhenTheThreadReadingIsInterrupted(@TempDir final Path tmpDir) throws IOException {
        final Path file = writeTestFile(tmpDir, 64);
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ);
                ClosedDuringSecondReadFileChannel closingChannel = new ClosedDuringSecondReadFileChannel(
                        fileChannel, true);
                FileChannel nextChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            final RandomAccessFileChannelReader reader = new RandomAccessFileChannelReader(
                    firstUntilClosed(closingChannel, nextChannel), 0L, 64L);
            assertThatThrownBy(new ThrowingCallable() {
                @Override
                public void call() throws Throwable {
                    reader.read(0, new byte[64], 0, 64);
                }
            }).isInstanceOf(ClosedByInterruptException.class);
        }
    }

    /**
     * A read from a channel that was given to the reader, rather than got from a source, fails when another thread
     * closes the channel, since there is no other channel to carry on with.
     */
    @Test
    public void aReadOfAGivenChannelFailsWhenAnotherThreadClosesTheChannel(@TempDir final Path tmpDir)
            throws IOException {
        final Path file = writeTestFile(tmpDir, 64);
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ);
                ClosedDuringSecondReadFileChannel closingChannel = new ClosedDuringSecondReadFileChannel(
                        fileChannel, false)) {
            final RandomAccessFileChannelReader reader = new RandomAccessFileChannelReader(closingChannel, 0L,
                    64L);
            assertThatThrownBy(new ThrowingCallable() {
                @Override
                public void call() throws Throwable {
                    reader.read(0, new byte[64], 0, 64);
                }
            }).isInstanceOf(AsynchronousCloseException.class);
        }
    }

    /**
     * A read that runs off the end of a file that is shorter than its slice, which means the file was truncated
     * after the slice was made, throws, rather than returning a short count or -1, which a caller that streams the
     * content would take to be the end of the content.
     */
    @Test
    public void readPastEndOfFileWithinTheSliceThrows(@TempDir final Path tmpDir) throws IOException {
        final Path file = writeTestFile(tmpDir, 8);
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ);
                ShortReadFileChannel shortReadChannel = new ShortReadFileChannel(fileChannel)) {
            // Claim the slice is longer than the file actually is, so that the bounds check passes but the read
            // runs off the end of the file
            final RandomAccessFileChannelReader reader = new RandomAccessFileChannelReader(shortReadChannel, 0L,
                    16L);
            final byte[] arr = new byte[16];
            assertThatThrownBy(new ThrowingCallable() {
                @Override
                public void call() throws Throwable {
                    reader.read(0, arr, 0, 16);
                }
            }).isInstanceOf(IOException.class).hasMessageContaining("truncated");
            assertThatThrownBy(new ThrowingCallable() {
                @Override
                public void call() throws Throwable {
                    reader.read(12, arr, 0, 4);
                }
            }).isInstanceOf(IOException.class).hasMessageContaining("truncated");
        }
    }

    /** A {@link ShortReadFileChannel} that reads all it is asked for, and records the largest read asked for. */
    private static final class LargestReadFileChannel extends ShortReadFileChannel {
        /** The largest number of bytes that one read has asked for. */
        private int largestRead;

        LargestReadFileChannel(final FileChannel wrapped) {
            super(wrapped);
        }

        @Override
        public int read(final ByteBuffer dst, final long position) throws IOException {
            largestRead = Math.max(largestRead, dst.remaining());
            return ((ShortReadFileChannel) this).wrapped.read(dst, position);
        }
    }

    /**
     * A read into a heap buffer is made in chunks of at most
     * {@link RandomAccessFileChannelReader#MAX_HEAP_READ_SIZE} bytes, and still fills the whole of the range asked
     * for. A read into a direct buffer is made in one read.
     */
    @Test
    public void aLargeReadIntoAHeapBufferIsMadeInChunks(@TempDir final Path tmpDir) throws IOException {
        final int fileLength = 3 * RandomAccessFileChannelReader.MAX_HEAP_READ_SIZE + 5;
        final Path file = writeTestFile(tmpDir, fileLength);
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ);
                LargestReadFileChannel largestReadChannel = new LargestReadFileChannel(fileChannel)) {
            final RandomAccessFileChannelReader reader = new RandomAccessFileChannelReader(largestReadChannel, 0L,
                    fileLength);
            final byte[] arr = new byte[fileLength + 1];
            assertThat(reader.read(2, arr, 3, fileLength - 2)).isEqualTo(fileLength - 2);
            for (int i = 2; i < fileLength; i++) {
                assertThat(arr[i + 1]).isEqualTo((byte) i);
            }
            assertThat(largestReadChannel.largestRead).isEqualTo(RandomAccessFileChannelReader.MAX_HEAP_READ_SIZE);

            // The buffer is left positioned at the end of the read, with its limit there, as a single read leaves it
            final ByteBuffer heapBuf = ByteBuffer.allocate(fileLength + 9);
            assertThat(reader.read(0, heapBuf, 0, fileLength)).isEqualTo(fileLength);
            assertThat(heapBuf.position()).isEqualTo(fileLength);
            assertThat(heapBuf.limit()).isEqualTo(fileLength);
            assertThat(heapBuf.get(fileLength - 1)).isEqualTo((byte) (fileLength - 1));

            final ByteBuffer directBuf = ByteBuffer.allocateDirect(fileLength);
            assertThat(reader.read(0, directBuf, 0, fileLength)).isEqualTo(fileLength);
            assertThat(largestReadChannel.largestRead).isEqualTo(fileLength);
            assertThat(directBuf.get(fileLength - 1)).isEqualTo((byte) (fileLength - 1));
        }
    }
}
