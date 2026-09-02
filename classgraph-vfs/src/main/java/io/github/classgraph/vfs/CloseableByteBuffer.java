/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

/**
 * A wrapper for {@link ByteBuffer} that implements the {@link AutoCloseable} interface, releasing the
 * {@link ByteBuffer} when it is no longer needed.
 *
 * <p>
 * Closing an already-closed wrapper has no effect, and two threads closing at once release the buffer once between
 * them.
 *
 * <p>
 * The wrapped buffer may be a view of a memory mapping of a file, so it must only be read while this wrapper is
 * open, and while the {@link VfsRoot} it was read from, and the {@link Vfs} that opened that root, are open.
 * {@link #getByteBuffer()} returns null once this wrapper has been closed; a reference to the buffer taken before
 * that close, or one that outlives {@link VfsRoot#close()} or {@link Vfs#close()}, reads memory that the file may
 * no longer be mapped into.
 *
 * <p>
 * This is the one place in the {@link Vfs} API where reading after a close is not reported as an
 * {@link java.io.IOException}: this is a raw {@link ByteBuffer} handed to the caller, so nothing sits between it
 * and the caller to check whether the file is still there. On JDK 22 and later such a read throws
 * {@link IllegalStateException}, since the file is unmapped by closing an arena that knows it has been closed;
 * below JDK 22 unmapping frees the address range without marking the buffer, so the read is undefined -- which is
 * why an open wrapper holds the mapping open on those JDK versions until it is closed.
 */
public final class CloseableByteBuffer implements AutoCloseable {
    /**
     * The wrapped {@link ByteBuffer}, or null once this wrapper has been closed.
     */
    private volatile @Nullable ByteBuffer byteBuffer;

    /**
     * The method to run on close, or null if this wrapper has already been closed. Held in an
     * {@link AtomicReference}, so that only the thread that takes it out runs it.
     */
    private final AtomicReference<@Nullable Runnable> onClose;

    /**
     * A wrapper for {@link ByteBuffer} that implements the {@link AutoCloseable} interface, releasing the
     * {@link ByteBuffer} when it is no longer needed.
     *
     * @param byteBuffer
     *            The {@link ByteBuffer} to wrap
     * @param onClose
     *            The method to run when {@link #close()} is called.
     */
    public CloseableByteBuffer(final ByteBuffer byteBuffer, final Runnable onClose) {
        this.byteBuffer = byteBuffer;
        this.onClose = new AtomicReference<>(onClose);
    }

    /**
     * Get the wrapped ByteBuffer.
     *
     * @return The wrapped {@link ByteBuffer}, or null if this wrapper has been closed.
     */
    public @Nullable ByteBuffer getByteBuffer() {
        return byteBuffer;
    }

    /** Release the wrapped {@link ByteBuffer}. */
    @Override
    public void close() {
        // Take the close action out atomically, so that a second call (or a concurrent one) runs it zero times
        // rather than twice -- releasing the same buffer twice would hand the same memory out to two readers
        final var onCloseRunnable = onClose.getAndSet(null);
        byteBuffer = null;
        if (onCloseRunnable != null) {
            try {
                onCloseRunnable.run();
            } catch (final Exception e) {
                // There is nowhere to report this: close() cannot throw, since a caller closing a buffer in a
                // try-with-resources block would then lose whatever exception the block itself threw. The close
                // action is responsible for leaving nothing checked out when the part of it that can fail fails.
            }
        }
    }
}
