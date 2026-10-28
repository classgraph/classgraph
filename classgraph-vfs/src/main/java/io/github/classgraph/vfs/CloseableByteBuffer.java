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

import io.github.classgraph.base.internal.utils.Assert;

/**
 * An {@link AutoCloseable} wrapper for a {@link ByteBuffer} that releases its underlying resources when closed.
 *
 * <p>
 * Closing this wrapper is idempotent and thread-safe. If this buffer was obtained from a {@link VfsRoot}, closing
 * that {@link VfsRoot} or its owning {@link Vfs} also closes this wrapper. Once closed, {@link #getByteBuffer()}
 * returns {@code null}.
 *
 * <p>
 * The wrapped {@link ByteBuffer} may be backed by a memory-mapped file and must only be accessed while this
 * wrapper, its originating {@link VfsRoot}, and the owning {@link Vfs} remain open; reading a retained
 * {@link ByteBuffer} reference after closure may throw {@link IllegalStateException}.
 */
public final class CloseableByteBuffer implements AutoCloseable {
    /** The wrapped {@link ByteBuffer}, or {@code null} once closed. */
    private volatile @Nullable ByteBuffer byteBuffer;

    /** The close callback, cleared atomically when {@link #close()} runs. */
    private final AtomicReference<@Nullable Runnable> onClose;

    /** The {@link VfsRoot} tracking this wrapper, or {@code null} if untracked. */
    private final @Nullable VfsRoot owner;

    /** The inner {@link CloseableByteBuffer} wrapped by this instance, or {@code null} if none. */
    private final @Nullable CloseableByteBuffer inner;

    /**
     * Wrap a {@link ByteBuffer} with a close callback.
     *
     * @param byteBuffer
     *            the {@link ByteBuffer} to wrap.
     * @param onClose
     *            the callback to run when {@link #close()} is called.
     */
    public CloseableByteBuffer(final ByteBuffer byteBuffer, final Runnable onClose) {
        this(byteBuffer, onClose, /* owner = */ null);
    }

    /**
     * Wrap a {@link ByteBuffer} tracked by a {@link VfsRoot}.
     *
     * @param byteBuffer
     *            the {@link ByteBuffer} to wrap.
     * @param onClose
     *            the callback to run when {@link #close()} is called.
     * @param owner
     *            the owning {@link VfsRoot}, or {@code null} if untracked.
     */
    CloseableByteBuffer(final ByteBuffer byteBuffer, final Runnable onClose, final @Nullable VfsRoot owner) {
        Assert.notNull(byteBuffer, "byteBuffer");
        Assert.notNull(onClose, "onClose");
        this.byteBuffer = byteBuffer;
        this.onClose = new AtomicReference<>(onClose);
        this.owner = owner;
        this.inner = null;
    }

    /**
     * Wrap the {@link ByteBuffer} of another open {@link CloseableByteBuffer} with an additional close callback.
     * Closing {@code inner} (or its owning {@link VfsRoot} or {@link Vfs}) also causes {@link #getByteBuffer()} on
     * this wrapper to return {@code null}; closing this wrapper does not close {@code inner} unless {@code onClose}
     * does so.
     *
     * @param inner
     *            the open wrapper whose buffer to wrap.
     * @param onClose
     *            the callback to run when {@link #close()} is called.
     * @throws IllegalArgumentException
     *             if {@code inner} is already closed.
     */
    public CloseableByteBuffer(final CloseableByteBuffer inner, final Runnable onClose) {
        Assert.notNull(inner, "inner");
        Assert.notNull(onClose, "onClose");
        final var innerByteBuffer = inner.getByteBuffer();
        if (innerByteBuffer == null) {
            throw new IllegalArgumentException("The wrapper to wrap has already been closed");
        }
        this.byteBuffer = innerByteBuffer;
        this.onClose = new AtomicReference<>(onClose);
        this.owner = null;
        this.inner = inner;
    }

    /**
     * Return the wrapped {@link ByteBuffer}, or {@code null} if this wrapper (or any wrapping/owning resource) has
     * been closed. The returned buffer must not be accessed after this wrapper, its {@link VfsRoot}, or its
     * {@link Vfs} is closed.
     *
     * @return the wrapped {@link ByteBuffer}, or {@code null} if closed.
     */
    public @Nullable ByteBuffer getByteBuffer() {
        final var innerWrapper = inner;
        return innerWrapper != null && innerWrapper.getByteBuffer() == null ? null : byteBuffer;
    }

    /** Release the wrapped {@link ByteBuffer} and run the close callback if not already closed. */
    @Override
    public void close() {
        // Take the close action out atomically, so that a second call (or a concurrent one) runs it zero times
        // rather than twice -- releasing the same buffer twice would hand the same memory out to two readers
        final var onCloseRunnable = onClose.getAndSet(null);
        byteBuffer = null;
        final var ownerRoot = owner;
        if (ownerRoot != null) {
            // Stop the root tracking this buffer, so that a caller that closes its buffers does not accumulate
            // them on the root for as long as it stays open. Harmless if the root is the one doing the closing,
            // since it drains its set before it closes what was in it
            ownerRoot.untrackOpenHandle(this);
        }
        if (onCloseRunnable != null) {
            try {
                onCloseRunnable.run();
            } catch (final Exception _) {
                // There is nowhere to report this: close() cannot throw, since a caller closing a buffer in a
                // try-with-resources block would then lose whatever exception the block itself threw. The close
                // action is responsible for leaving nothing checked out when the part of it that can fail fails.
            }
        }
    }
}
