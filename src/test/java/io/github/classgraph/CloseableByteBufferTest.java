package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link CloseableByteBuffer}, the wrapper that lets a memory-mapped {@link ByteBuffer} be unmapped in a
 * try-with-resources block.
 */
public class CloseableByteBufferTest {
    /** The wrapped buffer is returned until the wrapper is closed, then the close action runs and it is dropped. */
    @Test
    public void bufferIsReleasedOnClose() throws IOException {
        final var byteBuffer = ByteBuffer.allocate(8);
        final var numCloseActionsRun = new AtomicInteger();
        try (var closeableByteBuffer = new CloseableByteBuffer(byteBuffer, numCloseActionsRun::incrementAndGet)) {
            assertThat(closeableByteBuffer.getByteBuffer()).isSameAs(byteBuffer);
            assertThat(numCloseActionsRun).hasValue(0);
        }
        assertThat(numCloseActionsRun).hasValue(1);
    }

    /** Closing twice runs the close action only once, so a buffer cannot be unmapped twice. */
    @Test
    public void closeIsIdempotent() throws IOException {
        final var numCloseActionsRun = new AtomicInteger();
        final var closeableByteBuffer = new CloseableByteBuffer(ByteBuffer.allocate(8),
                numCloseActionsRun::incrementAndGet);
        closeableByteBuffer.close();
        closeableByteBuffer.close();
        assertThat(numCloseActionsRun).hasValue(1);
        assertThat(closeableByteBuffer.getByteBuffer()).isNull();
    }

    /**
     * An exception thrown while releasing the buffer does not propagate out of the try-with-resources block that is
     * closing it, since there is nothing the caller could do about it, and the buffer is dropped either way.
     */
    @Test
    public void exceptionWhileClosingIsNotPropagated() {
        final var closeableByteBuffer = new CloseableByteBuffer(ByteBuffer.allocate(8), () -> {
            throw new IllegalStateException("could not unmap");
        });
        assertThatCode(closeableByteBuffer::close).doesNotThrowAnyException();
        assertThat(closeableByteBuffer.getByteBuffer()).isNull();
    }
}
