package nonapi.io.github.classgraph.fileslice.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link RandomAccessFileChannelReader}. */
public class RandomAccessFileChannelReaderTest {
    /**
     * A {@link FileChannel} that never transfers more than one byte per read, which a real {@link FileChannel} is
     * also permitted to do (a read from a network filesystem can be short). Every method other than the positional
     * read delegates to the wrapped channel, or is unsupported if the reader never calls it.
     */
    private static final class ShortReadFileChannel extends FileChannel {
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

    /** Reading up to the end of the file must still stop at the end of the file. */
    @Test
    public void readPastEndOfFileStopsAtEndOfFile(@TempDir final Path tmpDir) throws IOException {
        final Path file = writeTestFile(tmpDir, 8);
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ);
                ShortReadFileChannel shortReadChannel = new ShortReadFileChannel(fileChannel)) {
            // Claim the slice is longer than the file actually is, so that the bounds check passes but the read
            // runs off the end of the file
            final RandomAccessFileChannelReader reader = new RandomAccessFileChannelReader(shortReadChannel, 0L,
                    16L);
            final byte[] arr = new byte[16];
            assertThat(reader.read(0, arr, 0, 16)).isEqualTo(8);
        }
    }
}
