package nonapi.io.github.classgraph.fileslice.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link RandomAccessByteBufferReader}, which is the reader used for memory-mapped files, against
 * {@link RandomAccessArrayReader}, which reads the same little-endian layout from a byte array.
 */
public class RandomAccessByteBufferReaderTest {
    /** A little-endian unsigned short must be read in full, not truncated to its low byte. */
    @Test
    public void readUnsignedShortReadsBothBytes() throws IOException {
        // 0x1234 in little-endian order
        final byte[] arr = { (byte) 0x34, (byte) 0x12 };
        final RandomAccessByteBufferReader reader = new RandomAccessByteBufferReader(ByteBuffer.wrap(arr), 0L,
                arr.length);
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
        final RandomAccessByteBufferReader reader = new RandomAccessByteBufferReader(ByteBuffer.wrap(arr), 0L,
                arr.length);
        assertThat(reader.readShort(0)).isEqualTo((short) -128);
        assertThat(reader.readShort(0))
                .isEqualTo(new RandomAccessArrayReader(arr, 0, arr.length).readShort(0));
    }

    /** Reading a short at a nonzero offset within a slice must respect the slice start position. */
    @Test
    public void readUnsignedShortWithinASlice() throws IOException {
        final byte[] arr = { (byte) 0xAA, (byte) 0xBB, (byte) 0x34, (byte) 0x12 };
        // Slice covering just the last two bytes
        final RandomAccessByteBufferReader reader = new RandomAccessByteBufferReader(ByteBuffer.wrap(arr), 2L, 2L);
        assertThat(reader.readUnsignedShort(0)).isEqualTo(0x1234);
    }

    /** Reading a string at a nonzero slice offset must return the bytes at that offset, not fail or return junk. */
    @Test
    public void readStringAtNonZeroOffset() throws IOException {
        final byte[] prefix = "PREFIX".getBytes(StandardCharsets.UTF_8);
        final byte[] str = "ClassGraph".getBytes(StandardCharsets.UTF_8);
        final byte[] arr = new byte[prefix.length + str.length];
        System.arraycopy(prefix, 0, arr, 0, prefix.length);
        System.arraycopy(str, 0, arr, prefix.length, str.length);

        // Read via a slice that starts at the string
        final RandomAccessByteBufferReader sliceReader = new RandomAccessByteBufferReader(ByteBuffer.wrap(arr),
                prefix.length, str.length);
        assertThat(sliceReader.readString(0, str.length)).isEqualTo("ClassGraph");

        // Read via a whole-array reader, using an offset to reach the string
        final RandomAccessByteBufferReader wholeReader = new RandomAccessByteBufferReader(ByteBuffer.wrap(arr), 0L,
                arr.length);
        assertThat(wholeReader.readString(prefix.length, str.length)).isEqualTo("ClassGraph");
        // Must agree with the byte-array reader
        assertThat(new RandomAccessArrayReader(arr, 0, arr.length).readString(prefix.length, str.length))
                .isEqualTo("ClassGraph");
    }
}
