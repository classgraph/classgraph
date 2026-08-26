package nonapi.io.github.classgraph.fileslice.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.fileslice.ArraySlice;

/**
 * Tests reading from a {@link ClassfileReader} into a {@link ByteBuffer}.
 *
 * <p>
 * A read into a {@code ByteBuffer} positions the buffer at the offset to write at, and a previous read leaves the
 * buffer's limit where it stopped, so positioning past that stale limit threw {@code IllegalArgumentException}. The
 * other three {@link RandomAccessReader} implementations open the limit up to the buffer's capacity before
 * positioning; this one did not, so the same call sequence failed depending on where the content was being read
 * from.
 */
public class ClassfileReaderByteBufferReadTest {
    /** Eight bytes, with a different value in every one. */
    private static final byte[] PATTERN = { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD,
            (byte) 0xEF };

    /** A reader over a fixed array, which cannot grow its buffer. */
    private static ClassfileReader arrayReader() throws IOException {
        return new ClassfileReader(new ArraySlice(PATTERN.clone(), /* isDeflatedZipEntry = */ false,
                /* inflatedLengthHint = */ 0L, /* nestedJarHandler = */ null), null);
    }

    /** A reader over an {@link java.io.InputStream}, which grows its buffer as it reads. */
    private static ClassfileReader streamReader() throws IOException {
        return new ClassfileReader(new ByteArrayInputStream(PATTERN.clone()), null);
    }

    /** A later read into the same buffer can start past where the previous read ended, for a fixed array. */
    @Test
    public void aLaterReadOfAnArrayCanStartPastWhereThePreviousReadEnded() throws IOException {
        try (ClassfileReader reader = arrayReader()) {
            assertALaterReadCanStartPastWhereThePreviousReadEnded(reader);
        }
    }

    /** A later read into the same buffer can start past where the previous read ended, for a stream. */
    @Test
    public void aLaterReadOfAStreamCanStartPastWhereThePreviousReadEnded() throws IOException {
        try (ClassfileReader reader = streamReader()) {
            assertALaterReadCanStartPastWhereThePreviousReadEnded(reader);
        }
    }

    /**
     * Read into the start of a buffer, then read into a later part of the same buffer, leaving a gap.
     *
     * @param reader
     *            the reader to read through
     * @throws IOException
     *             if the content could not be read
     */
    private static void assertALaterReadCanStartPastWhereThePreviousReadEnded(final ClassfileReader reader)
            throws IOException {
        final ByteBuffer dstBuf = ByteBuffer.allocate(PATTERN.length);
        assertThat(reader.read(0, dstBuf, 0, 2)).isEqualTo(2);
        // Leave a gap in the destination, so this read starts past where the previous read ended
        assertThat(reader.read(6, dstBuf, 6, 2)).isEqualTo(2);
        assertThat(dstBuf.array()).containsExactly(0x01, 0x23, 0x00, 0x00, 0x00, 0x00, (byte) 0xCD, (byte) 0xEF);
    }
}
