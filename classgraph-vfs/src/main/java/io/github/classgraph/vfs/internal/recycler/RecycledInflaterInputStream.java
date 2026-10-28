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
package io.github.classgraph.vfs.internal.recycler;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/**
 * An {@link InputStream} that inflates deflated zip entry data using an {@link Inflater} acquired from a
 * {@link Recycler} and returned when this stream is closed.
 */
public final class RecycledInflaterInputStream extends InputStream {
    /** The stream of deflated bytes. */
    private final InputStream rawInputStream;

    /** The recycler to which the {@link Inflater} is returned on close. */
    private final Recycler<Inflater, RuntimeException> inflaterRecycler;

    /** The acquired {@link Inflater} (configured with {@code nowrap = true} for zip entries). */
    private final Inflater inflater;

    /** True once this stream has been closed. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Lock synchronizing read operations and {@link #close()} access to {@link #inflater}. */
    private final Object lock = new Object();

    /**
     * True once the end of {@link #rawInputStream} has been reached and the trailing dummy byte required by
     * {@code nowrap = true} has been supplied to the inflater.
     */
    private boolean suppliedDummyByte;

    /**
     * Staging buffer for reading deflated input bytes from {@link #rawInputStream}. Must not be used as an inflate
     * output buffer while the inflater still references it as input.
     */
    private final byte[] buf = new byte[INFLATE_BUF_SIZE];

    /** Destination buffer for {@link #read()}. */
    private final byte[] singleByteBuf = new byte[1];

    /** The size of {@link #buf}. */
    private static final int INFLATE_BUF_SIZE = 8192;

    /**
     * Constructor.
     *
     * @param rawInputStream
     *            the stream of deflated bytes
     * @param inflaterRecycler
     *            the recycler from which to acquire and return the {@link Inflater}, created via
     *            {@link #newInflaterRecycler()}
     */
    public RecycledInflaterInputStream(final InputStream rawInputStream,
            final Recycler<Inflater, RuntimeException> inflaterRecycler) {
        this.rawInputStream = rawInputStream;
        this.inflaterRecycler = inflaterRecycler;
        this.inflater = inflaterRecycler.acquire();
    }

    /**
     * Create a {@link Recycler} of {@link Inflater} instances configured with {@code nowrap = true} for zip entry
     * data.
     *
     * @return the new {@link Inflater} recycler.
     */
    public static Recycler<Inflater, RuntimeException> newInflaterRecycler() {
        return new Recycler<>() {
            @Override
            public Inflater newInstance() {
                return new Inflater(/* nowrap = */ true);
            }

            @Override
            protected void resetInstance(final Inflater inflater) {
                inflater.reset();
            }

            @Override
            protected void closeInstance(final Inflater inflater) {
                // (Inflater is AutoCloseable only from JDK 25 onward, so end() is called directly)
                inflater.end();
            }
        };
    }

    /**
     * Check that this stream has not been closed.
     *
     * @throws IOException
     *             if this stream has been closed.
     */
    private void checkNotClosed() throws IOException {
        if (closed.get()) {
            throw new IOException("InputStream is already closed");
        }
    }

    @Override
    public int read() throws IOException {
        synchronized (lock) {
            checkNotClosed();
            if (inflater.finished()) {
                return -1;
            }
            final var numInflatedBytesRead = read(singleByteBuf, 0, 1);
            if (numInflatedBytesRead < 0) {
                return -1;
            } else {
                return singleByteBuf[0] & 0xff;
            }
        }
    }

    @Override
    public int read(final byte[] outBuf, final int off, final int len) throws IOException {
        synchronized (lock) {
            return readInflated(outBuf, off, len);
        }
    }

    /**
     * Inflate up to {@code len} bytes into {@code outBuf}. The caller must hold {@link #lock}.
     *
     * @param outBuf
     *            the destination buffer
     * @param off
     *            the offset in the destination buffer to start writing at
     * @param len
     *            the maximum number of bytes to inflate
     * @return the number of bytes inflated, or -1 at the end of the stream
     * @throws IOException
     *             if this stream has been closed, or the deflated data could not be read or is invalid
     */
    private int readInflated(final byte[] outBuf, final int off, final int len) throws IOException {
        checkNotClosed();
        // Check the destination range before anything is read, as InputStream#read(byte[], int, int) requires.
        // (This also means a negative len throws IndexOutOfBoundsException, and a null outBuf is rejected here.)
        Objects.checkFromIndexSize(off, len, outBuf.length);
        if (len == 0) {
            return 0;
        }
        try {
            // Keep fetching data from rawInputStream until buffer is full or inflater has finished
            var totInflatedBytes = 0;
            while (!inflater.finished() && totInflatedBytes < len) {
                final int numInflatedBytes;
                try {
                    numInflatedBytes = inflater.inflate(outBuf, off + totInflatedBytes, len - totInflatedBytes);
                } catch (final IllegalStateException e) {
                    // Closing the Vfs ends the Inflater, which can happen while this stream is being read. The
                    // IllegalStateException that an ended Inflater throws is translated into the IOException that
                    // reading a closed Vfs throws everywhere else
                    throw new IOException("Cannot read a file after the Vfs has been closed", e);
                }
                if (numInflatedBytes == 0) {
                    if (inflater.needsDictionary()) {
                        // Should not happen for jarfiles
                        throw new IOException("Inflater needs preset dictionary");
                    } else if (inflater.needsInput()) {
                        // Read a chunk of data from the raw InputStream
                        final var numRawBytesRead = rawInputStream.read(buf, 0, buf.length);
                        if (numRawBytesRead == -1) {
                            if (suppliedDummyByte) {
                                // The inflater wants more input, but the dummy byte has already been supplied
                                // and the raw stream is exhausted, so the deflated data was truncated. Without
                                // this check, a fresh dummy byte would be supplied on every iteration and this
                                // loop would spin forever.
                                throw new EOFException("Unexpected end of deflated zip entry data");
                            }
                            suppliedDummyByte = true;
                            // An extra dummy byte is needed at the end of the input stream when using the
                            // "nowrap" Inflater option. See: ZipFile.ZipFileInflaterInputStream.fill()
                            buf[0] = (byte) 0;
                            inflater.setInput(buf, 0, 1);
                        } else {
                            // Hand the chunk of deflated data to the inflater as its next input
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
            // ZipException has no constructor that takes a cause
            final var zipException = new ZipException(
                    e.getMessage() != null ? e.getMessage() : "Invalid deflated zip entry data");
            zipException.initCause(e);
            throw zipException;
        }
    }

    @Override
    public long skip(final long numToSkip) throws IOException {
        synchronized (lock) {
            checkNotClosed();
            if (numToSkip < 0) {
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
                final var numBytesRead = readInflated(skipBuf, 0, readLen);
                if (numBytesRead > 0) {
                    totBytesSkipped += numBytesRead;
                } else {
                    break;
                }
            }
            return totBytesSkipped;
        }
    }

    @Override
    public int available() throws IOException {
        synchronized (lock) {
            checkNotClosed();
            // How many inflated bytes can be read without blocking is not known, so return 1 until the end of the
            // entry, and 0 afterwards, as InflaterInputStream does
            return inflater.finished() ? 0 : 1;
        }
    }

    /**
     * Mark is not supported by this stream (no-op).
     */
    @Override
    public synchronized void mark(final int readlimit) {
        // No-op
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void close() {
        if (!closed.getAndSet(true)) {
            // The raw stream is closed before the lock is taken. A raw stream that throws once closed, as a
            // SliceInputStream does, then stops a read in progress on another thread at its next read of the raw
            // stream, rather than letting it go on to inflate everything it asked for while this close waits
            try {
                rawInputStream.close();
            } catch (final Exception _) {
                // Ignore
            }
            // Reset and recycle the inflater only once no read is using it. A read that starts after this sees
            // that the stream is closed, and does not touch the inflater
            synchronized (lock) {
                inflaterRecycler.recycle(inflater);
            }
        }
    }
}
