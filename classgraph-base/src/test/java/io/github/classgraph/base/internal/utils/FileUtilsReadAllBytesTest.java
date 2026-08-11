package io.github.classgraph.base.internal.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * Tests the reading of a resource into a byte array. The length of a deflated zip entry is only a hint, since it is
 * read from the zipfile itself, so it can be wrong in either direction, and it can be a lie told by a zipfile that
 * is trying to make the reader allocate more memory than it has.
 */
public class FileUtilsReadAllBytesTest {
    /** An {@link InputStream} that records whether it was closed. */
    private static class ClosedRecordingInputStream extends ByteArrayInputStream {
        /** True once this stream has been closed. */
        boolean closed;

        /**
         * Constructor.
         *
         * @param content
         *            the content of the stream
         */
        ClosedRecordingInputStream(final byte[] content) {
            super(content);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * Content of the given length, with a different value every few bytes, so that content read in the wrong order
     * or with a byte dropped does not still match.
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
     * The whole stream is read whether its length is known, unknown, overstated or understated, since the length of
     * a zip entry is only a hint.
     *
     * @throws IOException
     *             if a stream could not be read
     */
    @Test
    public void theWholeStreamIsReadWhateverItsLengthWasSaidToBe() throws IOException {
        // A stream longer than the default buffer size, so that the buffer has to be grown when the length is not
        // known in advance
        final var content = content(100_000);

        for (final long lengthHint : new long[] { -1L, 0L, 1L, content.length / 2, content.length,
                content.length * 2L }) {
            assertThat(FileUtils.readAllBytesAsArray(new ByteArrayInputStream(content), lengthHint))
                    .as("length hint %d", lengthHint).containsExactly(content);
        }
    }

    /**
     * An empty stream is read as an empty array, rather than failing.
     *
     * @throws IOException
     *             if a stream could not be read
     */
    @Test
    public void anEmptyStreamIsReadAsAnEmptyArray() throws IOException {
        assertThat(FileUtils.readAllBytesAsArray(new ByteArrayInputStream(new byte[0]), -1L)).isEmpty();
        assertThat(FileUtils.readAllBytesAsArray(new ByteArrayInputStream(new byte[0]), 0L)).isEmpty();
    }

    /**
     * The stream is closed once it has been read, so that reading a resource does not leak the handle it was read
     * through.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    public void theStreamIsClosedOnceItHasBeenRead() throws IOException {
        final var inputStream = new ClosedRecordingInputStream(content(16));
        assertThat(FileUtils.readAllBytesAsArray(inputStream, 16L)).hasSize(16);
        assertThat(inputStream.closed).isTrue();
    }

    /**
     * A length that is too large to fit in an array is rejected before the array is allocated, so that a zipfile
     * cannot make the reader run out of memory just by claiming an entry is enormous.
     */
    @Test
    public void aLengthThatIsTooLargeToFitInAnArrayIsRejected() {
        assertThatThrownBy(() -> FileUtils.readAllBytesAsArray(new ByteArrayInputStream(content(16)),
                FileUtils.MAX_BUFFER_SIZE + 1L)).isInstanceOf(IOException.class)
                .hasMessage("InputStream is too large to read");
    }
}
