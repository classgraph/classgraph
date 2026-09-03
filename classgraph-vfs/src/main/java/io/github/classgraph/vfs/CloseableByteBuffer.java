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
 * Closing the {@link VfsRoot} this buffer was read from, or the {@link Vfs} that opened that root, closes this
 * wrapper too, whether or not the caller has: the root closes every buffer it handed out as the step before it
 * releases what it owns, so that a buffer that is a view of a memory mapping stops holding that mapping open and
 * the file can be unmapped, and deleted if it was a temporary one, as the root closes. {@link #getByteBuffer()}
 * returns null from that point on.
 *
 * <p>
 * This is the one place in the {@link Vfs} API where reading after a close is not reported as an
 * {@link java.io.IOException}: this is a raw {@link ByteBuffer} handed to the caller, so nothing sits between it
 * and the caller to check whether the file is still there, and a reference to it taken before the close is not
 * revoked by the close. Reading through such a reference is a bug in the calling code, and what it does depends on
 * the JDK: on JDK 22 and later the read throws {@link IllegalStateException}, since the file is unmapped by closing
 * an arena that knows it has been closed, but below JDK 22 the only way to unmap a file frees the address range
 * without marking the buffer, and the read takes a SIGSEGV that kills the JVM. Read the buffer only inside the
 * try-with-resources block that holds this wrapper, and only while the root it came from is open.
 */
// TODO: drop the warning on getByteBuffer(), and the JDK-dependent half of the class javadoc, once the minimum
// supported JDK is 22 or later. The warning exists because below 22 a mapped file is unmapped by freeing its
// address range, so reading a ByteBuffer reference that was taken from this wrapper and kept past the close can
// kill the JVM -- on Windows, the only platform where a file is mapped. From 22 the file is mapped in an arena
// whose close makes such a read throw IllegalStateException instead, leaving nothing to warn about. This class is
// still wanted then: it releases the buffer as soon as the caller is finished with it, and no later than the close
// of the root that produced it, so that a mapping is dropped promptly and the file behind it can be deleted.
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
     * The {@link VfsRoot} that produced this buffer and closes it if it is still open when that root closes, or
     * null for a buffer that no root is tracking.
     */
    private final @Nullable VfsRoot owner;

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
        this(byteBuffer, onClose, /* owner = */ null);
    }

    /**
     * A wrapper for {@link ByteBuffer} that the given {@link VfsRoot} tracks, so that closing that root closes this
     * wrapper if the caller has not.
     *
     * @param byteBuffer
     *            The {@link ByteBuffer} to wrap.
     * @param onClose
     *            The method to run when {@link #close()} is called.
     * @param owner
     *            The root that produced the buffer, or null if no root is tracking it. The caller registers the
     *            wrapper with the root -- this constructor does not, so that the wrapper is not published before it
     *            is built.
     */
    CloseableByteBuffer(final ByteBuffer byteBuffer, final Runnable onClose, final @Nullable VfsRoot owner) {
        this.byteBuffer = byteBuffer;
        this.onClose = new AtomicReference<>(onClose);
        this.owner = owner;
    }

    /**
     * Get the wrapped {@link ByteBuffer}.
     *
     * <p>
     * <b>The returned {@link ByteBuffer} must not be read after this wrapper is closed, or after the
     * {@link VfsRoot} it was read from or the {@link Vfs} that opened that root is closed</b> -- any of those
     * closes releases the buffer, and the buffer may be a memory mapping of a file rather than a copy of its
     * content. This method returns null once any of them has happened, but a reference taken before that is not
     * revoked, and reading through one is undefined.
     *
     * <p>
     * On JDK 22 and later such a read throws {@link IllegalStateException}. Below JDK 22 there is no way to unmap a
     * file that marks the buffers that read it, so the read takes a SIGSEGV that kills the JVM instead. That only
     * arises where the file was memory-mapped, which is on Windows: files are read through the file channel API on
     * every other platform, so the buffer is a copy there and reading it late is merely wrong rather than fatal. Do
     * not rely on that -- read the buffer inside the try-with-resources block that holds this wrapper.
     *
     * @return The wrapped {@link ByteBuffer}, or null if this wrapper, the root it was read from, or the
     *         {@link Vfs} that opened that root has been closed.
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
            } catch (final Exception e) {
                // There is nowhere to report this: close() cannot throw, since a caller closing a buffer in a
                // try-with-resources block would then lose whatever exception the block itself threw. The close
                // action is responsible for leaving nothing checked out when the part of it that can fail fails.
            }
        }
    }
}
