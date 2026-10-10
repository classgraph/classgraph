package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    public void bufferIsReleasedOnClose() {
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
    public void closeIsIdempotent() {
        final var numCloseActionsRun = new AtomicInteger();
        final var closeableByteBuffer = new CloseableByteBuffer(ByteBuffer.allocate(8),
                numCloseActionsRun::incrementAndGet);
        closeableByteBuffer.close();
        closeableByteBuffer.close();
        assertThat(numCloseActionsRun).hasValue(1);
        assertThat(closeableByteBuffer.getByteBuffer()).isNull();
    }

    /**
     * A wrapper built on another wrapper returns the same buffer, reports that it is closed once the other wrapper
     * is closed, and runs its own close action once when it is closed.
     */
    @Test
    public void aWrapperOfAWrapperIsClosedWhenTheInnerOneIsClosed() {
        final var byteBuffer = ByteBuffer.allocate(8);
        final var numInnerCloseActionsRun = new AtomicInteger();
        final var numOuterCloseActionsRun = new AtomicInteger();
        final var inner = new CloseableByteBuffer(byteBuffer, numInnerCloseActionsRun::incrementAndGet);
        final var outer = new CloseableByteBuffer(inner, numOuterCloseActionsRun::incrementAndGet);
        assertThat(outer.getByteBuffer()).isSameAs(byteBuffer);

        inner.close();
        assertThat(outer.getByteBuffer()).isNull();
        assertThat(numOuterCloseActionsRun).hasValue(0);

        outer.close();
        outer.close();
        assertThat(numOuterCloseActionsRun).hasValue(1);
        assertThat(numInnerCloseActionsRun).hasValue(1);

        // A wrapper cannot be built on one that has already been closed, since it has no buffer to wrap
        assertThatThrownBy(() -> new CloseableByteBuffer(inner, () -> {
            // Nothing to release
        })).isInstanceOf(IllegalArgumentException.class);
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

    /**
     * A null buffer, wrapper to wrap or close action is rejected when the wrapper is built. A null buffer would
     * otherwise be indistinguishable from a closed wrapper, since {@link CloseableByteBuffer#getByteBuffer()}
     * returns null for both.
     */
    @Test
    public void nullArgumentsAreRejected() {
        assertThatNullPointerException().isThrownBy(() -> new CloseableByteBuffer((ByteBuffer) null, () -> {
        })).withMessage("byteBuffer must not be null");
        assertThatNullPointerException().isThrownBy(() -> new CloseableByteBuffer(ByteBuffer.allocate(8), null))
                .withMessage("onClose must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new CloseableByteBuffer((CloseableByteBuffer) null, () -> {
                })).withMessage("inner must not be null");
        final var inner = new CloseableByteBuffer(ByteBuffer.allocate(8), () -> {
        });
        assertThatNullPointerException().isThrownBy(() -> new CloseableByteBuffer(inner, null))
                .withMessage("onClose must not be null");
    }
}
