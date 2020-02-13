
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Inflater;

import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.reader.ClassfileReader;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;
import nonapi.io.github.classgraph.utils.FileUtils;

/**
 * A slice of a {@link File}, {@link ByteBuffer} or {@link InputStream}. A single {@link Slice} instance should only
 * be used by a single thread.
 */
public abstract class Slice {
    /** The {@link NestedJarHandler}. */
    protected final NestedJarHandler nestedJarHandler;

    /** The parent slice. */
    protected final Slice parentSlice;

    /** The start position of the slice. */
    public final long sliceStartPos;

    /** The length of the slice, or -1L if unknown (for {@link InputStream}). */
    public final long sliceLength;

    /** If true, the slice is a deflated zip entry, and needs to be inflated to access the content. */
    public final boolean isDeflatedZipEntry;

    /** If the slice is a deflated zip entry, this is the expected uncompressed length, or -1L if unknown. */
    public final long inflatedLengthHint;

    /** The cached hashCode. */
    private int hashCode;

    /** Constructor. */
    protected Slice(final Slice parentSlice, final long offset, final long length, final boolean isDeflatedZipEntry,
            final long inflatedLengthHint, final NestedJarHandler nestedJarHandler) {
        this.parentSlice = parentSlice;
        final long parentSliceStartPos = parentSlice == null ? 0L : parentSlice.sliceStartPos;
        this.sliceStartPos = parentSliceStartPos + offset;
        this.sliceLength = length;
        this.isDeflatedZipEntry = isDeflatedZipEntry;
        this.inflatedLengthHint = inflatedLengthHint;
        this.nestedJarHandler = nestedJarHandler;

        if (sliceStartPos < 0L) {
            throw new IllegalArgumentException("Invalid startPos");
        }
        if (length < 0L) {
            throw new IllegalArgumentException("Invalid length");
        }
        if (parentSlice != null && (sliceStartPos < parentSliceStartPos
                || sliceStartPos + length > parentSliceStartPos + parentSlice.sliceLength)) {
            throw new IllegalArgumentException("Child slice is not completely contained within parent slice");
        }
    }

    /** Constructor. */
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
     *            True if the slice is a deflated zip entry.
     * @param inflatedLengthHint
     *            If this is a deflated zip entry, the expected length of the inflated content, or -1L if unknown.
     *            If this is not a deflated zip entry, 0L.
     * @return The child slice.
     */
    public abstract Slice slice(long offset, long length, boolean isDeflatedZipEntry,
            final long inflatedLengthHint);

    /**
     * Open this {@link Slice} as an {@link InputStream}.
     * 
     * @throws IOException
     *             if an inflater cannot be created for this {@link Slice}.
     */
    public InputStream open() throws IOException {
        final InputStream rawInputStream = new InputStream() {
            RandomAccessReader randomAccessReader = randomAccessReader();
            private long currOff;
            private long markOff;
            private final byte[] byteBuf = new byte[1];
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public int read() throws IOException {
                if (closed.get()) {
                    throw new IOException("Already closed");
                }
                return read(byteBuf, 0, 1);
            }

            // InputStream's default implementation of this method is very slow -- it calls read()
            // for every byte. This method reads the maximum number of bytes possible in one call.
            @Override
            public int read(final byte buf[], final int off, final int len) throws IOException {
                if (closed.get()) {
                    throw new IOException("Already closed");
                } else if (len == 0) {
                    return 0;
                }
                final int numBytesToRead = Math.min(len, available());
                if (numBytesToRead < 1) {
                    return -1;
                }
                final int numBytesRead = randomAccessReader.read(currOff, buf, off, numBytesToRead);
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
                final long newOff = Math.min(currOff + n, sliceLength);
                final long skipped = newOff - currOff;
                currOff = newOff;
                return skipped;
            }

            @Override
            public int available() {
                return (int) Math.min(Math.max(sliceLength - currOff, 0L), FileUtils.MAX_BUFFER_SIZE);
            }

            @Override
            public void mark(final int readlimit) {
                // Ignore readlimit
                markOff = currOff;
            }

            @Override
            public void reset() {
                currOff = markOff;
            }

            @Override
            public boolean markSupported() {
                return true;
            }

            @Override
            public void close() {
                closed.getAndSet(true);
                // Nothing to close
            }
        };
        return isDeflatedZipEntry ? nestedJarHandler.openInflaterInputStream(rawInputStream) : rawInputStream;
    }

    /** Create a new {@link RandomAccessReader} for this {@link Slice}. */
    public abstract RandomAccessReader randomAccessReader();

    /**
     * Open this {@link Slice} for buffered sequential reading. Make sure you close this when you have finished with
     * it, in order to recycle any {@link Inflater} instances.
     */
    public ClassfileReader openClassfileReader() throws IOException {
        if (sliceLength > FileUtils.MAX_BUFFER_SIZE) {
            throw new IllegalArgumentException(
                    "Cannot open slices larger than 2GB for sequential buffered reading");
        }
        return new ClassfileReader(this);
    }

    /** Load this {@link Slice} into a byte array. */
    public abstract byte[] load() throws IOException;

    /**
     * Read this {@link Slice} as a {@link String}.
     * 
     * @throws IOException
     *             if slice cannot be read.
     */
    public String loadAsString() throws IOException {
        return new String(load(), StandardCharsets.UTF_8);
    }

    /** Read this {@link Slice} into a {@link ByteBuffer}. */
    public ByteBuffer read() throws IOException {
        return ByteBuffer.wrap(load());
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = (parentSlice == null ? 1 : parentSlice.hashCode) ^ ((int) sliceStartPos * 7)
                    ^ ((int) sliceLength * 15);
            if (hashCode == 0) {
                hashCode = 1;
            }
        }
        return hashCode;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof Slice)) {
            return false;
        } else {
            final Slice other = (Slice) o;
            return this.parentSlice == other.parentSlice && this.sliceStartPos == other.sliceStartPos
                    && this.sliceLength == other.sliceLength;
        }
    }
}
