
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
package nonapi.io.github.classgraph.fileslice.reader;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;

import io.github.classgraph.Resource;
import nonapi.io.github.classgraph.fileslice.ArraySlice;
import nonapi.io.github.classgraph.fileslice.FileSlice;
import nonapi.io.github.classgraph.fileslice.Slice;
import nonapi.io.github.classgraph.utils.StringUtils;

/**
 * A {@link Slice} reader that works as either a {@link RandomAccessReader} or a {@link SequentialReader}. The file
 * is buffered up to the furthest point that has been read or skipped to, and a read or skip past 64MB, far beyond
 * the length of any real classfile, throws {@link IOException}. Reads in <b>big endian</b> order, as required by
 * the classfile format.
 */
public class ClassfileReader implements RandomAccessReader, SequentialReader, Closeable {
    /** The underlying resource to close when {@link ClassfileReader#close()} is called. */
    private Resource resourceToClose;

    /** The {@link InputStream} for reading deflated or stream-backed content, or null. */
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

    /** Declared classfile length capped at {@link #MAX_CLASSFILE_LENGTH}, or -1 if unknown. */
    private int classfileLengthHint = -1;

    /** Initial buffer size (16 KB). */
    private static final int INITIAL_BUF_SIZE = 16384;

    /** Number of bytes read on each buffer underrun (8 KB minus 8 bytes for 64-bit alignment slack). */
    private static final int BUF_CHUNK_SIZE = 8192 - 8;

    /** Maximum classfile length buffered (64 MB). */
    private static final int MAX_CLASSFILE_LENGTH = 64 * 1024 * 1024;

    /** Maximum buffer size allocated upfront from a declared length hint (16 MB). */
    private static final int MAX_DECLARED_LENGTH_TO_ALLOCATE = 16 * 1024 * 1024;

    /** Error message thrown when reading past the end of the classfile. */
    private static final String END_OF_CLASSFILE = "Tried to read past the end of the classfile";

    /**
     * Constructor.
     * 
     * @param slice
     *            the {@link Slice} to read.
     * @param resourceToClose
     *            the resource to close when {@link ClassfileReader#close()} is called, or null.
     * @throws IOException
     *             If an inflater cannot be opened on the {@link Slice}.
     */
    public ClassfileReader(final Slice slice, final Resource resourceToClose) throws IOException {
        this.classfileLengthHint = (int) slice.sliceLength;
        this.resourceToClose = resourceToClose;
        if (slice.isDeflatedZipEntry) {
            // If this is a deflated slice, need to read from an InflaterInputStream to fill buffer
            inflaterInputStream = slice.open();
            arr = new byte[INITIAL_BUF_SIZE];
            classfileLengthHint = (int) Math.min(slice.inflatedLengthHint, MAX_CLASSFILE_LENGTH);
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
                    // offset, so copy the sliced part of the array, as far as the limit, to a new buffer
                    final int copyLength = (int) Math.min(arraySlice.sliceLength, MAX_CLASSFILE_LENGTH);
                    arr = Arrays.copyOfRange(arraySlice.arr, (int) arraySlice.sliceStartPos,
                            (int) arraySlice.sliceStartPos + copyLength);
                }
                // Only the bytes within the limit count as buffered, so that a read past it is rejected
                arrUsed = Math.min(arr.length, MAX_CLASSFILE_LENGTH);
                classfileLengthHint = arrUsed;
            } else {
                // Otherwise this is a FileSlice -- need to fetch chunks of bytes using a random access reader
                randomAccessReader = slice.randomAccessReader();
                arr = new byte[INITIAL_BUF_SIZE];
                classfileLengthHint = (int) Math.min(slice.sliceLength, MAX_CLASSFILE_LENGTH);
            }
        }
    }

    /**
     * Constructor for reader of module {@link InputStream} (which is not deflated).
     * 
     * @param inputStream
     *            the {@link InputStream} to read from.
     * @param resourceToClose
     *            the underlying resource to close when {@link ClassfileReader#close()} is called, or null.
     * @throws IOException
     *             If an inflater cannot be opened on the {@link Slice}.
     */
    public ClassfileReader(final InputStream inputStream, final Resource resourceToClose) throws IOException {
        inflaterInputStream = inputStream;
        arr = new byte[INITIAL_BUF_SIZE];
        this.resourceToClose = resourceToClose;
    }

    /**
     * Curr pos.
     *
     * @return the current read position.
     */
    public int currPos() {
        return currIdx;
    }

    /**
     * Buf.
     *
     * @return the buffer.
     */
    public byte[] buf() {
        return arr;
    }

    /**
     * Called when there is a buffer underrun to ensure there are sufficient bytes available in the array to read
     * the given number of bytes at the given start index.
     *
     * @param targetArrUsed
     *            the target value for {@link #arrUsed} (i.e. the number of bytes that must be filled in the array)
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void readTo(final int targetArrUsed) throws IOException {
        // Array does not need to grow larger than the length hint (if the uncompressed size of the zip entry
        // is an underestimate, classfile will be truncated). If -1, the buffer may grow as far as the limit.
        final int maxArrLen = classfileLengthHint == -1 ? MAX_CLASSFILE_LENGTH : classfileLengthHint;
        // Check the limit first, so that a read past it is reported as such whatever the classfile is read from.
        // (A negative target is a position past 2GB that wrapped around.)
        if (targetArrUsed > MAX_CLASSFILE_LENGTH || targetArrUsed < 0) {
            throw new IOException("Tried to read past the limit of " + MAX_CLASSFILE_LENGTH
                    + " bytes on the length of a classfile");
        }
        if (inflaterInputStream == null && randomAccessReader == null) {
            // If neither inflaterInputStream nor randomAccessReader is set, then slice is an ArraySlice,
            // and array is already "fully loaded" (the ArraySlice's backing array is used as the buffer).
            throw new IOException("Tried to read past end of fixed array buffer");
        }
        if (arrUsed == maxArrLen) {
            // The buffer already holds the whole of the classfile, as far as its length hint, which is less than
            // the limit, since a read past the limit was rejected above, so there is nothing left to read
            throw new IOException(END_OF_CLASSFILE);
        }

        // Read into the buffer, starting at position arrUsed, growing it whenever it fills up, until the target has
        // been reached or the classfile has run out
        while (arrUsed < targetArrUsed) {
            if (arrUsed == arr.length && arr.length < maxArrLen) {
                // Grow the buffer towards the target by at least a chunk, so that a caller reading one value at a
                // time does not pay one copy per value, but to no more than twice its size, unless the target lies
                // within the length hint, which is believed as far as one bounded allocation. The target says
                // nothing about how long the classfile is: it is often read out of the classfile itself, such as
                // the length of an attribute that is skipped, so growing straight to it would let one corrupt
                // length make a 20kb classfile allocate 64MB, the limit. This loop grows the buffer again for as
                // long as the classfile keeps arriving, so the buffer stays within about twice what has been read.
                final long maxStep = Math.max(Math.max((long) arrUsed + BUF_CHUNK_SIZE, 2L * arr.length),
                        Math.min(classfileLengthHint, MAX_DECLARED_LENGTH_TO_ALLOCATE));
                arr = Arrays.copyOf(arr, (int) Math.min(
                        Math.min(Math.max(targetArrUsed, (long) arrUsed + BUF_CHUNK_SIZE), maxStep), maxArrLen));
            }
            // Don't read past the length hint
            final int maxBytesToRead = Math.min(arr.length, maxArrLen) - arrUsed;
            if (maxBytesToRead <= 0) {
                // The buffer holds the whole of the classfile, as far as the length hint
                break;
            }
            if (inflaterInputStream != null) {
                // Read from the input stream. InputStream#read is not required to transfer the whole of the
                // requested range in a single call, and the channel-backed streams that a module or a directory is
                // read through really can transfer less, so keep reading until the target has been reached or the
                // stream is exhausted. (Each call may still transfer more than the target, filling the rest of the
                // buffer.)
                final int numRead = inflaterInputStream.read(arr, arrUsed, maxBytesToRead);
                if (numRead < 0) {
                    // End of stream
                    break;
                }
                if (numRead == 0) {
                    // The stream transferred nothing into a buffer that has room, which InputStream#read should
                    // never do, but some streams do, at their end or before it. Taking zero for the end would
                    // truncate the classfile, and reading again could loop forever, so ask for a single byte, which
                    // InputStream#read() can only answer with a byte or the end of the stream
                    final int nextByte = inflaterInputStream.read();
                    if (nextByte < 0) {
                        break;
                    }
                    arr[arrUsed++] = (byte) nextByte;
                    continue;
                }
                arrUsed += numRead;
            } else /* randomAccessReader != null, so this is a (non-deflated) FileSlice */ {
                final int numBytesRead = randomAccessReader.read(/* srcOffset = */ arrUsed, /* dstArr = */ arr,
                        /* dstArrStart = */ arrUsed, /* numBytes = */ maxBytesToRead);
                if (numBytesRead <= 0) {
                    break;
                }
                arrUsed += numBytesRead;
            }
        }

        // Check the buffer was able to be filled to the requested position. The read can only stop short of the
        // target if the classfile ran out, either at the end of the stream or at the length hint.
        if (arrUsed < targetArrUsed) {
            throw new IOException(END_OF_CLASSFILE);
        }
    }

    /**
     * Ensure that the given number of bytes have been read into the buffer from the beginning of the slice.
     *
     * @param numBytes
     *            the number of bytes to ensure have been buffered
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
        // Buffer only as much as the destination can take: numBytes is only the most that may be read, and the
        // content past the room in the destination could not be returned anyway
        final int numBytesToRead = Math.max(Math.min(numBytes, dstArr.length - dstArrStart), 0);
        if (numBytesToRead == 0) {
            return -1;
        }
        if (idx + numBytesToRead > arrUsed) {
            readTo(idx + numBytesToRead);
        }
        try {
            System.arraycopy(arr, idx, dstArr, dstArrStart, numBytesToRead);
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            throw new IOException("Read index out of bounds", e);
        }
    }

    @Override
    public int read(final long srcOffset, final ByteBuffer dstBuf, final int dstBufStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        final int idx = (int) srcOffset;
        // Buffer only as much as the destination can take, as in the byte array overload
        final int numBytesToRead = Math.max(Math.min(numBytes, dstBuf.capacity() - dstBufStart), 0);
        if (numBytesToRead == 0) {
            return -1;
        }
        if (idx + numBytesToRead > arrUsed) {
            readTo(idx + numBytesToRead);
        }
        try {
            final Buffer db = FileSlice.toBuffer(dstBuf);
            // Open the limit up to the capacity before positioning, since the destination buffer may still carry
            // the limit that a previous read left on it, and positioning past a stale limit throws
            // IllegalArgumentException
            db.limit(dstBuf.capacity());
            db.position(dstBufStart);
            db.limit(dstBufStart + numBytesToRead);
            dstBuf.put(arr, idx, numBytesToRead);
            return numBytesToRead;
        } catch (BufferUnderflowException | IndexOutOfBoundsException | ReadOnlyBufferException e) {
            throw new IOException("Read index out of bounds", e);
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
            // The number of bytes to skip is the length of a part of the classfile that is not of interest, read
            // from the classfile itself, so a negative value is a corrupt classfile rather than a caller error: an
            // attribute length is an unsigned 32-bit value, and one larger than 2GB reads back as a negative int
            throw new IOException("Tried to skip a negative number of bytes");
        }
        // The target position is computed in long arithmetic, because a length close to 2GB read from a corrupt
        // classfile would otherwise wrap the position negative, and the read after it would be made outside the
        // buffer rather than being rejected here
        final long targetIdx = currIdx + (long) bytesToSkip;
        if (targetIdx > arrUsed) {
            if (targetIdx > MAX_CLASSFILE_LENGTH) {
                throw new IOException("Tried to skip past the limit of " + MAX_CLASSFILE_LENGTH
                        + " bytes on the length of a classfile");
            }
            readTo((int) targetIdx);
        }
        currIdx = (int) targetIdx;
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
        // Delegate to the random access overload, as the other sequential read methods do, so that the buffer is
        // grown to cover the requested bytes first. Reading straight out of arr would silently return whatever
        // happened to be in the buffer past arrUsed.
        final String val = readString(currIdx, numBytes, replaceSlashWithDot, stripLSemicolon);
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
        // Close each of the two resources in its own try block, so that a failure to close the input stream does
        // not skip the close of the underlying resource, leaving its file handle or memory mapping open
        try {
            if (inflaterInputStream != null) {
                inflaterInputStream.close();
            }
        } catch (final Exception e) {
            // Ignore
        } finally {
            inflaterInputStream = null;
        }
        try {
            if (resourceToClose != null) {
                resourceToClose.close();
            }
        } catch (final Exception e) {
            // Ignore
        } finally {
            resourceToClose = null;
        }
    }
}
