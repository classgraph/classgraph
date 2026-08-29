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
package io.github.classgraph.vfs.internal.slice;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.vfs.reader.RandomAccessReader;
import org.jspecify.annotations.Nullable;

/**
 * An {@link InputStream} that reads the raw (still-deflated, if the slice is a deflated zip entry) bytes of a
 * {@link Slice}, in order, through the slice's {@link RandomAccessReader}.
 */
class SliceInputStream extends InputStream {
    /** The slice being read. */
    private final Slice slice;

    /**
     * The reader that the bytes are read through, or null once this stream has been closed. A reader of a
     * memory-mapped file holds a duplicate of the mapped buffer, so this reference is dropped as the stream closes
     * rather than being kept for as long as anything still refers to the stream -- below JDK 22 the file is
     * unmapped by freeing its address range, so a duplicate that outlived the unmapping would be a view of memory
     * that is no longer there.
     */
    // #939
    private volatile @Nullable RandomAccessReader randomAccessReader;

    /** The {@link AutoCloseable} to close when this stream is closed, or null if none. */
    private final @Nullable AutoCloseable resourceToClose;

    /** The current read position, relative to the start of the slice. */
    private long currOff;

    /** The read position recorded by the last call to {@link #mark(int)}. */
    private long markOff;

    /** A destination buffer for the single-byte {@link #read()} method. */
    private final byte[] byteBuf = new byte[1];

    /** True once this stream has been closed. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Constructor.
     *
     * @param slice
     *            the slice to read
     * @param resourceToClose
     *            the {@link AutoCloseable} to close when this stream is closed, or null if none
     * @throws IOException
     *             if the slice has been closed, so that there is nothing left to read through.
     */
    SliceInputStream(final Slice slice, final @Nullable AutoCloseable resourceToClose) throws IOException {
        this.slice = slice;
        this.randomAccessReader = slice.randomAccessReader();
        this.resourceToClose = resourceToClose;
    }

    /**
     * Check that this stream, and the {@link io.github.classgraph.vfs.Vfs} that the slice belongs to, are both
     * still open, and return the reader to read through.
     *
     * @return the reader that the bytes are read through
     * @throws IOException
     *             if either has been closed.
     */
    private RandomAccessReader checkOpen() throws IOException {
        // Read the field into a local, so that a close running concurrently cannot null it between the check and
        // the use
        final var reader = randomAccessReader;
        if (closed.get() || reader == null) {
            throw new IOException("Already closed");
        }
        // A stream that is still open can still be reading a file that the Vfs has released, so the session is
        // checked too, rather than only this stream's own state
        if (slice.session.isClosed()) {
            throw new IOException("Cannot read a file after the Vfs has been closed");
        }
        return reader;
    }

    @Override
    public int read() throws IOException {
        checkOpen();
        // Return the byte value, not the number of bytes read (read() returns 1 when a byte was read)
        return read(byteBuf, 0, 1) < 1 ? -1 : byteBuf[0] & 0xff;
    }

    // InputStream's default implementation of this method is very slow -- it calls read() for every byte.
    // This method reads the maximum number of bytes possible in one call.
    @Override
    public int read(final byte[] buf, final int off, final int len) throws IOException {
        final var reader = checkOpen();
        // InputStream#read(byte[], int, int) requires these to be checked before anything is read
        Objects.checkFromIndexSize(off, len, buf.length);
        if (len == 0) {
            return 0;
        }
        final var numBytesToRead = Math.min(len, available());
        if (numBytesToRead < 1) {
            return -1;
        }
        final var numBytesRead = reader.read(currOff, buf, off, numBytesToRead);
        if (numBytesRead > 0) {
            currOff += numBytesRead;
        }
        return numBytesRead;
    }

    @Override
    public long skip(final long n) throws IOException {
        checkOpen();
        if (n <= 0L) {
            // InputStream#skip returns 0 for a non-positive argument, rather than seeking backwards
            return 0L;
        }
        // (Compute the number of remaining bytes first, rather than testing currOff + n, so that a huge n
        // cannot overflow to a negative value)
        final var numBytesToSkip = Math.min(n, slice.sliceLength - currOff);
        currOff += numBytesToSkip;
        return numBytesToSkip;
    }

    @Override
    public int available() {
        return (int) Math.min(Math.max(slice.sliceLength - currOff, 0L), Slice.MAX_BUFFER_SIZE);
    }

    @Override
    public synchronized void mark(final int readlimit) {
        // Ignore readlimit
        markOff = currOff;
    }

    @Override
    public synchronized void reset() throws IOException {
        checkOpen();
        currOff = markOff;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public void close() {
        // Closing an already-closed InputStream has no effect, as required by InputStream#close() -- in particular
        // the owner must not be closed a second time, since it may have been reopened in the meantime
        if (!closed.getAndSet(true)) {
            // Drop the reader, and with it its duplicate of the buffer of the slice, which may be a mapping
            // #939
            randomAccessReader = null;
            if (resourceToClose != null) {
                try {
                    resourceToClose.close();
                } catch (final Exception e) {
                    // Ignore
                }
            }
        }
    }
}
