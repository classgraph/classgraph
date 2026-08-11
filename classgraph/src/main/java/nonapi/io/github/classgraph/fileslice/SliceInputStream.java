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
 * Copyright (c) 2020 Luke Hutchison
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
package nonapi.io.github.classgraph.fileslice;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.Resource;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;
import nonapi.io.github.classgraph.utils.FileUtils;
import org.jspecify.annotations.Nullable;

/**
 * An {@link InputStream} that reads the raw (still-deflated, if the slice is a deflated zip entry) bytes of a
 * {@link Slice}, in order, through the slice's {@link RandomAccessReader}.
 */
class SliceInputStream extends InputStream {
    /** The slice being read. */
    private final Slice slice;

    /** The reader that the bytes are read through. */
    private final RandomAccessReader randomAccessReader;

    /** The {@link Resource} to close when this stream is closed, or null if none. */
    private final @Nullable Resource resourceToClose;

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
     *            the {@link Resource} to close when this stream is closed, or null if none
     */
    SliceInputStream(final Slice slice, final @Nullable Resource resourceToClose) {
        this.slice = slice;
        this.randomAccessReader = slice.randomAccessReader();
        this.resourceToClose = resourceToClose;
    }

    @Override
    public int read() throws IOException {
        if (closed.get()) {
            throw new IOException("Already closed");
        }
        // Return the byte value, not the number of bytes read (read() returns 1 when a byte was read)
        return read(byteBuf, 0, 1) < 1 ? -1 : byteBuf[0] & 0xff;
    }

    // InputStream's default implementation of this method is very slow -- it calls read() for every byte.
    // This method reads the maximum number of bytes possible in one call.
    @Override
    public int read(final byte[] buf, final int off, final int len) throws IOException {
        if (closed.get()) {
            throw new IOException("Already closed");
        } else if (len == 0) {
            return 0;
        }
        final var numBytesToRead = Math.min(len, available());
        if (numBytesToRead < 1) {
            return -1;
        }
        final var numBytesRead = randomAccessReader.read(currOff, buf, off, numBytesToRead);
        if (numBytesRead > 0) {
            currOff += numBytesRead;
        }
        return numBytesRead;
    }

    @Override
    public long skip(final long n) throws IOException {
        if (closed.get()) {
            throw new IOException("Already closed");
        }
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
        return (int) Math.min(Math.max(slice.sliceLength - currOff, 0L), FileUtils.MAX_BUFFER_SIZE);
    }

    @Override
    public synchronized void mark(final int readlimit) {
        // Ignore readlimit
        markOff = currOff;
    }

    @Override
    public synchronized void reset() {
        currOff = markOff;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public void close() {
        // Closing an already-closed InputStream has no effect, as required by InputStream#close() -- in particular
        // the Resource must not be closed a second time, since it may have been reopened in the meantime
        if (!closed.getAndSet(true) && resourceToClose != null) {
            try {
                resourceToClose.close();
            } catch (final Exception e) {
                // Ignore
            }
        }
    }
}
