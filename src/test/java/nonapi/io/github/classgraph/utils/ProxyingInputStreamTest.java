package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/** Tests that {@link ProxyingInputStream} delegates every method to the stream it wraps. */
public class ProxyingInputStreamTest {
    /** The bytes read by the tests. */
    private static final byte[] CONTENT = { 10, 20, 30, 40, 50 };

    /**
     * Wrap a fresh stream over {@link #CONTENT}.
     *
     * @return the wrapped stream.
     */
    private static ProxyingInputStream proxy() {
        return new ProxyingInputStream(new ByteArrayInputStream(CONTENT));
    }

    /** An {@link InputStream} that counts how many times it has been closed. */
    private static class CloseCountingInputStream extends ByteArrayInputStream {
        /** The number of times {@link #close()} has been called. */
        int numCloseCalls;

        /** Constructor. */
        CloseCountingInputStream() {
            super(CONTENT);
        }

        @Override
        public void close() throws IOException {
            numCloseCalls++;
            super.close();
        }
    }

    /** The three {@code read} methods read from the wrapped stream. */
    @Test
    public void readMethodsAreDelegated() throws IOException {
        try (var in = proxy()) {
            assertThat(in.read()).isEqualTo(10);

            final var buf = new byte[2];
            assertThat(in.read(buf)).isEqualTo(2);
            assertThat(buf).containsExactly(20, 30);

            final var offsetBuf = new byte[4];
            assertThat(in.read(offsetBuf, 1, 2)).isEqualTo(2);
            assertThat(offsetBuf).containsExactly(0, 40, 50, 0);

            assertThat(in.read()).isEqualTo(-1);
        }
    }

    /** The bulk read methods read from the wrapped stream. */
    @Test
    public void bulkReadMethodsAreDelegated() throws IOException {
        try (var in = proxy()) {
            assertThat(in.readAllBytes()).containsExactly(CONTENT);
        }
        try (var in = proxy()) {
            assertThat(in.readNBytes(3)).containsExactly(10, 20, 30);
            final var buf = new byte[4];
            assertThat(in.readNBytes(buf, 1, 3)).isEqualTo(2);
            assertThat(buf).containsExactly(0, 40, 50, 0);
        }
    }

    /** {@code available()} reports the number of bytes left in the wrapped stream. */
    @Test
    public void availableIsDelegated() throws IOException {
        try (var in = proxy()) {
            assertThat(in.available()).isEqualTo(CONTENT.length);
            assertThat(in.read()).isEqualTo(10);
            assertThat(in.available()).isEqualTo(CONTENT.length - 1);
        }
    }

    /** Marking and resetting act on the wrapped stream. */
    @Test
    public void markAndResetAreDelegated() throws IOException {
        try (var in = proxy()) {
            assertThat(in.markSupported()).isTrue();
            assertThat(in.read()).isEqualTo(10);
            in.mark(CONTENT.length);
            assertThat(in.read()).isEqualTo(20);
            in.reset();
            assertThat(in.read()).isEqualTo(20);
        }
        // markSupported() is delegated, so a wrapped stream that does not support marking is reported as such
        try (var in = new ProxyingInputStream(InputStream.nullInputStream() /* does not support mark */)) {
            assertThat(in.markSupported()).isFalse();
        }
    }

    /** Both skip methods skip in the wrapped stream. */
    @Test
    public void skipMethodsAreDelegated() throws IOException {
        try (var in = proxy()) {
            assertThat(in.skip(2)).isEqualTo(2);
            assertThat(in.read()).isEqualTo(30);
            in.skipNBytes(1);
            assertThat(in.read()).isEqualTo(50);
            // skipNBytes(), unlike skip(), fails rather than skipping fewer bytes than asked for
            assertThatThrownBy(() -> in.skipNBytes(1)).isInstanceOf(EOFException.class);
        }
    }

    /** {@code transferTo()} transfers the wrapped stream's contents. */
    @Test
    public void transferToIsDelegated() throws IOException {
        try (var in = proxy(); var out = new ByteArrayOutputStream()) {
            assertThat(in.transferTo(out)).isEqualTo(CONTENT.length);
            assertThat(out.toByteArray()).containsExactly(CONTENT);
        }
    }

    /**
     * {@code toString()} is the wrapped stream's, so that a log line names the stream that is really being read.
     */
    @Test
    public void toStringIsDelegated() throws IOException {
        final var wrapped = new ByteArrayInputStream(CONTENT);
        try (var in = new ProxyingInputStream(wrapped)) {
            assertThat(in).hasToString(wrapped.toString());
        }
    }

    /** The wrapped stream is closed once, however many times the proxy is closed. */
    @Test
    public void theWrappedStreamIsOnlyClosedOnce() throws IOException {
        final var wrapped = new CloseCountingInputStream();
        final var in = new ProxyingInputStream(wrapped);
        in.close();
        in.close();
        assertThat(wrapped.numCloseCalls).isEqualTo(1);
    }

    /**
     * The wrapped stream is closed once even when {@code close()} is re-entered, which is what happens in
     * {@link io.github.classgraph.ClasspathElementModule}: the stream's {@code close()} closes the
     * {@link io.github.classgraph.Resource} that owns it, and closing that {@code Resource} closes the stream
     * again.
     */
    @Test
    public void theWrappedStreamIsOnlyClosedOnceWhenCloseIsReentered() throws IOException {
        final var wrapped = new CloseCountingInputStream();
        // Stands in for the Resource that owns the stream, which is only closed once
        final var ownerClosed = new AtomicBoolean();
        final var in = new ProxyingInputStream(wrapped) {
            @Override
            public void close() throws IOException {
                super.close();
                if (ownerClosed.compareAndSet(false, true)) {
                    // Closing the owner closes this stream again
                    close();
                }
            }
        };
        in.close();
        assertThat(wrapped.numCloseCalls).isEqualTo(1);
    }
}
