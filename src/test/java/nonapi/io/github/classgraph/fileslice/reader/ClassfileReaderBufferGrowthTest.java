package nonapi.io.github.classgraph.fileslice.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.ArraySlice;
import nonapi.io.github.classgraph.fileslice.FileSlice;
import nonapi.io.github.classgraph.fileslice.Slice;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Tests how far a {@link ClassfileReader} grows its buffer.
 *
 * <p>
 * The number of bytes to skip is read from the classfile itself, so a corrupt attribute length can ask for a skip
 * of nearly 2GB. The buffer used to be grown straight to the target of the skip, so one corrupt classfile in a jar
 * made a 20kb classfile allocate 2GB, and the {@link OutOfMemoryError} aborted the whole scan.
 *
 * <p>
 * Growing the buffer only as far as the classfile goes is not enough on its own: an attribute of hundreds of
 * megabytes of zeros takes up only a few hundred kilobytes of a jar once deflated, and it used to be buffered in
 * full. No classfile is read past a fixed limit, far beyond the length of any real classfile.
 */
public class ClassfileReaderBufferGrowthTest {
    /** The length of the content. */
    private static final int CONTENT_LENGTH = 20_000;

    /** A skip far past the end of the content, as read from a corrupt attribute length. */
    private static final int LONG_SKIP = 64 * 1024 * 1024;

    /** The message that a read past the end of the classfile is reported with. */
    private static final String PAST_THE_END = "Tried to read past the end of the classfile";

    /** The number of bytes of a classfile that are read, at most. */
    private static final int MAX_CLASSFILE_LENGTH = 64 * 1024 * 1024;

    /** The length of content that runs on past {@link #MAX_CLASSFILE_LENGTH}. */
    private static final int OVERSIZED_LENGTH = MAX_CLASSFILE_LENGTH + 1024 * 1024;

    /** The message that a read past {@link #MAX_CLASSFILE_LENGTH} is reported with. */
    private static final String READ_PAST_THE_LIMIT = "Tried to read past the limit of " + MAX_CLASSFILE_LENGTH
            + " bytes on the length of a classfile";

    /** The message that a skip past {@link #MAX_CLASSFILE_LENGTH} is reported with. */
    private static final String SKIP_PAST_THE_LIMIT = "Tried to skip past the limit of " + MAX_CLASSFILE_LENGTH
            + " bytes on the length of a classfile";

    /** A reader over an {@link java.io.InputStream}, as a classfile in a jar or a module is read through. */
    private static ClassfileReader streamReader() throws IOException {
        return new ClassfileReader(new ByteArrayInputStream(new byte[CONTENT_LENGTH]), null);
    }

    /**
     * An {@link InputStream} of a given number of zero bytes, which does not hold them, so that content longer than
     * {@link #MAX_CLASSFILE_LENGTH} does not have to be allocated in order to be read.
     *
     * @param length
     *            the number of bytes
     * @return the stream
     */
    private static InputStream zeros(final long length) {
        return new InputStream() {
            /** The number of bytes left to read. */
            private long remaining = length;

            @Override
            public int read() {
                if (remaining == 0) {
                    return -1;
                }
                remaining--;
                return 0;
            }

            @Override
            public int read(final byte[] b, final int off, final int len) {
                if (len == 0) {
                    return 0;
                }
                if (remaining == 0) {
                    return -1;
                }
                final int numBytes = (int) Math.min(len, remaining);
                Arrays.fill(b, off, off + numBytes, (byte) 0);
                remaining -= numBytes;
                return numBytes;
            }
        };
    }

    /**
     * Check that a reader of content that runs on past {@link #MAX_CLASSFILE_LENGTH} rejects a skip or a read past
     * the limit, and still reads the content up to the limit.
     *
     * @param reader
     *            the reader, at the start of the content
     * @throws IOException
     *             if the content could not be read
     */
    private static void assertReadUpToTheLimitAndNoFurther(final ClassfileReader reader) throws IOException {
        assertThatThrownBy(() -> reader.skip(MAX_CLASSFILE_LENGTH + 1)).isInstanceOf(IOException.class)
                .hasMessage(SKIP_PAST_THE_LIMIT);
        assertThatThrownBy(() -> reader.readByte(MAX_CLASSFILE_LENGTH)).isInstanceOf(IOException.class)
                .hasMessage(READ_PAST_THE_LIMIT);
        reader.skip(MAX_CLASSFILE_LENGTH - 4);
        assertThat(reader.readInt()).isZero();
        assertThatThrownBy(reader::readByte).isInstanceOf(IOException.class).hasMessage(READ_PAST_THE_LIMIT);
    }

    /**
     * A skip or a read past {@link #MAX_CLASSFILE_LENGTH} throws {@link IOException} without buffering any of the
     * content, even though the content runs on past the limit, and the content up to the limit can still be read.
     *
     * @throws IOException
     *             if the content could not be read
     */
    @Test
    public void aSkipOrReadPastTheLimitIsRejectedWithoutBuffering() throws IOException {
        try (ClassfileReader reader = new ClassfileReader(zeros(OVERSIZED_LENGTH), null)) {
            assertThatThrownBy(() -> reader.skip(MAX_CLASSFILE_LENGTH + 1)).isInstanceOf(IOException.class)
                    .hasMessage(SKIP_PAST_THE_LIMIT);
            assertThatThrownBy(() -> reader.readByte(MAX_CLASSFILE_LENGTH)).isInstanceOf(IOException.class)
                    .hasMessage(READ_PAST_THE_LIMIT);
            assertThat(reader.buf().length).isLessThan(1024 * 1024);
            assertReadUpToTheLimitAndNoFurther(reader);
            assertThat(reader.buf().length).isEqualTo(MAX_CLASSFILE_LENGTH);
        }
    }

    /**
     * The limit applies to every kind of {@link Slice} a classfile can be read from: a deflated zip entry, whose
     * declared length is believed only as far as the limit, an array, and a file.
     *
     * @param tempDir
     *            a temporary directory to write the file to
     * @throws IOException
     *             if the content could not be written or read
     */
    @Test
    public void everyKindOfSliceIsReadUpToTheLimitAndNoFurther(@TempDir final File tempDir) throws IOException {
        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        try {
            final ByteArrayOutputStream deflated = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(deflated,
                    new Deflater(Deflater.DEFAULT_COMPRESSION, /* nowrap = */ true))) {
                final byte[] chunk = new byte[1024 * 1024];
                for (int remaining = OVERSIZED_LENGTH; remaining > 0; remaining -= chunk.length) {
                    deflaterOutputStream.write(chunk, 0, Math.min(remaining, chunk.length));
                }
            }
            final ArraySlice deflatedSlice = new ArraySlice(deflated.toByteArray(), /* isDeflatedZipEntry = */ true,
                    /* inflatedLengthHint = */ OVERSIZED_LENGTH, nestedJarHandler);
            try (ClassfileReader reader = new ClassfileReader(deflatedSlice, null)) {
                assertReadUpToTheLimitAndNoFurther(reader);
            }

            final ArraySlice wholeArraySlice = new ArraySlice(new byte[OVERSIZED_LENGTH + 8],
                    /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L, nestedJarHandler);
            try (ClassfileReader reader = new ClassfileReader(wholeArraySlice, null)) {
                assertReadUpToTheLimitAndNoFurther(reader);
            }
            final Slice partialArraySlice = wholeArraySlice.slice(8, OVERSIZED_LENGTH,
                    /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L);
            try (ClassfileReader reader = new ClassfileReader(partialArraySlice, null)) {
                assertReadUpToTheLimitAndNoFurther(reader);
                assertThat(reader.buf().length).isEqualTo(MAX_CLASSFILE_LENGTH);
            }

            final File file = new File(tempDir, "Oversized.class");
            try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {
                randomAccessFile.setLength(OVERSIZED_LENGTH);
            }
            try (ClassfileReader reader = new ClassfileReader(new FileSlice(file, nestedJarHandler, null), null)) {
                assertReadUpToTheLimitAndNoFurther(reader);
                assertThat(reader.buf().length).isEqualTo(MAX_CLASSFILE_LENGTH);
            }
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A skip past the end of a stream throws {@link IOException} without growing the buffer to the target of the
     * skip, both as the first read and after the start of the content has been read.
     *
     * @throws IOException
     *             if the content could not be read
     */
    @Test
    public void aSkipPastTheEndOfAStreamDoesNotGrowTheBufferToTheSkipTarget() throws IOException {
        try (ClassfileReader reader = streamReader()) {
            assertThatThrownBy(() -> reader.skip(LONG_SKIP)).isInstanceOf(IOException.class)
                    .hasMessage(PAST_THE_END);
            assertThat(reader.buf().length).isLessThanOrEqualTo(2 * CONTENT_LENGTH);
        }
        try (ClassfileReader reader = streamReader()) {
            reader.skip(1000);
            reader.readInt();
            assertThatThrownBy(() -> reader.skip(LONG_SKIP - 2000)).isInstanceOf(IOException.class)
                    .hasMessage(PAST_THE_END);
            assertThat(reader.buf().length).isLessThanOrEqualTo(2 * CONTENT_LENGTH);
        }
    }

    /**
     * The uncompressed size that a zip entry declares is believed as far as one bounded allocation, so a corrupt
     * declared size cannot make the buffer grow to the target of a skip either.
     *
     * @throws IOException
     *             if the content could not be deflated or read
     */
    @Test
    public void aCorruptDeclaredSizeDoesNotLetASkipGrowTheBufferToTheSkipTarget() throws IOException {
        final ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(deflated,
                new Deflater(Deflater.DEFAULT_COMPRESSION, /* nowrap = */ true))) {
            deflaterOutputStream.write(new byte[CONTENT_LENGTH]);
        }
        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        try {
            final ArraySlice slice = new ArraySlice(deflated.toByteArray(), /* isDeflatedZipEntry = */ true,
                    /* inflatedLengthHint = */ 1L << 30, nestedJarHandler);
            try (ClassfileReader reader = new ClassfileReader(slice, null)) {
                assertThatThrownBy(() -> reader.skip(LONG_SKIP)).isInstanceOf(IOException.class)
                        .hasMessage(PAST_THE_END);
                assertThat(reader.buf().length).isLessThan(LONG_SKIP);
            }
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A bulk read buffers no more than the destination can take, so a number of bytes that is larger than the
     * destination does not grow the buffer past it, or fail for running past the end of the content.
     *
     * @throws IOException
     *             if the content could not be read
     */
    @Test
    public void aBulkReadBuffersNoMoreThanTheDestinationCanTake() throws IOException {
        try (ClassfileReader reader = streamReader()) {
            assertThat(reader.read(0, new byte[10], 0, Integer.MAX_VALUE)).isEqualTo(10);
            assertThat(reader.buf().length).isLessThanOrEqualTo(2 * CONTENT_LENGTH);
        }
        try (ClassfileReader reader = streamReader()) {
            assertThat(reader.read(0, ByteBuffer.allocate(10), 0, Integer.MAX_VALUE)).isEqualTo(10);
            assertThat(reader.buf().length).isLessThanOrEqualTo(2 * CONTENT_LENGTH);
        }
    }
}
