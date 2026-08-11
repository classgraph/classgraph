package io.github.classgraph.vfs.internal.slice.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
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
 * Tests that the three little-endian {@link RandomAccessReader} implementations read the same values from the same
 * bytes. A file is read through {@link RandomAccessByteBufferReader} when it is memory-mapped and through
 * {@link RandomAccessFileChannelReader} when it is not, and through {@link RandomAccessArrayReader} when it was
 * read into RAM, so the three of them have to agree. ({@link ClassfileReader} also implements the interface, but
 * reads in big-endian order, as the classfile format requires, so it is tested separately.)
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
        switch (readerKind) {
        case ARRAY:
            return new RandomAccessArrayReader(content, sliceStartPos, sliceLength);
        case BYTE_BUFFER:
            return new RandomAccessByteBufferReader(ByteBuffer.wrap(content), sliceStartPos, sliceLength);
        default:
            final var file = Files.write(tempDir.resolve("content.bin"), content);
            final var fileChannel = FileChannel.open(file, StandardOpenOption.READ);
            fileChannels.add(fileChannel);
            return new RandomAccessFileChannelReader(fileChannel, sliceStartPos, sliceLength);
        }
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
     * A read that runs past the end of the slice is rejected, rather than reading whatever follows the slice.
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

        assertThatThrownBy(() -> reader.read(0, dstArr, 0, 8)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.read(2, dstArr, 0, 4)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.read(-1, dstArr, 0, 1)).isInstanceOf(IOException.class)
                .hasMessage("Read index out of bounds");
        assertThatThrownBy(() -> reader.readString(0, 8)).isInstanceOf(IOException.class)
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
     * A string is read in the modified UTF-8 format that the classfile format stores its strings in, optionally
     * converting it from the internal form of a class name to the form a user would write.
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
        final var reader = reader(readerKind, content, 0, content.length);
        final var offset = 2;
        final var numBytes = content.length - offset;

        assertThat(reader.readString(offset, numBytes)).isEqualTo("Ljava/lang/String;");
        assertThat(
                reader.readString(offset, numBytes, /* replaceSlashWithDot = */ true, /* stripLSemicolon = */ true))
                .isEqualTo("java.lang.String");
    }
}
