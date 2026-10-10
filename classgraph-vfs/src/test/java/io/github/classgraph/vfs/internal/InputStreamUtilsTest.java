package io.github.classgraph.vfs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link InputStreamUtils#readAtLeastOne}, which reads from a stream that may return zero from a read of a
 * non-empty range.
 */
public class InputStreamUtilsTest {
    /**
     * A stream that answers each call with the next value of a script. Calling a method more often than its script
     * allows throws {@link ArrayIndexOutOfBoundsException}, which fails the test.
     */
    private static final class ScriptedInputStream extends InputStream {
        /** What each call to {@link #read(byte[], int, int)} returns, in turn. */
        private final int[] bulkReadResults;

        /** What each call to {@link #read()} returns, in turn. */
        private final int[] singleByteReadResults;

        /** The number of calls to {@link #read(byte[], int, int)} so far. */
        private int numBulkReads;

        /** The number of calls to {@link #read()} so far. */
        int numSingleByteReads;

        /**
         * Constructor.
         *
         * @param bulkReadResults
         *            what each call to {@link #read(byte[], int, int)} returns, in turn.
         * @param singleByteReadResults
         *            what each call to {@link #read()} returns, in turn.
         */
        ScriptedInputStream(final int[] bulkReadResults, final int[] singleByteReadResults) {
            this.bulkReadResults = bulkReadResults;
            this.singleByteReadResults = singleByteReadResults;
        }

        @Override
        public int read() {
            return singleByteReadResults[numSingleByteReads++];
        }

        @Override
        public int read(final byte[] buf, final int off, final int len) {
            final var numBytesRead = bulkReadResults[numBulkReads++];
            // Fill the range with bytes that a test can recognize
            for (var i = 0; i < numBytesRead; i++) {
                buf[off + i] = (byte) ('a' + i);
            }
            return numBytesRead;
        }
    }

    /**
     * A zero from a read of a non-empty range is resolved by reading a single byte, which is put at the start of
     * the range, and counted as one byte read.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    public void aZeroIsResolvedByReadingASingleByte() throws IOException {
        final var stream = new ScriptedInputStream(new int[] { 0 }, new int[] { 0x41 });
        final var buf = new byte[8];
        assertThat(InputStreamUtils.readAtLeastOne(stream, buf, 3, 5)).isEqualTo(1);
        assertThat(buf).containsExactly(0, 0, 0, 0x41, 0, 0, 0, 0);
    }

    /**
     * A zero from a read of a non-empty range, followed by the end of the stream, is reported as the end of the
     * stream, so that a caller that reads until the end stops there rather than reading forever.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    public void aZeroAtTheEndOfTheStreamIsReportedAsTheEnd() throws IOException {
        final var stream = new ScriptedInputStream(new int[] { 0 }, new int[] { -1 });
        final var buf = new byte[8];
        assertThat(InputStreamUtils.readAtLeastOne(stream, buf, 0, buf.length)).isEqualTo(-1);
        assertThat(buf).containsOnly(0);
    }

    /**
     * A read that returns bytes, or the end of the stream, is passed through unchanged, without a single-byte read.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    public void aCountOrTheEndOfTheStreamIsPassedThrough() throws IOException {
        final var buf = new byte[8];
        final var stream = new ScriptedInputStream(new int[] { 3, -1 }, new int[0]);
        assertThat(InputStreamUtils.readAtLeastOne(stream, buf, 2, 6)).isEqualTo(3);
        assertThat(buf).containsExactly(0, 0, 'a', 'b', 'c', 0, 0, 0);
        assertThat(InputStreamUtils.readAtLeastOne(stream, buf, 5, 3)).isEqualTo(-1);
        assertThat(stream.numSingleByteReads).isZero();
    }

    /**
     * A read of an empty range returns zero, as {@link InputStream#read(byte[], int, int)} does, rather than
     * reading a byte that there is no room for.
     *
     * @throws IOException
     *             if the stream could not be read
     */
    @Test
    public void aReadOfAnEmptyRangeReturnsZero() throws IOException {
        final var stream = new ScriptedInputStream(new int[] { 0 }, new int[] { 0x41 });
        final var buf = new byte[8];
        assertThat(InputStreamUtils.readAtLeastOne(stream, buf, 4, 0)).isZero();
        assertThat(stream.numSingleByteReads).isZero();
        assertThat(buf).containsOnly(0);
    }
}
