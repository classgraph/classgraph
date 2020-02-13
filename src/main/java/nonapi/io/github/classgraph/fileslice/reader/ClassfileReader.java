
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
package nonapi.io.github.classgraph.fileslice.reader;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;

import nonapi.io.github.classgraph.fileslice.ArraySlice;
import nonapi.io.github.classgraph.fileslice.FileSlice;
import nonapi.io.github.classgraph.fileslice.Slice;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.StringUtils;

/**
 * A {@link Slice} reader that works as either a {@link RandomAccessReader} or a {@link SequentialReader}. The file
 * is buffered up to the point it has been read so far. Reads in <b>big endian</b> order, as required by the
 * classfile format.
 */
public class ClassfileReader implements RandomAccessReader, SequentialReader, Closeable {
    /** If slice is deflated, a wrapper for {@link InflateInputStream}. */
    private InputStream inflaterInputStream;

    /**
     * If slice is not deflated, a {@link RandomAccessReader} for either the {@link ArraySlice} or {@link FileSlice}
     * concrete subclass.
     */
    private RandomAccessReader randomAccessReader;

    /** Buffer. */
    private byte[] arr;

    /** The number of bytes used in arr. */
    private int arrUsed;

    /** The current read index within the slice. */
    private int currIdx;

    /**
     * The length of the classfile if known (because it is not deflated), or -1 if unknown (because it is deflated).
     */
    private int classfileLengthHint = -1;

    /**
     * Initial buffer size. For most classfiles, only the first 16-64kb needs to be read (we don't read the
     * bytecodes).
     */
    private static final int INITIAL_BUF_SIZE = 16384;

    /**
     * Read this many bytes each time there is a buffer underrun. This is smaller than 8k by 8 bytes to prevent the
     * doubling of the array size when the last chunk doesn't quite fit within the 16kb of INITIAL_BUF_SIZE, since
     * the number of bytes that can be requested is up to 8 (for longs). Otherwise we could request to read to (8kb
     * * 2 + 8), which would double the size of the buffer to 32kb, but if we only need to read between 8kb and
     * 16kb, then we unnecessarily copied the buffer content one extra time.
     */
    private static final int BUF_CHUNK_SIZE = 8192 - 8;

    /**
     * Constructor.
     * 
     * @param slice
     *            the {@link Slice} to read.
     * @throws IOException
     *             If an inflater cannot be opened on the {@link Slice}.
     */
    public ClassfileReader(final Slice slice) throws IOException {
        this.classfileLengthHint = (int) slice.sliceLength;
        if (slice.isDeflatedZipEntry) {
            // If this is a deflated slice, need to read from an InflaterInputStream to fill buffer
            inflaterInputStream = slice.open();
            arr = new byte[INITIAL_BUF_SIZE];
            classfileLengthHint = (int) Math.min(slice.inflatedLengthHint, FileUtils.MAX_BUFFER_SIZE);
        } else {
            if (slice instanceof ArraySlice) {
                // If slice is an ArraySlice, avoid copying by simply reusing the wrapped byte array
                // in place of the buffer array, and mark it as fully loaded
                final ArraySlice arraySlice = (ArraySlice) slice;
                if (arraySlice.sliceStartPos == 0 && arraySlice.sliceLength == arraySlice.arr.length) {
                    // ArraySlice is the whole array
                    arr = arraySlice.arr;
                } else {
                    // ArraySlice covers only a partial array, and this class doesn't support a starting
                    // offset, so copy the sliced part of the array to a new buffer
                    arr = Arrays.copyOfRange(arraySlice.arr, (int) arraySlice.sliceStartPos,
                            (int) (arraySlice.sliceStartPos + arraySlice.sliceLength));
                }
                arrUsed = arr.length;
                classfileLengthHint = arr.length;
            } else {
                // Otherwise this is a FileSlice -- need to fetch chunks of bytes using a random access reader
                randomAccessReader = slice.randomAccessReader();
                arr = new byte[INITIAL_BUF_SIZE];
                classfileLengthHint = (int) Math.min(slice.sliceLength, FileUtils.MAX_BUFFER_SIZE);
            }
        }
    }

    /**
     * Constructor for reader of module {@link InputStream} (which is not deflated).
     * 
     * @param inputStream
     *            the {@link InputStream} to read from.
     * @throws IOException
     *             If an inflater cannot be opened on the {@link Slice}.
     */
    public ClassfileReader(final InputStream inputStream) throws IOException {
        inflaterInputStream = inputStream;
        arr = new byte[INITIAL_BUF_SIZE];
    }

    /** @return the current read position. */
    public int currPos() {
        return currIdx;
    }

    /** @return the buffer. */
    public byte[] buf() {
        return arr;
    }

    /**
     * Called when there is a buffer underrun to ensure there are sufficient bytes available in the array to read
     * the given number of bytes at the given start index.
     */
    private void readTo(final int targetArrUsed) throws IOException {
        // Array does not need to grow larger than the length hint (if the uncompressed size of the zip entry
        // is an underestimate, classfile will be truncated). If -1, assume 2GB is the max size.
        final int maxArrLen = classfileLengthHint == -1 ? FileUtils.MAX_BUFFER_SIZE : classfileLengthHint;
        if (inflaterInputStream == null && randomAccessReader == null) {
            // If neither inflaterInputStream nor randomAccessReader is set, then slice is an ArraySlice,
            // and array is already "fully loaded" (the ArraySlice's backing array is used as the buffer).
            throw new IOException("Tried to read past end of fixed array buffer");
        }
        if (targetArrUsed > FileUtils.MAX_BUFFER_SIZE || targetArrUsed < 0 || arrUsed == maxArrLen) {
            throw new IOException("Hit 2GB limit while trying to grow buffer array");
        }

        // Need to read at least BUF_CHUNK_SIZE (but don't overshoot past 2GB limit)
        final int maxNewArrUsed = (int) Math.min(Math.max(targetArrUsed, (long) (arrUsed + BUF_CHUNK_SIZE)),
                maxArrLen);

        // Double the size of the array if it's too small to contain the new chunk of bytes
        if (arr.length < maxNewArrUsed) {
            arr = Arrays.copyOf(arr, (int) Math.min(arr.length * 2L, maxArrLen));
        }

        // Figure out the maximum number of bytes that can be read into the array (which is the minimum
        // of the number of requested bytes, and the space left in the array)
        final int maxBytesToRead = Math.min(maxNewArrUsed - arrUsed, arr.length - arrUsed);

        // Read a new chunk into the buffer, starting at position arrUsed
        if (inflaterInputStream != null) {
            // Read from inflater input stream
            final int numRead = inflaterInputStream.read(arr, arrUsed, maxBytesToRead);
            if (numRead > 0) {
                arrUsed += numRead;
            }
        } else /* randomAccessReader == null, so this is a (non-deflated) FileSlice */ {
            // Don't read past end of slice
            final int bytesToRead = Math.min(maxBytesToRead, maxArrLen - arrUsed);
            // Read bytes from FileSlice into arr
            final int numBytesRead = randomAccessReader.read(/* srcOffset = */ arrUsed, /* dstArr = */ arr,
                    /* dstArrStart = */ arrUsed, /* numBytes = */ bytesToRead);
            if (numBytesRead > 0) {
                arrUsed += numBytesRead;
            }
        }

        // Check the buffer was able to be filled to the requested position
        if (arrUsed < targetArrUsed) {
            throw new IOException("Buffer underflow");
        }
    }

    /**
     * Ensure that the given number of bytes have been read into the buffer from the beginning of the slice.
     * 
     * @throws IOException
     *             on EOF or if the bytes could not be read.
     */
    public void bufferTo(final int numBytes) throws IOException {
        if (numBytes > arrUsed) {
            readTo(numBytes);
        }
    }

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        final int idx = (int) srcOffset;
        if (idx + numBytes > arrUsed) {
            readTo(idx + numBytes);
        }
        final int numBytesToRead = Math.max(Math.min(numBytes, dstArr.length - dstArrStart), 0);
        if (numBytesToRead == 0) {
            return -1;
        }
        try {
            System.arraycopy(arr, idx, dstArr, dstArrStart, numBytesToRead);
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            throw new IOException("Read index out of bounds");
        }
    }

    @Override
    public int read(final long srcOffset, final ByteBuffer dstBuf, final int dstBufStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        final int idx = (int) srcOffset;
        if (idx + numBytes > arrUsed) {
            readTo(idx + numBytes);
        }
        final int numBytesToRead = Math.max(Math.min(numBytes, dstBuf.capacity() - dstBufStart), 0);
        if (numBytesToRead == 0) {
            return -1;
        }
        try {
            dstBuf.position(dstBufStart);
            dstBuf.limit(dstBufStart + numBytesToRead);
            dstBuf.put(arr, idx, numBytesToRead);
            return numBytesToRead;
        } catch (BufferUnderflowException | IndexOutOfBoundsException | ReadOnlyBufferException e) {
            throw new IOException("Read index out of bounds");
        }
    }

    @Override
    public byte readByte(final long offset) throws IOException {
        final int idx = (int) offset;
        if (idx + 1 > arrUsed) {
            readTo(idx + 1);
        }
        return arr[idx];
    }

    @Override
    public int readUnsignedByte(final long offset) throws IOException {
        final int idx = (int) offset;
        if (idx + 1 > arrUsed) {
            readTo(idx + 1);
        }
        return arr[idx] & 0xff;
    }

    @Override
    public short readShort(final long offset) throws IOException {
        return (short) readUnsignedShort(offset);
    }

    @Override
    public int readUnsignedShort(final long offset) throws IOException {
        final int idx = (int) offset;
        if (idx + 2 > arrUsed) {
            readTo(idx + 2);
        }
        return ((arr[idx] & 0xff) << 8) //
                | (arr[idx + 1] & 0xff);
    }

    @Override
    public int readInt(final long offset) throws IOException {
        final int idx = (int) offset;
        if (idx + 4 > arrUsed) {
            readTo(idx + 4);
        }
        return ((arr[idx] & 0xff) << 24) //
                | ((arr[idx + 1] & 0xff) << 16) //
                | ((arr[idx + 2] & 0xff) << 8) //
                | (arr[idx + 3] & 0xff);
    }

    @Override
    public long readUnsignedInt(final long offset) throws IOException {
        return readInt(offset) & 0xffffffffL;
    }

    @Override
    public long readLong(final long offset) throws IOException {
        final int idx = (int) offset;
        if (idx + 8 > arrUsed) {
            readTo(idx + 8);
        }
        return ((arr[idx] & 0xffL) << 56) //
                | ((arr[idx + 1] & 0xffL) << 48) //
                | ((arr[idx + 2] & 0xffL) << 40) //
                | ((arr[idx + 3] & 0xffL) << 32) //
                | ((arr[idx + 4] & 0xffL) << 24) //
                | ((arr[idx + 5] & 0xffL) << 16) //
                | ((arr[idx + 6] & 0xffL) << 8) //
                | (arr[idx + 7] & 0xffL);
    }

    @Override
    public byte readByte() throws IOException {
        final byte val = readByte(currIdx);
        currIdx++;
        return val;
    }

    @Override
    public int readUnsignedByte() throws IOException {
        final int val = readUnsignedByte(currIdx);
        currIdx++;
        return val;
    }

    @Override
    public short readShort() throws IOException {
        final short val = readShort(currIdx);
        currIdx += 2;
        return val;
    }

    @Override
    public int readUnsignedShort() throws IOException {
        final int val = readUnsignedShort(currIdx);
        currIdx += 2;
        return val;
    }

    @Override
    public int readInt() throws IOException {
        final int val = readInt(currIdx);
        currIdx += 4;
        return val;
    }

    @Override
    public long readUnsignedInt() throws IOException {
        final long val = readUnsignedInt(currIdx);
        currIdx += 4;
        return val;
    }

    @Override
    public long readLong() throws IOException {
        final long val = readLong(currIdx);
        currIdx += 8;
        return val;
    }

    @Override
    public void skip(final int bytesToSkip) throws IOException {
        if (bytesToSkip < 0) {
            throw new IllegalArgumentException("Tried to skip a negative number of bytes");
        }
        final int idx = currIdx;
        if (idx + bytesToSkip > arrUsed) {
            readTo(idx + bytesToSkip);
        }
        currIdx += bytesToSkip;
    }

    @Override
    public String readString(final long offset, final int numBytes, final boolean replaceSlashWithDot,
            final boolean stripLSemicolon) throws IOException {
        final int idx = (int) offset;
        if (idx + numBytes > arrUsed) {
            readTo(idx + numBytes);
        }
        return StringUtils.readString(arr, idx, numBytes, replaceSlashWithDot, stripLSemicolon);
    }

    @Override
    public String readString(final int numBytes, final boolean replaceSlashWithDot, final boolean stripLSemicolon)
            throws IOException {
        final String val = StringUtils.readString(arr, currIdx, numBytes, replaceSlashWithDot, stripLSemicolon);
        currIdx += numBytes;
        return val;
    }

    @Override
    public String readString(final long offset, final int numBytes) throws IOException {
        return readString(offset, numBytes, false, false);
    }

    @Override
    public String readString(final int numBytes) throws IOException {
        return readString(numBytes, false, false);
    }

    @Override
    public void close() {
        try {
            if (inflaterInputStream != null) {
                inflaterInputStream.close();
            }
        } catch (final Exception e) {
            // Ignore
        }
    }
}
