package io.github.classgraph.vfs.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.VfsRoot;

/**
 * Tests that a classfile is read the same way whichever kind of classpath element it came from. The reader buffers
 * the classfile up to the point it has been read so far, and where the bytes come from differs by source: an entry
 * of a directory is read straight from the file, a stored zip entry is sliced out of the jarfile, a deflated zip
 * entry is inflated as it is read, and a module is read from a plain stream.
 */
public class RandomAccessOrSequentialReaderTest {
    /** The package that the classfile is put in within the directory or jarfile it is read from. */
    private static final String PACKAGE_NAME = "pkg";

    /** The name that the classfile is given within the directory or jarfile it is read from. */
    private static final String ENTRY_NAME = PACKAGE_NAME + "/Test.class";

    /** The kinds of classpath element that a classfile can be read from. */
    enum Source {
        /** An entry of a directory classpath element. */
        DIR_ENTRY,

        /** A zip entry that was stored rather than deflated, which is sliced out of the jarfile as it is read. */
        JAR_ENTRY_STORED,

        /** A deflated zip entry, which is inflated as it is read. */
        JAR_ENTRY_DEFLATED,

        /** A module, read from a plain {@link java.io.InputStream}, whose length is not known ahead of time. */
        INPUT_STREAM,

        /**
         * A module whose {@link java.io.InputStream} never transfers more than one byte per read, which
         * {@link java.io.InputStream#read(byte[], int, int)} is permitted to do, and which the channel-backed
         * streams that modules and directories are read through really can do.
         */
        INPUT_STREAM_SHORT_READS
    }

    /**
     * An {@link InputStream} that never transfers more than one byte per read, however many were asked for.
     */
    private static final class ShortReadInputStream extends InputStream {
        private final InputStream wrapped;

        ShortReadInputStream(final InputStream wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public int read() throws IOException {
            return wrapped.read();
        }

        @Override
        public int read(final byte[] buf, final int off, final int len) throws IOException {
            return len == 0 ? 0 : wrapped.read(buf, off, 1);
        }

        @Override
        public void close() throws IOException {
            wrapped.close();
        }
    }

    /** An {@link InputStream} that counts how many bytes have been pulled out of the stream underneath it. */
    private static final class CountingInputStream extends InputStream {
        private final InputStream wrapped;

        /** The number of bytes transferred so far. */
        private int numBytesRead;

        CountingInputStream(final InputStream wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public int read() throws IOException {
            final var byteRead = wrapped.read();
            if (byteRead >= 0) {
                numBytesRead++;
            }
            return byteRead;
        }

        @Override
        public int read(final byte[] buf, final int off, final int len) throws IOException {
            final var numRead = wrapped.read(buf, off, len);
            if (numRead > 0) {
                numBytesRead += numRead;
            }
            return numRead;
        }

        @Override
        public void close() throws IOException {
            wrapped.close();
        }
    }

    /** A byte pattern with a different value in every byte, so that byte order mistakes show up. */
    private static final byte[] PATTERN = { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD,
            (byte) 0xEF };

    /** A temporary directory to write the directory classpath elements to. */
    @TempDir
    private Path tempDir;

    /** The virtual filesystem the classpath elements are opened through, closed when the test ends. */
    private final Vfs vfs = new Vfs();

    /** The number of roots opened so far, so that each reader can be given a classpath element of its own. */
    private int numRootsOpened;

    /** Close the classpath elements that the test opened. */
    @AfterEach
    public void closeVfs() {
        vfs.close();
    }

    /**
     * Build a jarfile in RAM holding the classfile as its only entry.
     *
     * @param content
     *            the content of the classfile
     * @param compressionMethod
     *            {@link ZipEntry#STORED} or {@link ZipEntry#DEFLATED}
     * @return the bytes of the jarfile
     * @throws IOException
     *             if the jarfile could not be written
     */
    private static byte[] jar(final byte[] content, final int compressionMethod) throws IOException {
        final var jarBytes = new ByteArrayOutputStream();
        try (var zipOut = new ZipOutputStream(jarBytes)) {
            final var zipEntry = new ZipEntry(ENTRY_NAME);
            zipEntry.setMethod(compressionMethod);
            if (compressionMethod == ZipEntry.STORED) {
                // A stored entry's size and CRC have to be known before its content is written, since they go in the
                // local file header, which precedes it
                final var crc = new CRC32();
                crc.update(content);
                zipEntry.setSize(content.length);
                zipEntry.setCompressedSize(content.length);
                zipEntry.setCrc(crc.getValue());
            }
            zipOut.putNextEntry(zipEntry);
            zipOut.write(content);
            zipOut.closeEntry();
        }
        return jarBytes.toByteArray();
    }

    /**
     * The classfile entry of a classpath element.
     *
     * @param root
     *            the classpath element
     * @return the entry
     * @throws IOException
     *             if the classpath element could not be read
     */
    private static VfsEntry entry(final VfsRoot root) throws IOException {
        return Objects.requireNonNull(root.getEntry(ENTRY_NAME), () -> "No entry " + ENTRY_NAME + " in " + root);
    }

    /**
     * Create a reader that reads the given content from the given kind of classpath element.
     *
     * @param source
     *            the kind of classpath element to read from
     * @param content
     *            the content of the classfile
     * @return the reader
     * @throws IOException
     *             if the classpath element could not be written or opened
     */
    private RandomAccessOrSequentialReader reader(final Source source, final byte[] content) throws IOException {
        switch (source) {
        case DIR_ENTRY: {
            // Each reader is given a directory of its own, both because a Vfs hands back the same root the second
            // time a path is opened, and because on Windows a file that is still memory-mapped cannot be rewritten
            final var dir = tempDir.resolve("dir" + numRootsOpened++);
            Files.createDirectories(dir.resolve(PACKAGE_NAME));
            Files.write(dir.resolve(ENTRY_NAME), content);
            return new RandomAccessOrSequentialReader(entry(vfs.open(dir.toFile())));
        }
        case JAR_ENTRY_STORED:
            return new RandomAccessOrSequentialReader(entry(vfs.open(jar(content, ZipEntry.STORED), "stored.jar")));
        case JAR_ENTRY_DEFLATED:
            return new RandomAccessOrSequentialReader(
                    entry(vfs.open(jar(content, ZipEntry.DEFLATED), "deflated.jar")));
        case INPUT_STREAM_SHORT_READS:
            return new RandomAccessOrSequentialReader(new ShortReadInputStream(new ByteArrayInputStream(content)));
        default:
            return new RandomAccessOrSequentialReader(new ByteArrayInputStream(content));
        }
    }

    /**
     * Overwrite the uncompressed size that the central directory of a jarfile declares for its only entry, the way
     * a zipfile written by a broken tool declares a size that its deflate stream does not hold.
     *
     * @param jarBytes
     *            the bytes of the jarfile, modified in place.
     * @param declaredSize
     *            the uncompressed size to declare.
     */
    private static void setDeclaredUncompressedSize(final byte[] jarBytes, final int declaredSize) {
        // Find the central directory header, which is where a zip reader takes the sizes of an entry from
        var cenOffset = -1;
        for (var i = 0; i < jarBytes.length - 3; i++) {
            if (jarBytes[i] == 0x50 && jarBytes[i + 1] == 0x4b && jarBytes[i + 2] == 0x01
                    && jarBytes[i + 3] == 0x02) {
                cenOffset = i;
                break;
            }
        }
        assertThat(cenOffset).as("central directory header").isNotNegative();
        // The uncompressed size is a little endian 32-bit field 24 bytes into the header
        for (var i = 0; i < 4; i++) {
            jarBytes[cenOffset + 24 + i] = (byte) (declaredSize >>> (i * 8));
        }
    }

    /**
     * The length of the content is where the stream ends, whatever length the content declares for itself, so a zip
     * entry that declares an uncompressed size larger than its deflate stream holds is not padded out with zeroes,
     * and one that declares a size smaller than its deflate stream holds is not truncated. The zipfile format does
     * not guarantee that field is right, and neither {@code ZipFile} nor {@code jdk.nio.zipfs} trusts it.
     *
     * @param declaredSize
     *            the uncompressed size to declare, which is wrong in one direction or the other
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @ValueSource(ints = { 1, 100, 300, 100_000 })
    public void aWrongDeclaredLengthDoesNotTruncateOrPadTheContent(final int declaredSize) throws IOException {
        final var content = content(200);
        final var jarBytes = jar(content, ZipEntry.DEFLATED);
        setDeclaredUncompressedSize(jarBytes, declaredSize);
        final var entry = entry(vfs.open(jarBytes, "wrongsize" + declaredSize + ".jar"));

        try (var reader = new RandomAccessOrSequentialReader(entry)) {
            // The end of the content is where the deflate stream ends
            assertThat(reader.length()).isEqualTo(content.length);

            // Every byte of the content can be read, and nothing past it
            final var readBack = new byte[content.length + 16];
            assertThat(reader.read(0, readBack, 0, readBack.length)).isEqualTo(content.length);
            assertThat(Arrays.copyOf(readBack, content.length)).isEqualTo(content);
            assertThat(reader.readByte(content.length - 1)).isEqualTo(content[content.length - 1]);
            assertThat(reader.read(content.length, readBack, 0, 16)).isEqualTo(-1);
            assertThatThrownBy(() -> reader.readByte(content.length)).isInstanceOf(IOException.class)
                    .hasMessage("Tried to read past the end of the content");
        }

        // The whole entry reads back the same way through the filesystem view
        assertThat(Files.readAllBytes(entry.getRoot().asFileSystem().getPath("/" + ENTRY_NAME))).isEqualTo(content);
    }

    /**
     * The length of the content is where the stream ends, for every kind of classpath element, and asking for it
     * does not disturb the reads that follow.
     *
     * @param source
     *            the kind of classpath element to read from
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    public void theLengthOfTheContentIsWhereTheStreamEnds(final Source source) throws IOException {
        final var content = content(100_000);
        try (var reader = reader(source, content)) {
            // A read of a header comes first, so that the length is asked for after the buffer has been part filled
            assertThat(reader.readInt(0)).isEqualTo(((content[0] & 0xff) << 24) | ((content[1] & 0xff) << 16)
                    | ((content[2] & 0xff) << 8) | (content[3] & 0xff));

            assertThat(reader.length()).isEqualTo(content.length);
            // Asking a second time does not read the stream again, and does not change the answer
            assertThat(reader.length()).isEqualTo(content.length);

            final var readBack = new byte[content.length];
            assertThat(reader.read(0, readBack, 0, readBack.length)).isEqualTo(content.length);
            assertThat(readBack).isEqualTo(content);
        }
    }

    /**
     * Values are read in whichever byte order the reader was given, so that content that is not in the big endian
     * order of the classfile format can be read too, including content written in the byte order of the machine.
     *
     * @throws IOException
     *             if the content could not be read
     */
    @Test
    public void valuesCanBeReadInEitherByteOrder() throws IOException {
        try (var bigEndian = new RandomAccessOrSequentialReader(new ByteArrayInputStream(PATTERN),
                ByteOrder.BIG_ENDIAN);
                var littleEndian = new RandomAccessOrSequentialReader(new ByteArrayInputStream(PATTERN),
                        ByteOrder.LITTLE_ENDIAN);
                var nativeEndian = new RandomAccessOrSequentialReader(new ByteArrayInputStream(PATTERN),
                        ByteOrder.nativeOrder())) {
            assertThat(bigEndian.byteOrder()).isEqualTo(ByteOrder.BIG_ENDIAN);
            assertThat(littleEndian.byteOrder()).isEqualTo(ByteOrder.LITTLE_ENDIAN);
            assertThat(nativeEndian.byteOrder()).isEqualTo(ByteOrder.nativeOrder());

            assertThat(bigEndian.readUnsignedShort(0)).isEqualTo(0x0123);
            assertThat(littleEndian.readUnsignedShort(0)).isEqualTo(0x2301);
            assertThat(bigEndian.readInt(0)).isEqualTo(0x01234567);
            assertThat(littleEndian.readInt(0)).isEqualTo(0x67452301);
            assertThat(bigEndian.readLong(0)).isEqualTo(0x0123456789ABCDEFL);
            assertThat(littleEndian.readLong(0)).isEqualTo(0xEFCDAB8967452301L);

            // A reader in the byte order of the machine agrees with whichever of the two that is
            final var nativeIsBigEndian = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
            assertThat(nativeEndian.readInt(0)).isEqualTo(nativeIsBigEndian ? 0x01234567 : 0x67452301);
        }
    }

    /**
     * Content of the given length, with a different value every few bytes, so that content read at the wrong offset
     * does not still match.
     *
     * @param length
     *            the length of the content
     * @return the content
     */
    private static byte[] content(final int length) {
        final var content = new byte[length];
        for (var i = 0; i < length; i++) {
            content[i] = (byte) (i * 31);
        }
        return content;
    }

    /**
     * Every value is read in big-endian order, which is the order the classfile format stores its values in.
     *
     * @param source
     *            the kind of classpath element to read from
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    public void everyValueIsReadInBigEndianOrder(final Source source) throws IOException {
        try (var reader = reader(source, PATTERN)) {
            assertThat(reader.readByte(0)).isEqualTo((byte) 0x01);
            assertThat(reader.readUnsignedByte(4)).isEqualTo(0x89);
            assertThat(reader.readShort(6)).isEqualTo((short) 0xCDEF);
            assertThat(reader.readUnsignedShort(0)).isEqualTo(0x0123);
            assertThat(reader.readUnsignedShort(6)).isEqualTo(0xCDEF);
            assertThat(reader.readInt(0)).isEqualTo(0x01234567);
            assertThat(reader.readInt(4)).isEqualTo(0x89ABCDEF);
            assertThat(reader.readUnsignedInt(4)).isEqualTo(0x89ABCDEFL);
            assertThat(reader.readLong(0)).isEqualTo(0x0123456789ABCDEFL);

            final var dstArr = new byte[6];
            assertThat(reader.read(2, dstArr, 1, 4)).isEqualTo(4);
            assertThat(dstArr).containsExactly(0x00, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, 0x00);

            final var dstBuf = ByteBuffer.allocate(6);
            assertThat(reader.read(2, dstBuf, 1, 4)).isEqualTo(4);
            assertThat(dstBuf.array()).containsExactly(0x00, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, 0x00);

            // Reading nothing succeeds without touching the destination
            assertThat(reader.read(0, new byte[0], 0, 0)).isZero();
        }
    }

    /**
     * A sequential read starts where the last one stopped, which is how the classfile format is parsed.
     *
     * @param source
     *            the kind of classpath element to read from
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    public void aSequentialReadStartsWhereTheLastOneStopped(final Source source) throws IOException {
        try (var reader = reader(source, PATTERN)) {
            assertThat(reader.currPos()).isZero();
            assertThat(reader.readUnsignedByte()).isEqualTo(0x01);
            assertThat(reader.readByte()).isEqualTo((byte) 0x23);
            assertThat(reader.currPos()).isEqualTo(2);
            assertThat(reader.readUnsignedShort()).isEqualTo(0x4567);
            assertThat(reader.readShort()).isEqualTo((short) 0x89AB);
            assertThat(reader.readUnsignedShort()).isEqualTo(0xCDEF);
            assertThat(reader.currPos()).isEqualTo(8);
        }

        try (var reader = reader(source, PATTERN)) {
            assertThat(reader.readInt()).isEqualTo(0x01234567);
            assertThat(reader.readUnsignedInt()).isEqualTo(0x89ABCDEFL);
            assertThat(reader.currPos()).isEqualTo(8);
        }

        try (var reader = reader(source, PATTERN)) {
            assertThat(reader.readLong()).isEqualTo(0x0123456789ABCDEFL);
            assertThat(reader.currPos()).isEqualTo(8);
        }

        // Skipping moves the read position past the parts of the classfile that are not needed, such as bytecodes
        try (var reader = reader(source, PATTERN)) {
            reader.skip(4);
            assertThat(reader.currPos()).isEqualTo(4);
            assertThat(reader.readInt()).isEqualTo(0x89ABCDEF);
        }
    }

    /**
     * A skip past the end of the classfile is rejected, and leaves the read position where it was. The number of
     * bytes to skip is read from the classfile -- it is the length of an attribute that is not of interest -- so a
     * corrupt classfile can ask for a skip of almost 2GB, or, since an attribute length is an unsigned 32-bit value
     * and no classfile can be that long anyway, for a skip of a negative number of bytes.
     *
     * @param source
     *            the kind of classpath element to read from
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    public void aSkipPastTheEndOfTheClassfileIsRejected(final Source source) throws IOException {
        try (var reader = reader(source, PATTERN)) {
            assertThat(reader.readInt()).isEqualTo(0x01234567);

            assertThatThrownBy(() -> reader.skip(PATTERN.length)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader.skip(Integer.MAX_VALUE)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader.skip(-1)).isInstanceOf(IOException.class)
                    .hasMessage("Tried to skip a negative number of bytes");

            // The read position did not move, so the reads that stay within the classfile still succeed
            assertThat(reader.currPos()).isEqualTo(4);
            assertThat(reader.readInt()).isEqualTo(0x89ABCDEF);
        }
    }

    /**
     * A classfile longer than the initial buffer is read in full, whichever kind of classpath element it came from,
     * so that a large classfile is not silently truncated at the buffer boundary.
     *
     * @param source
     *            the kind of classpath element to read from
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    public void aClassfileLongerThanTheInitialBufferIsReadInFull(final Source source) throws IOException {
        // Longer than the 16kB initial buffer, so that the buffer has to be grown more than once
        final var content = content(40_000);
        try (var reader = reader(source, content)) {
            // Read sequentially through the whole classfile, so that the buffer is grown a chunk at a time
            for (var offset = 0; offset < content.length; offset++) {
                assertThat(reader.readByte()).as("offset %d", offset).isEqualTo(content[offset]);
            }
            assertThat(reader.currPos()).isEqualTo(content.length);

            // Every byte that was read is still in the buffer, and can be read again at random
            assertThat(reader.readByte(0)).isEqualTo(content[0]);
            assertThat(reader.readByte(content.length - 1)).isEqualTo(content[content.length - 1]);
        }

        // A read at an offset past the end of the buffer grows the buffer to cover it in one step
        try (var reader = reader(source, content)) {
            assertThat(reader.readInt(content.length - 4)).isEqualTo(
                    ((content[content.length - 4] & 0xff) << 24) | ((content[content.length - 3] & 0xff) << 16)
                            | ((content[content.length - 2] & 0xff) << 8) | (content[content.length - 1] & 0xff));
        }

        // A read at an offset does not move the position that a sequential read starts from
        try (var reader = reader(source, content)) {
            assertThat(reader.readByte(content.length - 1)).isEqualTo(content[content.length - 1]);
            assertThat(reader.currPos()).isZero();
        }
    }

    /**
     * A read past the end of the classfile fails, rather than returning whatever happened to be in the buffer, and
     * says that the read ran past the end of the content, whichever kind of classpath element it is being read
     * from.
     *
     * @param source
     *            the kind of classpath element to read from
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    public void aReadPastTheEndOfTheClassfileIsRejected(final Source source) throws IOException {
        final var pastTheEnd = "Tried to read past the end of the content";
        try (var reader = reader(source, PATTERN)) {
            assertThatThrownBy(() -> reader.readByte(PATTERN.length)).isInstanceOf(IOException.class)
                    .hasMessage(pastTheEnd);
            assertThatThrownBy(() -> reader.readInt(PATTERN.length - 3)).isInstanceOf(IOException.class)
                    .hasMessage(pastTheEnd);
            assertThatThrownBy(() -> reader.readLong(1)).isInstanceOf(IOException.class).hasMessage(pastTheEnd);
            // A bulk read stops at the end of the content and reports how far it got, rather than throwing
            assertThat(reader.read(0, new byte[16], 0, 16)).isEqualTo(PATTERN.length);
            assertThat(reader.read(PATTERN.length, new byte[16], 0, 16)).isEqualTo(-1);
            assertThatThrownBy(() -> reader.readString(0, 16)).isInstanceOf(IOException.class)
                    .hasMessage(pastTheEnd);

            // The reads that stay within the classfile still succeed
            assertThat(reader.readInt(0)).isEqualTo(0x01234567);
        }
    }

    /**
     * A read leaves the destination buffer's limit where it stopped, so a later read into the same buffer has to
     * open the limit up again before positioning. Positioning past a stale limit threw
     * {@link IllegalArgumentException} rather than reading, which is what the other {@link RandomAccessReader}
     * implementations do not do.
     *
     * @param source
     *            the kind of classpath element to read from
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    public void aLaterReadCanStartPastWhereThePreviousReadEnded(final Source source) throws IOException {
        try (var reader = reader(source, PATTERN)) {
            final var dstBuf = ByteBuffer.allocate(PATTERN.length);
            assertThat(reader.read(0, dstBuf, 0, 2)).isEqualTo(2);
            // Leave a gap in the destination, so this read starts past where the previous read ended
            assertThat(reader.read(6, dstBuf, 6, 2)).isEqualTo(2);
            assertThat(dstBuf.array()).containsExactly(0x01, 0x23, 0x00, 0x00, 0x00, 0x00, (byte) 0xCD,
                    (byte) 0xEF);
        }
    }

    /**
     * An offset that is out of range is rejected, rather than being silently narrowed to an int, or added to the
     * number of bytes to read to give a sum that wraps negative -- either of which would turn a read from outside
     * the content into one that looks like it is already buffered. Offsets are read out of the content itself (a
     * classfile constant pool holds them), so corrupt content can ask for a read at any offset.
     *
     * @param source
     *            the kind of classpath element to read from
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    public void anOutOfRangeOffsetIsRejected(final Source source) throws IOException {
        try (var reader = reader(source, PATTERN)) {
            // Adding the number of bytes to read to these offsets wraps an int sum negative
            assertThatThrownBy(() -> reader.readByte(Integer.MAX_VALUE)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader.readUnsignedShort(Integer.MAX_VALUE)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader.readLong(Integer.MAX_VALUE - 4)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader.readString(Integer.MAX_VALUE, 8)).isInstanceOf(IOException.class);
            // A bulk read reports the end of the content rather than throwing, since a caller copying the content
            // out does not necessarily know how long it is
            assertThat(reader.read(Integer.MAX_VALUE, new byte[8], 0, 8)).isEqualTo(-1);

            // Narrowing this offset to an int gives 0, which is within the content
            assertThatThrownBy(() -> reader.readInt(0x1_0000_0000L)).isInstanceOf(IOException.class);

            // A negative offset indexes outside the buffer
            assertThatThrownBy(() -> reader.readByte(-1)).isInstanceOf(IOException.class);

            // The reads that stay within the content still succeed
            assertThat(reader.readInt(0)).isEqualTo(0x01234567);
        }
    }

    /**
     * A string is read in the modified UTF-8 format that the classfile format stores its strings in.
     *
     * @param source
     *            the kind of classpath element to read from
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    public void aStringIsReadInModifiedUtf8(final Source source) throws IOException {
        final var content = "xxLjava/lang/String;".getBytes(StandardCharsets.UTF_8);
        final var offset = 2;
        final var numBytes = content.length - offset;

        try (var reader = reader(source, content)) {
            assertThat(reader.readStringModifiedUtf8(offset, numBytes)).isEqualTo("Ljava/lang/String;");
        }

        // The sequential overload reads from the current position, and advances it past the string
        try (var reader = reader(source, content)) {
            reader.skip(offset);
            assertThat(reader.readStringModifiedUtf8(numBytes)).isEqualTo("Ljava/lang/String;");
            assertThat(reader.currPos()).isEqualTo(content.length);
        }
    }

    /**
     * A string is read in UTF-8, or in whatever character encoding the caller asks for.
     *
     * @param source
     *            the kind of classpath element to read from
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    public void aStringIsReadInAStandardCharacterEncoding(final Source source) throws IOException {
        // 'é' takes two bytes in UTF-8 and one byte in ISO-8859-1, so the two encodings read these bytes
        // differently
        final var content = "xxrésumé".getBytes(StandardCharsets.UTF_8);
        final var offset = 2;
        final var numBytes = content.length - offset;

        try (var reader = reader(source, content)) {
            assertThat(reader.readString(offset, numBytes)).isEqualTo("résumé");
            assertThat(reader.readString(offset, numBytes, StandardCharsets.ISO_8859_1)).isEqualTo("rÃ©sumÃ©");
        }

        // The sequential overloads read from the current position, and advance it past the string
        try (var reader = reader(source, content)) {
            reader.skip(offset);
            assertThat(reader.readString(numBytes)).isEqualTo("résumé");
            assertThat(reader.currPos()).isEqualTo(content.length);
        }

        try (var reader = reader(source, content)) {
            reader.skip(offset);
            assertThat(reader.readString(numBytes, StandardCharsets.ISO_8859_1)).isEqualTo("rÃ©sumÃ©");
            assertThat(reader.currPos()).isEqualTo(content.length);
        }
    }

    /**
     * The bytes at an offset are compared with an ASCII string without a {@link String} being built out of them,
     * which is how the classfile parser tells one attribute name from another.
     *
     * @param source
     *            the kind of classpath element to read from
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    public void bytesAreComparedWithAnAsciiString(final Source source) throws IOException {
        // The two bytes at the front stand for the length that precedes a string in the constant pool
        final var content = "xxConstantValue".getBytes(StandardCharsets.US_ASCII);
        final var offset = 2;
        final var numBytes = content.length - offset;

        try (var reader = reader(source, content)) {
            assertThat(reader.contentEqualsAscii(offset, numBytes, "ConstantValue")).isTrue();

            // Only the requested bytes are compared, not the whole of the rest of the content
            assertThat(reader.contentEqualsAscii(offset, 8, "Constant")).isTrue();

            // The same number of bytes, but not the same bytes
            assertThat(reader.contentEqualsAscii(offset, numBytes, "ConstantValuf")).isFalse();
            assertThat(reader.contentEqualsAscii(offset, numBytes, "constantValue")).isFalse();
        }

        // A number of bytes that is not the length of the string is answered without anything being read, so a
        // length read out of corrupt content is reported as "not this string" rather than as an IO error
        try (var reader = reader(source, content)) {
            assertThat(reader.contentEqualsAscii(offset, Integer.MAX_VALUE, "ConstantValue")).isFalse();
        }

        // A byte outside the ASCII range is not equal to any character of an ASCII string
        final var nonAscii = new byte[] { (byte) 0xC3 };
        try (var reader = reader(source, nonAscii)) {
            assertThat(reader.contentEqualsAscii(0, nonAscii.length, "C")).isFalse();
        }

        // A range outside the content is rejected, as it is for the other reads at an offset
        try (var reader = reader(source, content)) {
            assertThatThrownBy(() -> reader.contentEqualsAscii(offset, numBytes + 1, "ConstantValueX"))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader.contentEqualsAscii(-1, 1, "C")).isInstanceOf(IOException.class);
        }
    }

    /**
     * The reader pulls from the stream underneath it only as far as the furthest offset that has been read, so a
     * caller that reads a header out of a large entry pays for the header rather than for the whole entry, and a
     * caller that goes back over a part it has already read does not pull it a second time.
     *
     * @throws IOException
     *             if the content could not be read.
     */
    @Test
    public void onlyReadsAsFarAsTheFurthestOffsetThatIsRead() throws IOException {
        final var content = new byte[1024 * 1024];
        for (var i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        final var countingStream = new CountingInputStream(new ByteArrayInputStream(content));
        try (var reader = new RandomAccessOrSequentialReader(countingStream)) {
            // A read of the first few bytes pulls the initial buffer's worth, and no more
            assertThat(reader.readByte(0)).isEqualTo(content[0]);
            final var numBytesReadForHeader = countingStream.numBytesRead;
            assertThat(numBytesReadForHeader).isLessThanOrEqualTo(16384);

            // Going back over a part that has already been read pulls nothing further
            assertThat(reader.readByte(numBytesReadForHeader - 1)).isEqualTo(content[numBytesReadForHeader - 1]);
            assertThat(countingStream.numBytesRead).isEqualTo(numBytesReadForHeader);

            // Reading further pulls only as far as the offset that was asked for, rounded up to a whole chunk
            final var furtherOffset = numBytesReadForHeader + 1024;
            assertThat(reader.readByte(furtherOffset)).isEqualTo(content[furtherOffset]);
            assertThat(countingStream.numBytesRead).isGreaterThan(furtherOffset).isLessThan(furtherOffset + 65536);

            // The rest of the content is still there to be read
            assertThat(reader.readByte(content.length - 1)).isEqualTo(content[content.length - 1]);
            assertThat(countingStream.numBytesRead).isEqualTo(content.length);
        }
    }

    /**
     * A declared uncompressed size that cannot describe the same content as the compressed size beside it is not
     * used to size the buffer. Believing one costs an allocation that is thrown away, and the two sentinel values a
     * zip entry carries when its size was never filled in would ask for gigabytes.
     */
    @Test
    public void aDeclaredLengthThatCannotBeRightIsNotBelieved() {
        // A plausible declared length is used as it stands
        assertThat(RandomAccessOrSequentialReader.validatedLengthHint(100_000L, 40_000L)).isEqualTo(100_000);

        // An entry that is not stored compressed declares the same value for both sizes, so no check can fire
        assertThat(RandomAccessOrSequentialReader.validatedLengthHint(100_000L, 100_000L)).isEqualTo(100_000);

        // A length is only a length if there is one, and an empty entry needs no buffer
        assertThat(RandomAccessOrSequentialReader.validatedLengthHint(-1L, 40_000L)).isEqualTo(-1);
        assertThat(RandomAccessOrSequentialReader.validatedLengthHint(0L, 40_000L)).isEqualTo(-1);

        // The size a zip entry is left holding when it overflowed into a Zip64 extra field that is not there, and
        // the size a writer that clamped to a signed 32-bit maximum leaves behind
        assertThat(RandomAccessOrSequentialReader.validatedLengthHint(0xffffffffL, 40_000L)).isEqualTo(-1);
        assertThat(RandomAccessOrSequentialReader.validatedLengthHint(0x7fffffffL, 40_000L)).isEqualTo(-1);

        // No amount of deflated data inflates to more than 1032 times its size, so a ratio far above that is wrong,
        // while a ratio below it is believed -- a run of identical bytes really does compress that far
        assertThat(RandomAccessOrSequentialReader.validatedLengthHint(1024L * 40_000L, 40_000L))
                .isEqualTo(1024 * 40_000);
        assertThat(RandomAccessOrSequentialReader.validatedLengthHint(4096L * 40_000L, 40_000L)).isEqualTo(-1);

        // Deflating incompressible data expands it slightly, so content a little shorter than the bytes it is
        // stored in is believed, and content a kilobyte shorter than them is not
        assertThat(RandomAccessOrSequentialReader.validatedLengthHint(39_990L, 40_000L)).isEqualTo(39_990);
        assertThat(RandomAccessOrSequentialReader.validatedLengthHint(30_000L, 40_000L)).isEqualTo(-1);

        // Without a compressed size to check it against, a declared length is taken at face value
        assertThat(RandomAccessOrSequentialReader.validatedLengthHint(30_000L, -1L)).isEqualTo(30_000);
    }

    /**
     * Reaching the end of content that declared its length correctly does not grow the buffer. A stream only
     * reports that it has ended when a read asks it for a byte that it does not have, so a reader that grows the
     * buffer before asking copies the whole of the content into a larger array, once per entry, to discover that
     * there was nothing left to put in it.
     *
     * @throws IOException
     *             if the content could not be read.
     */
    @Test
    public void findingTheEndOfTheContentDoesNotGrowTheBuffer() throws IOException {
        final var content = content(300_000);
        final var entry = entry(vfs.open(jar(content, ZipEntry.DEFLATED), "exactsize.jar"));
        try (var reader = new RandomAccessOrSequentialReader(entry)) {
            // Read the whole of the content the way a channel does, a fixed block at a time, without ever asking
            // how long it is. The buffer ends up exactly full, because the entry declares its length correctly.
            final var block = new byte[65536];
            var total = 0;
            for (int numRead; (numRead = reader.read(total, block, 0, block.length)) > 0;) {
                total += numRead;
            }
            assertThat(total).isEqualTo(content.length);

            // The buffer holds the content and nothing more. Growing it to look for content that is not there
            // would have doubled it, or added a chunk to it, before the read that found the end of the stream.
            assertThat(reader.bufferLength()).isEqualTo(content.length);

            // Every byte is still there to be read back, so nothing was lost in finding the end
            final var readBack = new byte[content.length];
            assertThat(reader.read(0, readBack, 0, readBack.length)).isEqualTo(content.length);
            assertThat(readBack).isEqualTo(content);
        }
    }

    /**
     * The limit the tests below lower the reader's buffer to, standing in for the largest array the JVM can
     * allocate. Larger than {@code INITIAL_BUF_SIZE} so that the buffer really has to be grown to reach it.
     */
    private static final int TEST_BUFFER_LIMIT = 100_000;

    /** The message the reader uses when the content runs on past the largest buffer it can grow. */
    private static final String TOO_LARGE = "larger than the 2GB that can be buffered";

    /**
     * An {@link InputStream} of a given number of bytes, which does not hold them: the tests below read past the
     * point where a buffer can hold the content, so materializing it would defeat the purpose.
     */
    private static final class GeneratedInputStream extends InputStream {
        private long remaining;

        GeneratedInputStream(final long length) {
            remaining = length;
        }

        @Override
        public int read() {
            return remaining-- > 0 ? 'x' : -1;
        }

        @Override
        public int read(final byte[] dstArr, final int dstArrStart, final int numBytes) {
            if (remaining <= 0) {
                return -1;
            }
            final var numToWrite = (int) Math.min(numBytes, remaining);
            Arrays.fill(dstArr, dstArrStart, dstArrStart + numToWrite, (byte) 'x');
            remaining -= numToWrite;
            return numToWrite;
        }
    }

    /**
     * A reader over the given number of generated bytes, whose buffer is limited to {@link #TEST_BUFFER_LIMIT}
     * rather than to the largest array the JVM can allocate.
     *
     * @param contentLength
     *            the number of bytes the content is made up of.
     * @return the reader.
     */
    private static RandomAccessOrSequentialReader limitedReader(final long contentLength) {
        final var reader = new RandomAccessOrSequentialReader(new GeneratedInputStream(contentLength));
        reader.setMaxBufferSize(TEST_BUFFER_LIMIT);
        return reader;
    }

    /**
     * Reading content that runs on past the largest buffer that can be allocated reports that, rather than stopping
     * at the limit and reporting the end of the content. A caller streaming an entry through a channel cannot tell
     * a short read at the limit apart from the real end of the entry, so reporting one would hand back a silently
     * truncated copy of a longer entry.
     *
     * @throws IOException
     *             if the content could not be read.
     */
    @Test
    public void contentLongerThanTheBufferIsNotSilentlyTruncated() throws IOException {
        try (var reader = limitedReader(TEST_BUFFER_LIMIT * 4L)) {
            final var block = new byte[8192];
            assertThatThrownBy(() -> {
                for (var total = 0L;;) {
                    final var numRead = reader.read(total, block, 0, block.length);
                    if (numRead <= 0) {
                        throw new AssertionError("Reported the end of the content at " + total
                                + " bytes, but the content is " + TEST_BUFFER_LIMIT * 4L + " bytes long");
                    }
                    total += numRead;
                }
            }).isInstanceOf(IOException.class).hasMessageContaining(TOO_LARGE);
        }
    }

    /**
     * Asking how long content is that runs on past the largest buffer that can be allocated reports that, rather
     * than returning the length of the part of it that fits.
     */
    @Test
    public void lengthOfContentLongerThanTheBufferIsNotTheBufferLength() {
        try (var reader = limitedReader(TEST_BUFFER_LIMIT * 4L)) {
            assertThatThrownBy(reader::length).isInstanceOf(IOException.class).hasMessageContaining(TOO_LARGE);
        }
    }

    /**
     * Content that ends exactly at the largest buffer that can be allocated is not truncated, so it is read back in
     * full rather than being reported as too long. A stream only reports that it has ended once a read asks it for
     * a byte that it does not have, so the reader has to ask before it can tell this apart from content that runs
     * on past the limit.
     *
     * @throws IOException
     *             if the content could not be read.
     */
    @Test
    public void contentEndingExactlyAtTheBufferLimitIsReadInFull() throws IOException {
        try (var reader = limitedReader(TEST_BUFFER_LIMIT)) {
            final var block = new byte[8192];
            var total = 0L;
            for (int numRead; (numRead = reader.read(total, block, 0, block.length)) > 0;) {
                total += numRead;
            }
            assertThat(total).isEqualTo(TEST_BUFFER_LIMIT);
            assertThat(reader.length()).isEqualTo(TEST_BUFFER_LIMIT);
        }
    }

    /**
     * Reading at an offset past the largest buffer that can be allocated reports the end of the content when the
     * content really does end before that offset. Nothing can be buffered that far in either way, so the reader has
     * to find where the content ends before it can answer, and answering with the end of the content is only right
     * for content that fits.
     *
     * @throws IOException
     *             if the content could not be read.
     */
    @Test
    public void readingPastTheBufferLimitOfShortContentIsTheEndOfTheContent() throws IOException {
        try (var reader = limitedReader(1024)) {
            assertThat(reader.read(TEST_BUFFER_LIMIT + 1L, new byte[16], 0, 16)).isEqualTo(-1);
        }
    }

    /**
     * Reading at an offset past the largest buffer that can be allocated reports that the content is too long, when
     * the content really does run on past the offset. Reporting the end of the content there would tell a caller
     * seeking within a large entry that the entry had ended, when it had not.
     */
    @Test
    public void readingPastTheBufferLimitOfLongContentIsNotTheEndOfTheContent() {
        try (var reader = limitedReader(TEST_BUFFER_LIMIT * 4L)) {
            assertThatThrownBy(() -> reader.read(TEST_BUFFER_LIMIT + 1L, new byte[16], 0, 16))
                    .isInstanceOf(IOException.class).hasMessageContaining(TOO_LARGE);
        }
    }

    /**
     * A destination with no room left in it is not the end of the content. A caller copying content out in blocks
     * fills its destination and then hands the reader the offset just past the last byte it wrote, so a reader that
     * answered -1 there would tell that caller its content had ended, and the copy would be silently truncated.
     *
     * @throws IOException
     *             if the content could not be read.
     */
    @Test
    public void aFullDestinationIsNotTheEndOfTheContent() throws IOException {
        try (var reader = new RandomAccessOrSequentialReader(new ByteArrayInputStream(PATTERN))) {
            final var dstArr = new byte[4];
            assertThat(reader.read(0, dstArr, dstArr.length, 4)).isZero();
            final var dstBuf = ByteBuffer.allocate(4);
            assertThat(reader.read(0, dstBuf, dstBuf.capacity(), 4)).isZero();

            // The content really has not ended: a read with room for it still reads it
            assertThat(reader.read(0, dstArr, 0, 4)).isEqualTo(4);
        }
    }

    /**
     * An offset that is not within the destination at all is a mistake on the caller's part, and is reported rather
     * than answered with the zero that a destination which is merely full is answered with.
     *
     * @throws IOException
     *             if the content could not be read.
     */
    @Test
    public void anOffsetOutsideTheDestinationIsRejected() throws IOException {
        try (var reader = new RandomAccessOrSequentialReader(new ByteArrayInputStream(PATTERN))) {
            final var dstArr = new byte[4];
            final var dstBuf = ByteBuffer.allocate(4);
            assertThatThrownBy(() -> reader.read(0, dstArr, dstArr.length + 1, 4)).isInstanceOf(IOException.class)
                    .hasMessageContaining("out of bounds");
            assertThatThrownBy(() -> reader.read(0, dstArr, -1, 4)).isInstanceOf(IOException.class)
                    .hasMessageContaining("out of bounds");
            assertThatThrownBy(() -> reader.read(0, dstBuf, dstBuf.capacity() + 1, 4))
                    .isInstanceOf(IOException.class).hasMessageContaining("out of bounds");
            assertThatThrownBy(() -> reader.read(0, dstBuf, -1, 4)).isInstanceOf(IOException.class)
                    .hasMessageContaining("out of bounds");
        }
    }
}
