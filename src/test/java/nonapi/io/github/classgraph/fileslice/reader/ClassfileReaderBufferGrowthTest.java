package nonapi.io.github.classgraph.fileslice.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.ArraySlice;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Tests how far a {@link ClassfileReader} grows its buffer.
 *
 * <p>
 * The number of bytes to skip is read from the classfile itself, so a corrupt attribute length can ask for a skip
 * of nearly 2GB. The buffer used to be grown straight to the target of the skip, so one corrupt classfile in a jar
 * made a 20kb classfile allocate 2GB, and the {@link OutOfMemoryError} aborted the whole scan.
 */
public class ClassfileReaderBufferGrowthTest {
    /** The length of the content. */
    private static final int CONTENT_LENGTH = 20_000;

    /** A skip far past the end of the content, as read from a corrupt attribute length. */
    private static final int LONG_SKIP = 64 * 1024 * 1024;

    /** The message that a read past the end of the classfile is reported with. */
    private static final String PAST_THE_END = "Tried to read past the end of the classfile";

    /** A reader over an {@link java.io.InputStream}, as a classfile in a jar or a module is read through. */
    private static ClassfileReader streamReader() throws IOException {
        return new ClassfileReader(new ByteArrayInputStream(new byte[CONTENT_LENGTH]), null);
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
