package io.github.classgraph.vfs.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests that the three {@link RandomAccessReader} implementations that read a range of already-addressable bytes
 * read the same values from the same bytes. A file is read through {@link RandomAccessByteBufferReader} when it is
 * memory-mapped and through {@link RandomAccessFileChannelReader} when it is not, and through
 * {@link RandomAccessArrayReader} when it was read into RAM, so the three of them have to agree. (
 * {@link RandomAccessOrSequentialReader} also implements the interface, but reads content that has to be streamed
 * before it can be addressed, so it is tested separately.)
 */
public class RandomAccessReaderTest {
    /** The kinds of {@link RandomAccessReader}. */
    enum ReaderKind {
        /** {@link RandomAccessArrayReader}. */
        ARRAY,

        /** {@link RandomAccessByteBufferReader}. */
        BYTE_BUFFER,

        /** {@link RandomAccessFileChannelReader}. */
        FILE_CHANNEL
    }

    /** A byte pattern with a different value in every byte, so that byte order mistakes show up. */
    private static final byte[] PATTERN = { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD,
            (byte) 0xEF };

    /** A temporary directory to write the file that the file channel reader reads. */
    @TempDir
    private Path tempDir;

    /** The file channels opened by the test, closed when it ends. */
    private final List<FileChannel> fileChannels = new ArrayList<>();

    /**
     * Close the file channels the test opened.
     *
     * @throws IOException
     *             if a file channel could not be closed
     */
    @AfterEach
    public void closeFileChannels() throws IOException {
        for (final FileChannel fileChannel : fileChannels) {
            fileChannel.close();
        }
    }

    /**
     * Create a reader of the given kind, reading a range of the given content.
     *
     * @param readerKind
     *            the kind of reader to create
     * @param content
     *            the content to read
     * @param sliceStartPos
     *            the start position of the range to read
     * @param sliceLength
     *            the length of the range to read
     * @return the reader
     * @throws IOException
     *             if the content could not be written to a file, for the file channel reader
     */
    private RandomAccessReader reader(final ReaderKind readerKind, final byte[] content, final int sliceStartPos,
            final int sliceLength) throws IOException {
        return reader(readerKind, content, sliceStartPos, sliceLength, ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Create a reader of the given kind, reading a range of the given content in a given byte order.
     *
     * @param readerKind
     *            the kind of reader to create
     * @param content
     *            the content to read
     * @param sliceStartPos
     *            the start position of the range to read
     * @param sliceLength
     *            the length of the range to read
     * @param byteOrder
     *            the byte order to read multi-byte values in
     * @return the reader
     * @throws IOException
     *             if the content could not be written to a file, for the file channel reader
     */
    private RandomAccessReader reader(final ReaderKind readerKind, final byte[] content, final int sliceStartPos,
            final int sliceLength, final ByteOrder byteOrder) throws IOException {
        switch (readerKind) {
        case ARRAY:
            return new RandomAccessArrayReader(content, sliceStartPos, sliceLength, byteOrder);
        case BYTE_BUFFER:
            return new RandomAccessByteBufferReader(ByteBuffer.wrap(content), sliceStartPos, sliceLength,
                    byteOrder);
        default:
            final var file = Files.write(tempDir.resolve("content.bin"), content);
            final var fileChannel = FileChannel.open(file, StandardOpenOption.READ);
            fileChannels.add(fileChannel);
            return new RandomAccessFileChannelReader(fileChannel, sliceStartPos, sliceLength, byteOrder);
        }
    }

    /**
     * Every reader can be told which byte order to read in, and reports the order it was given. The byte order of
     * the machine the test runs on does not enter into it: the same bytes read in the same order give the same
     * values on a big-endian machine as on a little-endian one, which is what lets the classfile format (big
     * endian) and the zipfile format (little endian) both be read correctly wherever ClassGraph is running.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void valuesCanBeReadInEitherByteOrder(final ReaderKind readerKind) throws IOException {
        final var bigEndian = reader(readerKind, PATTERN, 0, PATTERN.length, ByteOrder.BIG_ENDIAN);
        assertThat(bigEndian.byteOrder()).isEqualTo(ByteOrder.BIG_ENDIAN);
        assertThat(bigEndian.readByte(0)).isEqualTo((byte) 0x01);
        assertThat(bigEndian.readUnsignedShort(0)).isEqualTo(0x0123);
        assertThat(bigEndian.readShort(6)).isEqualTo((short) 0xCDEF);
        assertThat(bigEndian.readInt(0)).isEqualTo(0x01234567);
        assertThat(bigEndian.readUnsignedInt(4)).isEqualTo(0x89ABCDEFL);
        assertThat(bigEndian.readLong(0)).isEqualTo(0x0123456789ABCDEFL);

        final var littleEndian = reader(readerKind, PATTERN, 0, PATTERN.length, ByteOrder.LITTLE_ENDIAN);
        assertThat(littleEndian.byteOrder()).isEqualTo(ByteOrder.LITTLE_ENDIAN);
        assertThat(littleEndian.readByte(0)).isEqualTo((byte) 0x01);
        assertThat(littleEndian.readUnsignedShort(0)).isEqualTo(0x2301);
        assertThat(littleEndian.readShort(6)).isEqualTo((short) 0xEFCD);
        assertThat(littleEndian.readInt(0)).isEqualTo(0x67452301);
        assertThat(littleEndian.readUnsignedInt(4)).isEqualTo(0xEFCDAB89L);
        assertThat(littleEndian.readLong(0)).isEqualTo(0xEFCDAB8967452301L);

        // The machine's own order is one of the two, and reads the same values as whichever one it is
        final var nativeOrder = reader(readerKind, PATTERN, 0, PATTERN.length, ByteOrder.nativeOrder());
        assertThat(nativeOrder.byteOrder()).isEqualTo(ByteOrder.nativeOrder());
        assertThat(nativeOrder.readLong(0)).isEqualTo(
                ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? 0x0123456789ABCDEFL : 0xEFCDAB8967452301L);
    }

    /**
     * Every value is read in little-endian order, which is the order the zipfile format stores its values in.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void everyValueIsReadInLittleEndianOrder(final ReaderKind readerKind) throws IOException {
        final var reader = reader(readerKind, PATTERN, 0, PATTERN.length);

        assertThat(reader.readByte(0)).isEqualTo((byte) 0x01);
        assertThat(reader.readByte(5)).isEqualTo((byte) 0xAB);
        assertThat(reader.readUnsignedByte(5)).isEqualTo(0xAB);
        assertThat(reader.readShort(6)).isEqualTo((short) 0xEFCD);
        assertThat(reader.readUnsignedShort(0)).isEqualTo(0x2301);
        assertThat(reader.readUnsignedShort(6)).isEqualTo(0xEFCD);
        assertThat(reader.readInt(0)).isEqualTo(0x67452301);
        assertThat(reader.readInt(4)).isEqualTo(0xEFCDAB89);
        assertThat(reader.readUnsignedInt(4)).isEqualTo(0xEFCDAB89L);
        assertThat(reader.readLong(0)).isEqualTo(0xEFCDAB8967452301L);
    }

    /**
     * Every offset is relative to the start of the slice, so a slice of a file reads the same values as the whole
     * file does at the corresponding offsets.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void everyOffsetIsRelativeToTheStartOfTheSlice(final ReaderKind readerKind) throws IOException {
        // Four bytes of padding, then the pattern, then four more bytes of padding
        final var padded = new byte[PATTERN.length + 8];
        System.arraycopy(PATTERN, 0, padded, 4, PATTERN.length);
        final var reader = reader(readerKind, padded, 4, PATTERN.length);

        assertThat(reader.readByte(0)).isEqualTo((byte) 0x01);
        assertThat(reader.readUnsignedShort(0)).isEqualTo(0x2301);
        assertThat(reader.readInt(0)).isEqualTo(0x67452301);
        assertThat(reader.readLong(0)).isEqualTo(0xEFCDAB8967452301L);
    }

    /**
     * Bytes are read into a byte array or a byte buffer, starting at the requested offset within the destination.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void bytesAreReadIntoAByteArrayOrAByteBuffer(final ReaderKind readerKind) throws IOException {
        final var reader = reader(readerKind, PATTERN, 0, PATTERN.length);

        final var dstArr = new byte[6];
        assertThat(reader.read(2, dstArr, 1, 4)).isEqualTo(4);
        assertThat(dstArr).containsExactly(0x00, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, 0x00);

        final var dstBuf = ByteBuffer.allocate(6);
        assertThat(reader.read(2, dstBuf, 1, 4)).isEqualTo(4);
        assertThat(dstBuf.array()).containsExactly(0x00, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, 0x00);

        // Reading nothing succeeds without touching the destination
        assertThat(reader.read(0, new byte[0], 0, 0)).isZero();

        // A partial read leaves the reader able to read the rest of the slice
        assertThat(reader.readLong(0)).isEqualTo(0xEFCDAB8967452301L);
    }

    /**
     * A read leaves the destination buffer's limit where it stopped, so a later read into the same buffer has to
     * open the limit up again before positioning. Positioning past a stale limit threw
     * {@link IllegalArgumentException} rather than reading.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void aLaterReadCanStartPastWhereThePreviousReadEnded(final ReaderKind readerKind) throws IOException {
        final var reader = reader(readerKind, PATTERN, 0, PATTERN.length);

        final var dstBuf = ByteBuffer.allocate(PATTERN.length);
        assertThat(reader.read(0, dstBuf, 0, 2)).isEqualTo(2);
        // Leave a gap in the destination, so this read starts past where the previous read ended
        assertThat(reader.read(6, dstBuf, 6, 2)).isEqualTo(2);
        assertThat(dstBuf.array()).containsExactly(0x01, 0x23, 0x00, 0x00, 0x00, 0x00, (byte) 0xCD, (byte) 0xEF);
    }

    /**
     * A bulk read that runs past the end of the slice stops at the end of it and reports how far it got, rather
     * than reading whatever follows the slice, and reports end of content once there is nothing left to read.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void aBulkReadStopsAtTheEndOfTheSlice(final ReaderKind readerKind) throws IOException {
        // A slice of the first four bytes only, so the rest of the content is out of bounds
        final var reader = reader(readerKind, PATTERN, 0, 4);
        final var dstArr = new byte[PATTERN.length];

        // Only the four bytes of the slice are read, and the rest of the destination is left alone
        assertThat(reader.read(0, dstArr, 0, 8)).isEqualTo(4);
        assertThat(dstArr).containsExactly(0x01, 0x23, 0x45, 0x67, 0x00, 0x00, 0x00, 0x00);
        // A read that starts within the slice and ends past it is cut down to what is left of the slice
        assertThat(reader.read(2, dstArr, 0, 4)).isEqualTo(2);
        // A read that starts at or past the end of the slice reports end of content
        assertThat(reader.read(4, dstArr, 0, 4)).isEqualTo(-1);
        assertThat(reader.read(100, dstArr, 0, 4)).isEqualTo(-1);
    }

    /**
     * A read of a value or a string that runs past the end of the slice is rejected, rather than reading whatever
     * follows the slice, since half of a value is not a value.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void aReadPastTheEndOfTheSliceIsRejected(final ReaderKind readerKind) throws IOException {
        // A slice of the first four bytes only, so the rest of the content is out of bounds
        final var reader = reader(readerKind, PATTERN, 0, 4);
        final var dstArr = new byte[PATTERN.length];

        assertThatThrownBy(() -> reader.read(-1, dstArr, 0, 1)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.readString(0, 8)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.readStringModifiedUtf8(0, 8)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        // A negative length is rejected before it is used as the size of the array the string is read into
        assertThatThrownBy(() -> reader.readString(0, -1)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.readStringModifiedUtf8(0, -1)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");

        // A value that straddles the end of the slice is rejected as well, rather than taking its remaining bytes
        // from whatever follows the slice
        assertThatThrownBy(() -> reader.readByte(4)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.readUnsignedByte(4)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.readShort(3)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.readUnsignedShort(3)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.readInt(1)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.readUnsignedInt(1)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.readLong(0)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.readByte(-1)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");

        // The reads that stay within the slice still succeed
        assertThat(reader.read(0, dstArr, 0, 4)).isEqualTo(4);
        assertThat(reader.readInt(0)).isEqualTo(0x67452301);
    }

    /**
     * A string is read in the modified UTF-8 format that the classfile format stores its strings in.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void aStringIsReadInModifiedUtf8(final ReaderKind readerKind) throws IOException {
        final var content = "xxLjava/lang/String;".getBytes(StandardCharsets.UTF_8);
        final var offset = 2;
        final var numBytes = content.length - offset;
        assertThat(reader(readerKind, content, 0, content.length).readStringModifiedUtf8(offset, numBytes))
                .isEqualTo("Ljava/lang/String;");

        // Modified UTF-8 writes a character outside the basic multilingual plane as a surrogate pair, each written
        // in the three-byte form, where standard UTF-8 writes the character itself in a four-byte form
        final var surrogatePair = new byte[] { (byte) 0xed, (byte) 0xa0, (byte) 0xbd, (byte) 0xed, (byte) 0xb8,
                (byte) 0x80 };
        final var surrogateReader = reader(readerKind, surrogatePair, 0, surrogatePair.length);
        assertThat(surrogateReader.readStringModifiedUtf8(0, surrogatePair.length)).isEqualTo("😀");
        // Standard UTF-8 does not allow surrogates to be encoded, so the same bytes read back differently
        assertThat(surrogateReader.readString(0, surrogatePair.length)).isNotEqualTo("😀");
    }

    /**
     * A string is read in UTF-8, or in whatever character encoding the caller asks for.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void aStringIsReadInAStandardCharacterEncoding(final ReaderKind readerKind) throws IOException {
        // 'é' takes two bytes in UTF-8 and one byte in ISO-8859-1, so the two encodings read these bytes
        // differently
        final var content = "xxrésumé".getBytes(StandardCharsets.UTF_8);
        final var reader = reader(readerKind, content, 0, content.length);
        final var offset = 2;
        final var numBytes = content.length - offset;

        assertThat(reader.readString(offset, numBytes)).isEqualTo("résumé");
        assertThat(reader.readString(offset, numBytes, StandardCharsets.UTF_8)).isEqualTo("résumé");
        assertThat(reader.readString(offset, numBytes, StandardCharsets.ISO_8859_1)).isEqualTo("rÃ©sumÃ©");
    }

    /**
     * A destination with no room left in it is not the end of the content. A caller copying content out in blocks
     * fills its destination and then hands the reader the offset just past the last byte it wrote, so a reader that
     * answered -1 there would tell that caller its content had ended, and the copy would be silently truncated.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void aFullDestinationIsNotTheEndOfTheContent(final ReaderKind readerKind) throws IOException {
        final var reader = reader(readerKind, PATTERN, 0, PATTERN.length);
        final var dstArr = new byte[4];
        assertThat(reader.read(0, dstArr, dstArr.length, 4)).isZero();
        final var dstBuf = ByteBuffer.allocate(4);
        assertThat(reader.read(0, dstBuf, dstBuf.capacity(), 4)).isZero();

        // The content really has not ended: a read with room for it still reads it
        assertThat(reader.read(0, dstArr, 0, 4)).isEqualTo(4);
    }

    /**
     * An offset that is not within the destination at all is a mistake on the caller's part, and is reported rather
     * than answered with the zero that a destination which is merely full is answered with.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void anOffsetOutsideTheDestinationIsRejected(final ReaderKind readerKind) throws IOException {
        final var reader = reader(readerKind, PATTERN, 0, PATTERN.length);
        final var dstArr = new byte[4];
        final var dstBuf = ByteBuffer.allocate(4);
        assertThatThrownBy(() -> reader.read(0, dstArr, dstArr.length + 1, 4)).isInstanceOf(IOException.class)
                .hasMessageContaining("out of bounds");
        assertThatThrownBy(() -> reader.read(0, dstArr, -1, 4)).isInstanceOf(IOException.class)
                .hasMessageContaining("out of bounds");
        assertThatThrownBy(() -> reader.read(0, dstBuf, dstBuf.capacity() + 1, 4)).isInstanceOf(IOException.class)
                .hasMessageContaining("out of bounds");
        assertThatThrownBy(() -> reader.read(0, dstBuf, -1, 4)).isInstanceOf(IOException.class)
                .hasMessageContaining("out of bounds");
    }

    /**
     * A read into a {@link ByteBuffer} writes at the index it is given, so it must leave the destination's position
     * and limit exactly where the caller left them. A caller that then reads the buffer relatively, or hands it to
     * something that does, gets the whole of it rather than just the window of the last read.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void aBufferReadLeavesThePositionAndLimitAlone(final ReaderKind readerKind) throws IOException {
        final var reader = reader(readerKind, PATTERN, 0, PATTERN.length);
        final var dstBuf = ByteBuffer.allocate(16);
        dstBuf.position(3);
        dstBuf.limit(12);

        assertThat(reader.read(0, dstBuf, 4, 4)).isEqualTo(4);
        assertThat(dstBuf.position()).isEqualTo(3);
        assertThat(dstBuf.limit()).isEqualTo(12);
        // The bytes really did land at the index that was asked for, not at the position
        assertThat(dstBuf.get(4)).isEqualTo(PATTERN[0]);
        assertThat(dstBuf.get(7)).isEqualTo(PATTERN[3]);

        // A second read is not affected by where the first one wrote
        assertThat(reader.read(4, dstBuf, 8, 4)).isEqualTo(4);
        assertThat(dstBuf.position()).isEqualTo(3);
        assertThat(dstBuf.limit()).isEqualTo(12);
        assertThat(dstBuf.get(8)).isEqualTo(PATTERN[4]);
    }

    /**
     * The room in a {@link ByteBuffer} destination ends at its limit, not at its capacity: a caller that lowered
     * the limit did so to say that the bytes past it are not to be written.
     *
     * @param readerKind
     *            the kind of reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void aBufferReadStopsAtTheLimitRatherThanTheCapacity(final ReaderKind readerKind) throws IOException {
        final var reader = reader(readerKind, PATTERN, 0, PATTERN.length);
        final var dstBuf = ByteBuffer.allocate(16);
        dstBuf.limit(6);

        // Only 2 of the 8 bytes asked for fit between index 4 and the limit
        assertThat(reader.read(0, dstBuf, 4, 8)).isEqualTo(2);

        // The limit is full at index 6, which is not the end of the content
        assertThat(reader.read(0, dstBuf, 6, 8)).isZero();

        // An index between the limit and the capacity is outside the destination, not merely full
        assertThatThrownBy(() -> reader.read(0, dstBuf, 7, 8)).isInstanceOf(IOException.class)
                .hasMessageContaining("out of bounds");

        // The bytes past the limit were not written. (The limit has to be opened up to look at them, since an
        // absolute read of a buffer is bounded by the limit too.)
        dstBuf.limit(dstBuf.capacity());
        assertThat(dstBuf.get(5)).isEqualTo(PATTERN[1]);
        assertThat(dstBuf.get(6)).isZero();
        assertThat(dstBuf.get(7)).isZero();
    }

    /**
     * Every {@link RandomAccessReader} rejects a read-only destination buffer the same way. The file channel reader
     * used to let {@link FileChannel}'s own {@link IllegalArgumentException} escape, while the other two turned
     * {@link java.nio.ReadOnlyBufferException} into an {@link IOException}, so the same caller mistake produced a
     * different exception depending on how the file happened to be opened.
     *
     * @param readerKind
     *            the kind of reader to test
     * @throws IOException
     *             if the reader could not be created
     */
    @ParameterizedTest
    @EnumSource(ReaderKind.class)
    public void aReadOnlyDestinationBufferIsRejectedTheSameWayByEveryReader(final ReaderKind readerKind)
            throws IOException {
        final var reader = reader(readerKind, PATTERN, 0, PATTERN.length);
        final var dstBuf = ByteBuffer.allocate(PATTERN.length).asReadOnlyBuffer();
        assertThatThrownBy(() -> reader.read(0, dstBuf, 0, PATTERN.length)).isInstanceOf(IOException.class)
                .hasMessage("The destination buffer is read-only");
    }
}
