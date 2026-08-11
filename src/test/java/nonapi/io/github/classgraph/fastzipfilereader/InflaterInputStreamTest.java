package nonapi.io.github.classgraph.fastzipfilereader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/** Tests for the inflating {@link InputStream} returned by {@link NestedJarHandler#openInflaterInputStream}. */
public class InflaterInputStreamTest {
    /** Some compressible test data. */
    private static byte[] rawBytes() {
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            buf.append("the quick brown fox jumps over the lazy dog ").append(i).append('\n');
        }
        return buf.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Deflate the given bytes with the "nowrap" option, as zip entry data is deflated. */
    private static byte[] deflate(final byte[] rawBytes) {
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, /* nowrap = */ true);
        try {
            deflater.setInput(rawBytes);
            deflater.finish();
            final byte[] deflatedBuf = new byte[rawBytes.length * 2 + 32];
            final int deflatedLen = deflater.deflate(deflatedBuf);
            return Arrays.copyOf(deflatedBuf, deflatedLen);
        } finally {
            deflater.end();
        }
    }

    private static NestedJarHandler nestedJarHandler() {
        return new NestedJarHandler(new ScanSpec(), new InterruptionChecker(), new ReflectionUtils());
    }

    /** Read the given deflated bytes back through the inflating {@link InputStream}. */
    private static byte[] inflate(final byte[] deflatedBytes) throws IOException {
        final NestedJarHandler nestedJarHandler = nestedJarHandler();
        try (InputStream inflaterInputStream = nestedJarHandler
                .openInflaterInputStream(new ByteArrayInputStream(deflatedBytes))) {
            final ByteArrayOutputStream inflatedBytes = new ByteArrayOutputStream();
            final byte[] readBuf = new byte[1024];
            for (int numBytesRead; (numBytesRead = inflaterInputStream.read(readBuf, 0, readBuf.length)) > 0;) {
                inflatedBytes.write(readBuf, 0, numBytesRead);
                if (inflatedBytes.size() > 100 * 1024 * 1024) {
                    // Stop before running out of memory, if the stream never reaches its end
                    throw new IOException("Inflated far more bytes than were deflated");
                }
            }
            return inflatedBytes.toByteArray();
        } finally {
            nestedJarHandler.close(null);
        }
    }

    /** A complete deflated stream must inflate back to the original bytes. */
    @Test
    public void completeStreamIsInflated() throws IOException {
        final byte[] rawBytes = rawBytes();
        assertThat(inflate(deflate(rawBytes))).isEqualTo(rawBytes);
    }

    /**
     * A deflated stream that has been truncated must throw, rather than looping forever. The "nowrap" option
     * requires a dummy byte at the end of the input, and if that byte is supplied afresh every time the inflater
     * asks for more input, the read loop never terminates.
     *
     * <p>
     * The timeout runs the test in a separate thread, since a same-thread timeout is only checked once the test
     * method returns, which a non-terminating loop never does.
     */
    @Test
    @Timeout(value = 60, threadMode = ThreadMode.SEPARATE_THREAD)
    public void truncatedStreamThrowsRatherThanLoopingForever() {
        final byte[] deflatedBytes = deflate(rawBytes());
        // Truncating a deflated stream close to its end leaves the inflater wanting more input after every
        // byte of the stream has been consumed; truncating it close to its start instead tends to produce
        // invalid deflate data, which throws a DataFormatException
        for (final int truncatedLength : new int[] { deflatedBytes.length - 1, deflatedBytes.length - 5,
                deflatedBytes.length / 2 }) {
            final byte[] truncatedBytes = Arrays.copyOf(deflatedBytes, truncatedLength);
            assertThatThrownBy(new ThrowingCallable() {
                @Override
                public void call() throws Throwable {
                    inflate(truncatedBytes);
                }
            }).isInstanceOf(EOFException.class);
        }
    }

    /**
     * {@link InputStream#mark(int)} has to be a no-op when mark is not supported, and {@link InputStream#reset()}
     * has to throw an {@link IOException}, not an unchecked exception.
     */
    @Test
    public void markIsANoOpAndResetThrowsIOException() throws IOException {
        final NestedJarHandler nestedJarHandler = nestedJarHandler();
        try (InputStream inflaterInputStream = nestedJarHandler
                .openInflaterInputStream(new ByteArrayInputStream(deflate(rawBytes())))) {
            assertThat(inflaterInputStream.markSupported()).isFalse();
            inflaterInputStream.mark(1024);
            assertThatThrownBy(new ThrowingCallable() {
                @Override
                public void call() throws Throwable {
                    inflaterInputStream.reset();
                }
            }).isInstanceOf(IOException.class);
        } finally {
            nestedJarHandler.close(null);
        }
    }
}
