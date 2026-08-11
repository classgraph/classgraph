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
 * Copyright (c) 2019 Luke Hutchison
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
package io.github.classgraph.vfs.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import io.github.classgraph.base.internal.recycler.Recycler;

/**
 * An {@link InputStream} that inflates a stream of deflated zip entry data, using an {@link Inflater} borrowed from
 * a {@link Recycler} and handed back to it when this stream is closed.
 */
class RecycledInflaterInputStream extends InputStream {
    /** The stream of deflated bytes. */
    private final InputStream rawInputStream;

    /** The recycler to hand the {@link Inflater} back to when this stream is closed. */
    private final Recycler<RecyclableInflater, RuntimeException> inflaterRecycler;

    /** The borrowed {@link Inflater} wrapper. */
    private final RecyclableInflater recyclableInflater;

    /** The borrowed {@link Inflater}, created with nowrap set to true (needed by zip entries). */
    private final Inflater inflater;

    /** True once this stream has been closed. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * The staging buffer that deflated bytes are read into from rawInputStream, and then handed to the inflater as
     * its input. This must never be used as the destination of an inflate() call: the inflater keeps a reference to
     * its input array, so inflating into this array would overwrite deflated bytes that the inflater has not
     * consumed yet.
     */
    private final byte[] buf = new byte[INFLATE_BUF_SIZE];

    /** A separate destination buffer for the single-byte read() method. */
    private final byte[] singleByteBuf = new byte[1];

    /** The size of the staging buffer. */
    private static final int INFLATE_BUF_SIZE = 8192;

    /**
     * Constructor.
     *
     * @param rawInputStream
     *            the stream of deflated bytes
     * @param inflaterRecycler
     *            the recycler to borrow an {@link Inflater} from, and to hand it back to on close
     */
    RecycledInflaterInputStream(final InputStream rawInputStream,
            final Recycler<RecyclableInflater, RuntimeException> inflaterRecycler) {
        this.rawInputStream = rawInputStream;
        this.inflaterRecycler = inflaterRecycler;
        this.recyclableInflater = inflaterRecycler.acquire();
        this.inflater = recyclableInflater.getInflater();
    }

    @Override
    public int read() throws IOException {
        if (closed.get()) {
            throw new IOException("InputStream is already closed");
        } else if (inflater.finished()) {
            return -1;
        }
        final var numInflatedBytesRead = read(singleByteBuf, 0, 1);
        if (numInflatedBytesRead < 0) {
            return -1;
        } else {
            return singleByteBuf[0] & 0xff;
        }
    }

    @Override
    public int read(final byte[] outBuf, final int off, final int len) throws IOException {
        if (closed.get()) {
            throw new IOException("InputStream is already closed");
        } else if (len < 0) {
            throw new IllegalArgumentException("len cannot be negative");
        } else if (len == 0) {
            return 0;
        }
        try {
            // Keep fetching data from rawInputStream until buffer is full or inflater has finished
            var totInflatedBytes = 0;
            while (!inflater.finished() && totInflatedBytes < len) {
                final var numInflatedBytes = inflater.inflate(outBuf, off + totInflatedBytes,
                        len - totInflatedBytes);
                if (numInflatedBytes == 0) {
                    if (inflater.needsDictionary()) {
                        // Should not happen for jarfiles
                        throw new IOException("Inflater needs preset dictionary");
                    } else if (inflater.needsInput()) {
                        // Read a chunk of data from the raw InputStream
                        final var numRawBytesRead = rawInputStream.read(buf, 0, buf.length);
                        if (numRawBytesRead == -1) {
                            // An extra dummy byte is needed at the end of the input stream when using the
                            // "nowrap" Inflater option. See: ZipFile.ZipFileInflaterInputStream.fill()
                            buf[0] = (byte) 0;
                            inflater.setInput(buf, 0, 1);
                        } else {
                            // Deflate the chunk of data
                            inflater.setInput(buf, 0, numRawBytesRead);
                        }
                    }
                } else {
                    totInflatedBytes += numInflatedBytes;
                }
            }
            if (totInflatedBytes == 0) {
                // If no bytes were inflated, return -1 as required by read() API contract
                return -1;
            }
            return totInflatedBytes;

        } catch (final DataFormatException e) {
            throw new ZipException(e.getMessage() != null ? e.getMessage() : "Invalid deflated zip entry data");
        }
    }

    @Override
    public long skip(final long numToSkip) throws IOException {
        if (closed.get()) {
            throw new IOException("InputStream is already closed");
        } else if (numToSkip < 0) {
            throw new IllegalArgumentException("numToSkip cannot be negative");
        } else if (numToSkip == 0 || inflater.finished()) {
            // (InputStream#skip returns 0 at the end of the stream, it does not return -1)
            return 0;
        }
        // (Use a separate destination buffer -- buf is the inflater's input buffer, see above)
        final var skipBuf = new byte[(int) Math.min(numToSkip, INFLATE_BUF_SIZE)];
        var totBytesSkipped = 0L;
        while (totBytesSkipped < numToSkip) {
            final var readLen = (int) Math.min(numToSkip - totBytesSkipped, skipBuf.length);
            final var numBytesRead = read(skipBuf, 0, readLen);
            if (numBytesRead > 0) {
                totBytesSkipped += numBytesRead;
            } else {
                break;
            }
        }
        return totBytesSkipped;
    }

    @Override
    public int available() throws IOException {
        if (closed.get()) {
            throw new IOException("InputStream is already closed");
        }
        // We don't know how many bytes are available, but have to return greater than zero if there is
        // still input, according to the API contract. Hopefully nothing relies on this and ends up reading
        // just one byte at a time.
        return inflater.finished() ? 0 : 1;
    }

    @Override
    public synchronized void mark(final int readlimit) {
        throw new IllegalArgumentException("Not supported");
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new IllegalArgumentException("Not supported");
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void close() {
        if (!closed.getAndSet(true)) {
            try {
                rawInputStream.close();
            } catch (final Exception e) {
                // Ignore
            }
            // Reset and recycle inflater instance
            inflaterRecycler.recycle(recyclableInflater);
        }
    }
}
