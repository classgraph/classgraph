package io.github.classgraph.vfs.internal.slice.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.internal.ScanResources;
import io.github.classgraph.vfs.internal.slice.ArraySlice;
import io.github.classgraph.vfs.internal.slice.FileSlice;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;

/**
 * Tests that a classfile is read the same way whichever kind of classpath element it came from. The reader buffers
 * the classfile up to the point it has been read so far, and where the bytes are pulled from differs by source: a
 * jar that was read into RAM hands over its array as the buffer, a jar on disk is read in chunks, a deflated zip
 * entry is inflated into the buffer, and a module is read from a plain stream.
 */
public class ClassfileReaderTest {
    /** The kinds of classpath element that a classfile can be read from. */
    enum Source {
        /** A jar that was read into RAM, whose whole array is the classfile. */
        ARRAY,

        /** A jar that was read into RAM, where the classfile is one entry within the array. */
        ARRAY_SUB_SLICE,

        /** A jar on disk, read in chunks through a random access reader. */
        FILE,

        /** A jar on disk, read through a memory mapping, which is what is done by default on Windows. */
        FILE_MEMORY_MAPPED,

        /** A deflated zip entry, inflated into the buffer as it is read. */
        DEFLATED,

        /** A module, read from a plain {@link java.io.InputStream}. */
        INPUT_STREAM
    }

    /** A byte pattern with a different value in every byte, so that byte order mistakes show up. */
    private static final byte[] PATTERN = { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD,
            (byte) 0xEF };

    /** A temporary directory to write the file that the file slice reads. */
    @TempDir
    private Path tempDir;

    /** The resources owned by the scan, closed when the test ends. */
    private final ScanResources scanResources = scanResources(/* memoryMapFiles = */ false);

    /** The resources owned by a scan that memory-maps the files it reads, closed when the test ends. */
    private final ScanResources memoryMappedScanResources = scanResources(/* memoryMapFiles = */ true);

    /** The number of files written so far, so that each file slice can be given a file of its own. */
    private int numFilesWritten;

    /**
     * Create the resources for a scan that either does or does not memory-map the files it reads, so that both ways
     * of reading a file are exercised whatever platform the test is running on.
     *
     * @param memoryMapFiles
     *            whether the scan should memory-map the files it reads
     * @return the resources
     */
    private static ScanResources scanResources(final boolean memoryMapFiles) {
        final var vfsScanSpec = new VfsScanSpec();
        vfsScanSpec.memoryMapFiles = memoryMapFiles;
        return new ScanResources(vfsScanSpec, new InterruptionChecker());
    }

    /** Close the slices that the test opened. */
    @AfterEach
    public void closeScanResources() {
        scanResources.close(/* log = */ null);
        memoryMappedScanResources.close(/* log = */ null);
    }

    /**
     * Deflate content the way a zip entry is deflated, which is without the zlib wrapper.
     *
     * @param content
     *            the content to deflate
     * @return the deflated content
     */
    private static byte[] deflate(final byte[] content) {
        final var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, /* nowrap = */ true);
        try {
            deflater.setInput(content);
            deflater.finish();
            final var deflated = new ByteArrayOutputStream();
            final var buf = new byte[8192];
            while (!deflater.finished()) {
                deflated.write(buf, 0, deflater.deflate(buf));
            }
            return deflated.toByteArray();
        } finally {
            deflater.end();
        }
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
     *             if the content could not be written to a file, or an inflater could not be opened
     */
    private ClassfileReader reader(final Source source, final byte[] content) throws IOException {
        switch (source) {
        case ARRAY:
            return new ClassfileReader(new ArraySlice(content, /* isDeflatedZipEntry = */ false,
                    /* inflatedLengthHint = */ 0L, scanResources), /* resourceToClose = */ null);
        case ARRAY_SUB_SLICE: {
            // Four bytes of padding, then the content, then four more bytes of padding
            final var padded = new byte[content.length + 8];
            System.arraycopy(content, 0, padded, 4, content.length);
            final var wholeArray = new ArraySlice(padded, /* isDeflatedZipEntry = */ false,
                    /* inflatedLengthHint = */ 0L, scanResources);
            return new ClassfileReader(wholeArray.slice(4, content.length, /* isDeflatedZipEntry = */ false,
                    /* inflatedLengthHint = */ 0L), /* resourceToClose = */ null);
        }
        case FILE:
        case FILE_MEMORY_MAPPED: {
            // Each slice is given a file of its own, because on Windows a file that an earlier slice still has
            // memory-mapped cannot be written to again
            final var file = Files.write(tempDir.resolve("content" + numFilesWritten++ + ".bin"), content).toFile();
            return new ClassfileReader(new FileSlice(file,
                    source == Source.FILE_MEMORY_MAPPED ? memoryMappedScanResources : scanResources,
                    /* log = */ null), /* resourceToClose = */ null);
        }
        case DEFLATED:
            return new ClassfileReader(
                    new ArraySlice(deflate(content), /* isDeflatedZipEntry = */ true,
                            /* inflatedLengthHint = */ content.length, scanResources),
                    /* resourceToClose = */ null);
        default:
            return new ClassfileReader(new ByteArrayInputStream(content), /* resourceToClose = */ null);
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
            assertThatThrownBy(() -> reader.skip(-1)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Tried to skip a negative number of bytes");
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
            assertThat(reader.buf()).hasSizeGreaterThanOrEqualTo(content.length);
        }

        // A read at an offset past the end of the buffer grows the buffer to cover it in one step
        try (var reader = reader(source, content)) {
            assertThat(reader.readInt(content.length - 4)).isEqualTo(
                    ((content[content.length - 4] & 0xff) << 24) | ((content[content.length - 3] & 0xff) << 16)
                            | ((content[content.length - 2] & 0xff) << 8) | (content[content.length - 1] & 0xff));
        }

        // Buffering ahead reads the requested number of bytes without moving the read position
        try (var reader = reader(source, content)) {
            reader.bufferTo(content.length);
            assertThat(reader.currPos()).isZero();
            assertThat(reader.readByte(content.length - 1)).isEqualTo(content[content.length - 1]);
        }
    }

    /**
     * A read past the end of the classfile fails, rather than returning whatever happened to be in the buffer. (The
     * message depends on where the classfile is being read from, so only the exception type is checked here.)
     *
     * @param source
     *            the kind of classpath element to read from
     * @throws IOException
     *             if the content could not be read
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    public void aReadPastTheEndOfTheClassfileIsRejected(final Source source) throws IOException {
        try (var reader = reader(source, PATTERN)) {
            assertThatThrownBy(() -> reader.readByte(PATTERN.length)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader.readInt(PATTERN.length - 3)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader.readLong(1)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader.read(0, new byte[16], 0, 16)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader.readString(0, 16)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader.bufferTo(PATTERN.length + 1)).isInstanceOf(IOException.class);

            // The reads that stay within the classfile still succeed
            assertThat(reader.readInt(0)).isEqualTo(0x01234567);
        }
    }

    /**
     * A string is read in the modified UTF-8 format that the classfile format stores its strings in, optionally
     * converting it from the internal form of a class name to the form a user would write.
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
            assertThat(reader.readString(offset, numBytes)).isEqualTo("Ljava/lang/String;");
            assertThat(reader.readString(offset, numBytes, /* replaceSlashWithDot = */ true,
                    /* stripLSemicolon = */ true)).isEqualTo("java.lang.String");
        }

        // The sequential overloads read from the current position, and advance it past the string
        try (var reader = reader(source, content)) {
            reader.skip(offset);
            assertThat(reader.readString(numBytes)).isEqualTo("Ljava/lang/String;");
            assertThat(reader.currPos()).isEqualTo(content.length);
        }

        try (var reader = reader(source, content)) {
            reader.skip(offset);
            assertThat(reader.readString(numBytes, /* replaceSlashWithDot = */ true, /* stripLSemicolon = */ true))
                    .isEqualTo("java.lang.String");
            assertThat(reader.currPos()).isEqualTo(content.length);
        }
    }

    /**
     * Closing the reader closes the classpath element it was reading, and closing it twice closes that element only
     * once, since it may have been reopened in the meantime.
     *
     * @throws IOException
     *             if the content could not be read
     */
    @Test
    public void closingTheReaderClosesTheClasspathElementItWasReadingOnlyOnce() throws IOException {
        final var closeCount = new AtomicInteger();
        final Closeable resourceToClose = closeCount::incrementAndGet;

        final var reader = new ClassfileReader(new ArraySlice(PATTERN, /* isDeflatedZipEntry = */ false,
                /* inflatedLengthHint = */ 0L, scanResources), resourceToClose);
        assertThat(reader.readInt()).isEqualTo(0x01234567);

        reader.close();
        assertThat(closeCount).hasValue(1);
        reader.close();
        assertThat(closeCount).hasValue(1);
    }
}
