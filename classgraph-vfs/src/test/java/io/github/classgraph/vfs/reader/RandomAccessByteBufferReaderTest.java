package io.github.classgraph.vfs.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.base.internal.utils.VersionFinder;

/**
 * Tests {@link RandomAccessByteBufferReader}, which is the reader used for memory-mapped files, against
 * {@link RandomAccessArrayReader}, which reads the same little-endian layout from a byte array.
 */
public class RandomAccessByteBufferReaderTest {
    /**
     * A little-endian unsigned short must be read in full, not truncated to its low byte.
     */
    @Test
    public void readUnsignedShortReadsBothBytes() throws IOException {
        // 0x1234 in little-endian order
        final byte[] arr = { (byte) 0x34, (byte) 0x12 };
        final var reader = new RandomAccessByteBufferReader(ByteBuffer.wrap(arr), 0L, arr.length);
        assertThat(reader.readUnsignedShort(0)).isEqualTo(0x1234);
        // Must agree with the byte-array reader, which reads the same layout
        assertThat(reader.readUnsignedShort(0))
                .isEqualTo(new RandomAccessArrayReader(arr, 0, arr.length).readUnsignedShort(0));
    }

    /** A signed short must sign-extend the full 16-bit value. */
    @Test
    public void readShortReadsBothBytes() throws IOException {
        // 0xFF80 in little-endian order == -128 as a signed short
        final byte[] arr = { (byte) 0x80, (byte) 0xFF };
        final var reader = new RandomAccessByteBufferReader(ByteBuffer.wrap(arr), 0L, arr.length);
        assertThat(reader.readShort(0)).isEqualTo((short) -128);
        assertThat(reader.readShort(0)).isEqualTo(new RandomAccessArrayReader(arr, 0, arr.length).readShort(0));
    }

    /**
     * Reading a short at a nonzero offset within a slice must respect the slice start position.
     */
    @Test
    public void readUnsignedShortWithinASlice() throws IOException {
        final byte[] arr = { (byte) 0xAA, (byte) 0xBB, (byte) 0x34, (byte) 0x12 };
        // Slice covering just the last two bytes
        final var reader = new RandomAccessByteBufferReader(ByteBuffer.wrap(arr), 2L, 2L);
        assertThat(reader.readUnsignedShort(0)).isEqualTo(0x1234);
    }

    /**
     * Reading a string at a nonzero slice offset must return the bytes at that offset, not fail or return junk.
     */
    @Test
    public void readStringAtNonZeroOffset() throws IOException {
        final var prefix = "PREFIX".getBytes(StandardCharsets.UTF_8);
        final var str = "ClassGraph".getBytes(StandardCharsets.UTF_8);
        final var arr = new byte[prefix.length + str.length];
        System.arraycopy(prefix, 0, arr, 0, prefix.length);
        System.arraycopy(str, 0, arr, prefix.length, str.length);

        // Read via a slice that starts at the string
        final var sliceReader = new RandomAccessByteBufferReader(ByteBuffer.wrap(arr), prefix.length, str.length);
        assertThat(sliceReader.readString(0, str.length)).isEqualTo("ClassGraph");

        // Read via a whole-array reader, using an offset to reach the string
        final var wholeReader = new RandomAccessByteBufferReader(ByteBuffer.wrap(arr), 0L, arr.length);
        assertThat(wholeReader.readString(prefix.length, str.length)).isEqualTo("ClassGraph");
        // Must agree with the byte-array reader
        assertThat(new RandomAccessArrayReader(arr, 0, arr.length).readString(prefix.length, str.length))
                .isEqualTo("ClassGraph");
    }

    /**
     * Fill a buffer with the low byte of each index.
     *
     * @param capacity
     *            the capacity of the buffer.
     * @return the buffer, with its position at zero and its limit at its capacity.
     */
    private static ByteBuffer indexBytes(final int capacity) {
        final var buf = ByteBuffer.allocate(capacity);
        for (var i = 0; i < capacity; i++) {
            buf.put(i, (byte) i);
        }
        return buf;
    }

    /**
     * The source buffer's limit is ignored, as documented, so a slice that lies within the buffer's capacity but
     * starts past its limit can be read.
     */
    @Test
    public void sliceStartingPastTheSourceLimitCanBeRead() throws IOException {
        final var buf = indexBytes(100);
        buf.limit(10);
        final var reader = new RandomAccessByteBufferReader(buf, 50, 10);
        assertThat(reader.readByte(0)).isEqualTo((byte) 50);
        assertThat(reader.readByte(9)).isEqualTo((byte) 59);
        // The caller's buffer is not changed
        assertThat(buf.position()).isZero();
        assertThat(buf.limit()).isEqualTo(10);
    }

    /** A buffer that was flipped after a short write can still be sliced past the end of what was written. */
    @Test
    public void flippedSourceCanBeSlicedPastItsLimit() throws IOException {
        final var buf = indexBytes(100);
        buf.position(12);
        buf.flip();
        final var reader = new RandomAccessByteBufferReader(buf, 40, 8);
        final var dst = new byte[8];
        assertThat(reader.read(0, dst, 0, 8)).isEqualTo(8);
        assertThat(dst).containsExactly(40, 41, 42, 43, 44, 45, 46, 47);
    }

    /** A source buffer with a limit of zero can be sliced at a nonzero start. */
    @Test
    public void sourceWithZeroLimitCanBeSlicedAtANonzeroStart() throws IOException {
        final var buf = indexBytes(16);
        buf.limit(0);
        final var reader = new RandomAccessByteBufferReader(buf, 1, 15);
        assertThat(reader.length()).isEqualTo(15);
        assertThat(reader.readByte(14)).isEqualTo((byte) 15);
    }

    /** A slice outside the buffer's capacity is still rejected, with the documented exception. */
    @Test
    public void sliceOutsideTheCapacityIsRejected() {
        final var buf = indexBytes(100);
        assertThatThrownBy(() -> new RandomAccessByteBufferReader(buf, 95, 10))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    /**
     * A bulk read into a destination buffer whose memory has been freed reports that, rather than saying that the
     * file being read was unmapped. Only from JDK 22, where the destination can be allocated from an arena and
     * freed by closing it. The arena is reached by reflection, since {@code java.lang.foreign.Arena} is not in JDK
     * 17.
     */
    @Test
    public void bulkReadIntoAFreedDestinationNamesTheDestination() throws Exception {
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 22, "below JDK 22 there is no arena to free memory with");
        final var arenaClass = Class.forName("java.lang.foreign.Arena");
        final var reader = new RandomAccessByteBufferReader(indexBytes(16), 0, 16);
        final ByteBuffer dst;
        try (var arena = (AutoCloseable) arenaClass.getMethod("ofShared").invoke(null)) {
            final var segment = arenaClass.getMethod("allocate", long.class).invoke(arena, 16L);
            dst = (ByteBuffer) Class.forName("java.lang.foreign.MemorySegment").getMethod("asByteBuffer")
                    .invoke(segment);
            assertThat(reader.read(0, dst, 0, 16)).isEqualTo(16);
        }
        assertThatThrownBy(() -> reader.read(0, dst, 0, 16)).isInstanceOf(IOException.class)
                .hasMessageContaining("freed").hasCauseInstanceOf(IllegalStateException.class);
    }

    /**
     * A reader given a release check reports a read after the release as an {@link IOException}, from a single-byte
     * read and from both bulk reads. A zero-byte bulk read copies nothing, so it still returns 0.
     */
    @Test
    public void readAfterReleaseThrows() throws IOException {
        final var released = new AtomicBoolean();
        final var reader = new RandomAccessByteBufferReader(indexBytes(16), 0, 16, ByteOrder.LITTLE_ENDIAN,
                released::get);
        assertThat(reader.readInt(0)).isEqualTo(0x03020100);
        released.set(true);
        assertThatThrownBy(() -> reader.readByte(0)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped");
        assertThatThrownBy(() -> reader.read(0, new byte[4], 0, 4)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped");
        assertThatThrownBy(() -> reader.read(0, ByteBuffer.allocate(4), 0, 4)).isInstanceOf(IOException.class)
                .hasMessageContaining("unmapped");
        assertThat(reader.read(0, new byte[4], 0, 0)).isZero();
    }

    /** The read lengths of the truncation tests: each side of the 64- and 80-byte aarch64 limits, and of 256. */
    private static final int[] TRUNCATED_READ_LENGTHS = { 1, 8, 30, 64, 65, 80, 81, 100, 255, 256, 257, 8192,
            1 << 20 };

    /**
     * Every read of a page of a mapped file that was truncated away throws {@link IOException} from inside the
     * read, in interpreted code, and the JVM goes on running. Windows refuses to truncate a file that is mapped.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void readOfTruncatedMappingThrowsInsideTheRead(@TempDir final Path tempDir) throws IOException {
        readTruncatedMapping(tempDir, false);
    }

    /**
     * As {@link #readOfTruncatedMappingThrowsInsideTheRead}, after every read method has been called enough times
     * to be compiled. A compiled copy can deliver the error after the read returns, or, on aarch64, crash the JVM.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void compiledReadOfTruncatedMappingThrowsInsideTheRead(@TempDir final Path tempDir) throws IOException {
        readTruncatedMapping(tempDir, true);
    }

    private static void readTruncatedMapping(final Path tempDir, final boolean warm) throws IOException {
        // Only from JDK 25 is a file mapped where it can be truncated, which is anywhere but Windows
        assumeTrue(VersionFinder.JAVA_MAJOR_VERSION >= 25, "below JDK 25 files are mapped on Windows only");
        final var file = tempDir.resolve("mapped.bin");
        final int fileSize = 8 << 20;
        Files.write(file, new byte[fileSize]);
        // The mapping is released by the garbage collector, since this branch compiles for JDK 17, which has no
        // Arena to release it with. That does not change how a read of it faults.
        try (var ch = FileChannel.open(file, StandardOpenOption.READ)) {
            final var reader = new RandomAccessByteBufferReader(ch.map(FileChannel.MapMode.READ_ONLY, 0, fileSize),
                    0L, fileSize);
            final var arr = new byte[1 << 20];
            final var heapBuf = ByteBuffer.allocate(1 << 20);
            final var directBuf = ByteBuffer.allocateDirect(1 << 20);
            if (warm) {
                // Every length but the last, 1 MB, which would make this slow
                for (int i = 0; i < 200_000; i++) {
                    final int len = TRUNCATED_READ_LENGTHS[i % (TRUNCATED_READ_LENGTHS.length - 1)];
                    final int off = (i * 4099) & 0x7FFF;
                    reader.read(off, arr, 0, len);
                    reader.read(off, heapBuf, 0, len);
                    reader.read(off, directBuf, 0, len);
                    reader.readByte(off);
                    reader.readShort(off);
                    reader.readInt(off);
                    reader.readLong(off);
                }
            }
            try (var raf = new RandomAccessFile(file.toFile(), "rw")) {
                raf.setLength(4096);
            }
            final long off = fileSize / 2;
            for (final int len : TRUNCATED_READ_LENGTHS) {
                assertPageFault(() -> reader.read(off, arr, 0, len));
                assertPageFault(() -> reader.read(off, heapBuf, 0, len));
                assertPageFault(() -> reader.read(off, directBuf, 0, len));
            }
            assertPageFault(() -> reader.readByte(off));
            assertPageFault(() -> reader.readShort(off));
            assertPageFault(() -> reader.readInt(off));
            assertPageFault(() -> reader.readLong(off));
            // The page that is still in the file can still be read
            assertThat(reader.read(0, arr, 0, 4096)).isEqualTo(4096);
        }
    }

    private static void assertPageFault(final ThrowingCallable read) {
        assertThatThrownBy(read).isInstanceOf(IOException.class).hasMessageContaining("truncated")
                .hasCauseInstanceOf(InternalError.class);
    }
}
