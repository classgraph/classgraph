
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
package nonapi.io.github.classgraph.fileslice;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.Resource;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;
import nonapi.io.github.classgraph.utils.FileUtils;

/**
 * A slice of a {@link File}, {@link ByteBuffer} or {@link InputStream}.
 *
 * <p>
 * A {@link Slice} may be shared between threads -- a zipfile's slice is read concurrently by all the threads
 * scanning that zipfile. It is the objects obtained <i>from</i> a slice that are single-threaded: each call to
 * {@link #randomAccessReader()} or {@link #open()} returns a new reader or stream with its own read position and
 * scratch buffers, and each of those must be used by only one thread.
 */
public abstract class Slice implements Closeable {
    /** The {@link NestedJarHandler}. */
    protected final NestedJarHandler nestedJarHandler;

    /** The parent slice. */
    protected final Slice parentSlice;

    /** The start position of the slice. */
    public final long sliceStartPos;

    /** The length of the slice, or -1L if unknown (for {@link InputStream}). */
    public long sliceLength;

    /** If true, the slice is a deflated zip entry, and needs to be inflated to access the content. */
    public final boolean isDeflatedZipEntry;

    /** If the slice is a deflated zip entry, this is the expected uncompressed length, or -1L if unknown. */
    public final long inflatedLengthHint;

    /** The cached hashCode. */
    private int hashCode;

    /**
     * Constructor for treating a range of a slice as a sub-slice.
     *
     * @param parentSlice
     *            the parent slice
     * @param offset
     *            the offset of the sub-slice within the parent slice
     * @param length
     *            the length of the sub-slice
     * @param isDeflatedZipEntry
     *            true if this is a deflated zip entry
     * @param inflatedLengthHint
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 of this is not a deflated
     *            zip entry.
     * @param nestedJarHandler
     *            the nested jar handler
     */
    protected Slice(final Slice parentSlice, final long offset, final long length, final boolean isDeflatedZipEntry,
            final long inflatedLengthHint, final NestedJarHandler nestedJarHandler) {
        this.parentSlice = parentSlice;
        final long parentSliceStartPos = parentSlice == null ? 0L : parentSlice.sliceStartPos;
        this.sliceStartPos = parentSliceStartPos + offset;
        this.sliceLength = length;
        this.isDeflatedZipEntry = isDeflatedZipEntry;
        this.inflatedLengthHint = inflatedLengthHint;
        this.nestedJarHandler = nestedJarHandler;

        if (offset < 0L || sliceStartPos < 0L) {
            throw new IllegalArgumentException("Invalid startPos");
        }
        if (length < 0L) {
            throw new IllegalArgumentException("Invalid length");
        }
        // Compare the length against the space left in the parent by subtraction, rather than comparing the end of
        // this slice against the end of the parent by addition -- a zip entry can claim a compressed size large
        // enough to overflow that addition, which would make an out-of-range slice look contained, and reading it
        // would then return bytes from beyond the end of the entry. Both operands here are non-negative, so the
        // subtraction cannot overflow.
        if (parentSlice != null && length > parentSlice.sliceLength - offset) {
            throw new IllegalArgumentException("Child slice is not completely contained within parent slice");
        }
    }

    /**
     * Constructor.
     *
     * @param length
     *            the length
     * @param isDeflatedZipEntry
     *            true if this is a deflated zip entry
     * @param inflatedLengthHint
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 of this is not a deflated
     *            zip entry.
     * @param nestedJarHandler
     *            the nested jar handler
     */
    protected Slice(final long length, final boolean isDeflatedZipEntry, final long inflatedLengthHint,
            final NestedJarHandler nestedJarHandler) {
        this(/* parentSlice = */ null, 0L, length, isDeflatedZipEntry, inflatedLengthHint, nestedJarHandler);
    }

    /**
     * Get a child {@link Slice} from this parent {@link Slice}. The child slice must be smaller than the parent
     * slice, and completely contained within it.
     * 
     * @param offset
     *            The offset to start slicing from, relative to this parent slice's start position.
     * @param length
     *            The length of the slice.
     * @param isDeflatedZipEntry
     *            true if this is a deflated zip entry
     * @param inflatedLengthHint
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 of this is not a deflated
     *            zip entry.
     * @return The child slice.
     */
    public abstract Slice slice(long offset, long length, boolean isDeflatedZipEntry,
            final long inflatedLengthHint);

    /**
     * Open this {@link Slice} as an {@link InputStream}.
     *
     * @return the input stream
     * @throws IOException
     *             if an inflater cannot be created for this {@link Slice}.
     */
    public InputStream open() throws IOException {
        return open(null);
    }

    /**
     * Open this {@link Slice} as an {@link InputStream}.
     *
     * @param resourceToClose
     *            the {@link Resource} to close when the returned {@code InputStream} is closed, or null if none.
     * @return the input stream
     * @throws IOException
     *             if an inflater cannot be created for this {@link Slice}.
     */
    public InputStream open(final Resource resourceToClose) throws IOException {
        final InputStream rawInputStream = new InputStream() {
            /**
             * The reader that the bytes are read through, or null once this stream has been closed. A reader of a
             * memory-mapped file holds a duplicate of the mapped buffer, so this reference is dropped as the
             * stream closes rather than being kept for as long as anything still refers to the stream -- below
             * JDK 22 the file is unmapped by freeing its address range, so a duplicate that outlived the
             * unmapping would be a view of memory that is no longer there.
             */
            // #939
            private volatile RandomAccessReader randomAccessReader = randomAccessReader();
            private long currOff;
            private long markOff;
            private final byte[] byteBuf = new byte[1];
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public int read() throws IOException {
                if (closed.get()) {
                    throw new IOException("Already closed");
                }
                // Return the byte value, not the number of bytes read (read() returns 1 when a byte
                // was read)
                return read(byteBuf, 0, 1) < 1 ? -1 : byteBuf[0] & 0xff;
            }

            // InputStream's default implementation of this method is very slow -- it calls read()
            // for every byte. This method reads the maximum number of bytes possible in one call.
            @Override
            public int read(final byte[] buf, final int off, final int len) throws IOException {
                // Read the field into a local, so that a close running concurrently cannot null it between the
                // check and the use
                final RandomAccessReader reader = randomAccessReader;
                if (closed.get() || reader == null) {
                    throw new IOException("Already closed");
                } else if (len == 0) {
                    return 0;
                }
                final int numBytesToRead = Math.min(len, available());
                if (numBytesToRead < 1) {
                    return -1;
                }
                final int numBytesRead = reader.read(currOff, buf, off, numBytesToRead);
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
                // (Compute the number of remaining bytes first, rather than testing currOff + n, so that a
                // huge n cannot overflow to a negative value)
                final long numBytesToSkip = Math.min(n, sliceLength - currOff);
                currOff += numBytesToSkip;
                return numBytesToSkip;
            }

            @Override
            public int available() {
                return (int) Math.min(Math.max(sliceLength - currOff, 0L), FileUtils.MAX_BUFFER_SIZE);
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
                // Closing an already-closed InputStream has no effect, as required by InputStream#close()
                // -- in particular the Resource must not be closed a second time, since it may have been
                // reopened in the meantime
                if (!closed.getAndSet(true)) {
                    // Drop the reader, and with it its duplicate of the buffer of the slice, which may be a
                    // mapping
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
        };
        return isDeflatedZipEntry ? nestedJarHandler.openInflaterInputStream(rawInputStream) : rawInputStream;
    }

    /**
     * Create a new {@link RandomAccessReader} for this {@link Slice}.
     *
     * @return the random access reader
     */
    public abstract RandomAccessReader randomAccessReader();

    /**
     * Take a view of the memory mapping of this slice, if the file is memory-mapped, so that the file is not
     * unmapped while the caller can still read a {@link ByteBuffer} that {@link #read()} returned -- see
     * {@link nonapi.io.github.classgraph.fileslice.FileSlice#close()}. A slice that is not memory-mapped has no
     * view to take, and returns a release action that does nothing.
     *
     * @return the action that releases the view, which the caller must run once it can no longer read the buffer.
     * @throws IOException
     *             if the file has already been unmapped, so that there is nothing left to read.
     */
    // #939
    public Runnable acquireMappingView() throws IOException {
        return NO_MAPPING_VIEW_TO_RELEASE;
    }

    /** The release action of a slice that has no memory mapping, so that there is nothing to release. */
    private static final Runnable NO_MAPPING_VIEW_TO_RELEASE = new Runnable() {
        @Override
        public void run() {
            // Nothing to release
        }
    };

    /**
     * Load the slice as a byte array.
     *
     * @return the byte[]
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public abstract byte[] load() throws IOException;

    /**
     * Load the slice as a string.
     *
     * @return the string
     * @throws IOException
     *             if slice cannot be read.
     */
    public String loadAsString() throws IOException {
        return new String(load(), StandardCharsets.UTF_8);
    }

    /**
     * Read the slice into a {@link ByteBuffer}.
     *
     * @return the byte buffer
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public ByteBuffer read() throws IOException {
        return ByteBuffer.wrap(load());
    }

    @Override
    public void close() throws IOException {
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            final Slice parent = parentSlice;
            hashCode = parent == null ? System.identityHashCode(this)
                    : parent.hashCode() ^ ((int) sliceStartPos * 7) ^ ((int) sliceLength * 15);
            if (hashCode == 0) {
                hashCode = 1;
            }
        }
        return hashCode;
    }

    /**
     * A child slice is equal to another child slice of the same parent slice that spans the same range of it. A
     * toplevel slice is only equal to itself, since it owns the resource it reads through -- two toplevel slices of
     * the same file are two separate handles on that file, and closing one of them does not close the other.
     */
    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof Slice) || this.parentSlice == null) {
            return false;
        } else {
            final Slice other = (Slice) o;
            return this.parentSlice == other.parentSlice && this.sliceStartPos == other.sliceStartPos
                    && this.sliceLength == other.sliceLength;
        }
    }
}
