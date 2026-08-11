package io.github.classgraph.vfs.internal.slice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.internal.ScanResources;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;

/**
 * Tests for the {@link InputStream} that {@link Slice#open()} returns, which is what a resource's content is read
 * through.
 */
public class SliceInputStreamTest {
    /** The content of the slice. The high byte checks that a byte value is read unsigned. */
    private static final byte[] CONTENT = { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, (byte) 0xFE,
            (byte) 0xFF };

    /**
     * Create a slice over {@link #CONTENT}.
     *
     * @return the slice
     */
    private static Slice slice() {
        final var scanResources = new ScanResources(new VfsScanSpec(), new InterruptionChecker());
        return new ArraySlice(CONTENT, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L,
                scanResources);
    }

    /**
     * The bytes of the slice are read in order, and each byte is returned as an unsigned value, so that a byte of
     * 0xFF is not mistaken for the end of the stream.
     *
     * @throws IOException
     *             if the slice could not be read
     */
    @Test
    public void everyByteIsReadInOrderAsAnUnsignedValue() throws IOException {
        try (var inputStream = slice().open()) {
            for (final byte expected : CONTENT) {
                assertThat(inputStream.read()).isEqualTo(expected & 0xff);
            }
            // The end of the slice is the end of the stream
            assertThat(inputStream.read()).isEqualTo(-1);
        }
    }

    /**
     * A bulk read writes to the requested range of the destination array, and stops at the end of the slice.
     *
     * @throws IOException
     *             if the slice could not be read
     */
    @Test
    public void aBulkReadFillsTheRequestedRangeOfTheDestination() throws IOException {
        try (var inputStream = slice().open()) {
            // Reading nothing succeeds without touching the destination
            assertThat(inputStream.read(new byte[0], 0, 0)).isZero();

            final var buf = new byte[6];
            assertThat(inputStream.read(buf, 1, 4)).isEqualTo(4);
            assertThat(buf).containsExactly(0x00, 0x00, 0x01, 0x02, 0x03, 0x00);

            // A read of more bytes than the slice has left returns only the bytes that are left
            assertThat(inputStream.read(new byte[CONTENT.length], 0, CONTENT.length)).isEqualTo(CONTENT.length - 4);
            assertThat(inputStream.read(new byte[1], 0, 1)).isEqualTo(-1);
        }
    }

    /**
     * Skipping moves the read position forwards only, and stops at the end of the slice.
     *
     * @throws IOException
     *             if the slice could not be read
     */
    @Test
    public void skippingStopsAtTheEndOfTheSlice() throws IOException {
        try (var inputStream = slice().open()) {
            // A non-positive skip does not seek backwards
            assertThat(inputStream.skip(0L)).isZero();
            assertThat(inputStream.skip(-5L)).isZero();

            assertThat(inputStream.skip(8L)).isEqualTo(8L);
            assertThat(inputStream.read()).isEqualTo(0xFE);

            // A skip past the end of the slice skips only the bytes that are left, and does not overflow
            assertThat(inputStream.skip(Long.MAX_VALUE)).isEqualTo(1L);
            assertThat(inputStream.skip(1L)).isZero();
            assertThat(inputStream.read()).isEqualTo(-1);
        }
    }

    /**
     * The number of available bytes is the number of bytes of the slice that have not been read yet, which is what
     * callers size their read buffers from.
     *
     * @throws IOException
     *             if the slice could not be read
     */
    @Test
    public void theAvailableBytesAreTheUnreadBytesOfTheSlice() throws IOException {
        try (var inputStream = slice().open()) {
            assertThat(inputStream.available()).isEqualTo(CONTENT.length);
            assertThat(inputStream.skip(4L)).isEqualTo(4L);
            assertThat(inputStream.available()).isEqualTo(CONTENT.length - 4);
            assertThat(inputStream.skip(CONTENT.length)).isEqualTo(CONTENT.length - 4L);
            assertThat(inputStream.available()).isZero();
        }
    }

    /**
     * A marked position can be returned to, so that a caller can look ahead at the bytes of a resource and then
     * read them again.
     *
     * @throws IOException
     *             if the slice could not be read
     */
    @Test
    public void aMarkedPositionCanBeReturnedTo() throws IOException {
        try (var inputStream = slice().open()) {
            assertThat(inputStream.markSupported()).isTrue();
            assertThat(inputStream.skip(4L)).isEqualTo(4L);
            inputStream.mark(/* readlimit = */ 1);

            assertThat(inputStream.read()).isEqualTo(0x04);
            assertThat(inputStream.read()).isEqualTo(0x05);
            inputStream.reset();
            assertThat(inputStream.read()).isEqualTo(0x04);

            // Resetting without a mark returns to the start of the slice
            try (var unmarked = slice().open()) {
                assertThat(unmarked.skip(4L)).isEqualTo(4L);
                unmarked.reset();
                assertThat(unmarked.read()).isEqualTo(0x00);
            }
        }
    }

    /**
     * Closing the stream closes the resource that the slice was read from, but only once, since the resource may
     * have been reopened by the time the stream is closed a second time.
     *
     * @throws IOException
     *             if the slice could not be read
     */
    @Test
    public void closingTheStreamClosesTheResourceItWasReadFromOnlyOnce() throws IOException {
        final var closeCount = new AtomicInteger();
        final Closeable resourceToClose = closeCount::incrementAndGet;

        final var inputStream = slice().open(resourceToClose);
        assertThat(inputStream.read()).isEqualTo(0x00);
        inputStream.close();
        assertThat(closeCount).hasValue(1);

        // Closing again has no effect, as InputStream#close() requires
        inputStream.close();
        assertThat(closeCount).hasValue(1);

        // Reading a closed stream fails, rather than reading a resource that has been handed back
        assertThatThrownBy(inputStream::read).isInstanceOf(IOException.class).hasMessage("Already closed");
        assertThatThrownBy(() -> inputStream.read(new byte[1], 0, 1)).isInstanceOf(IOException.class)
                .hasMessage("Already closed");
        assertThatThrownBy(() -> inputStream.skip(1L)).isInstanceOf(IOException.class).hasMessage("Already closed");
    }

    /**
     * A stream over a sub-slice reads only the bytes of the sub-slice, not the bytes of the parent slice that
     * surround it.
     *
     * @throws IOException
     *             if the slice could not be read
     */
    @Test
    public void aStreamOverASubSliceReadsOnlyTheBytesOfTheSubSlice() throws IOException {
        final var subSlice = slice().slice(4, 3, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L);
        try (var inputStream = subSlice.open()) {
            assertThat(inputStream.available()).isEqualTo(3);
            final var buf = new byte[CONTENT.length];
            assertThat(inputStream.read(buf, 0, buf.length)).isEqualTo(3);
            assertThat(new String(buf, 0, 3, StandardCharsets.ISO_8859_1))
                    .isEqualTo(new String(CONTENT, 4, 3, StandardCharsets.ISO_8859_1));
            assertThat(inputStream.read()).isEqualTo(-1);
        }
    }
}
