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

import java.io.IOException;
import java.nio.Buffer;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;

import nonapi.io.github.classgraph.fileslice.FileSlice;
import nonapi.io.github.classgraph.utils.StringUtils;

/**
 * {@link RandomAccessReader} for a {@link ByteBuffer}. Reads in <b>little endian</b> order, as required by the
 * zipfile format.
 *
 * <p>
 * The buffer may be a memory mapping that is unmapped when the {@link io.github.classgraph.ScanResult} is closed,
 * which can happen while this reader is being read from. Reading a buffer that aliases an unmapped file throws
 * {@link IllegalStateException}, which is translated here into {@link IOException}, so that reading a mapped file
 * after the {@link io.github.classgraph.ScanResult} was closed fails the same documented way as reading from a
 * closed {@link java.nio.channels.FileChannel}.
 */
public class RandomAccessByteBufferReader implements RandomAccessReader {
    /** The byte buffer. */
    private final ByteBuffer byteBuffer;

    /** The slice start pos. */
    private final int sliceStartPos;

    /** The slice length. */
    private final int sliceLength;

    /**
     * Constructor.
     *
     * @param byteBuffer
     *            the byte buffer
     * @param sliceStartPos
     *            the slice start pos
     * @param sliceLength
     *            the slice length
     */
    public RandomAccessByteBufferReader(final ByteBuffer byteBuffer, final long sliceStartPos,
            final long sliceLength) {
        this.byteBuffer = byteBuffer.duplicate();
        this.byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        this.sliceStartPos = (int) sliceStartPos;
        this.sliceLength = (int) sliceLength;
        FileSlice.toBuffer(this.byteBuffer).position(this.sliceStartPos);
        FileSlice.toBuffer(this.byteBuffer).limit(this.sliceStartPos + this.sliceLength);
    }

    /**
     * Check that a read stays within the slice, so that it cannot read the bytes that surround the slice in the
     * buffer. (A zipfile can ask for a read at any offset, since offsets are read from the zipfile itself.)
     *
     * @param offset
     *            the offset to read from, relative to the start of the slice
     * @param numBytes
     *            the number of bytes to read
     * @throws IOException
     *             if the read would run past either end of the slice
     */
    private void checkInBounds(final long offset, final int numBytes) throws IOException {
        // Compare by subtraction rather than addition, so that a large offset plus a large numBytes cannot
        // overflow and slip past the check
        if (offset < 0L || numBytes < 0 || numBytes > sliceLength - offset) {
            throw new IOException("Read index out of bounds");
        }
    }

    /**
     * Wrap the {@link IllegalStateException} thrown by reading a buffer that aliases a memory mapping whose arena
     * has been closed.
     *
     * @param e
     *            the exception thrown by the buffer access
     * @return the {@link IOException} to throw in its place
     */
    private static IOException unmapped(final IllegalStateException e) {
        return new IOException("Cannot read a file that has been unmapped by closing the ScanResult", e);
    }

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        checkInBounds(srcOffset, numBytes);
        try {
            final int numBytesToRead = Math.max(Math.min(numBytes, dstArr.length - dstArrStart), 0);
            if (numBytesToRead == 0) {
                return -1;
            }
            final int srcStart = (int) srcOffset;
            final Buffer bb = FileSlice.toBuffer(byteBuffer);
            bb.position(sliceStartPos + srcStart);
            byteBuffer.get(dstArr, dstArrStart, numBytesToRead);
            bb.position(sliceStartPos);
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            throw new IOException("Read index out of bounds");
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        }
    }

    @Override
    public int read(final long srcOffset, final ByteBuffer dstBuf, final int dstBufStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        checkInBounds(srcOffset, numBytes);
        try {
            final int numBytesToRead = Math.max(Math.min(numBytes, dstBuf.capacity() - dstBufStart), 0);
            if (numBytesToRead == 0) {
                return -1;
            }
            final int srcStart = (int) (sliceStartPos + srcOffset);
            final Buffer bb = FileSlice.toBuffer(byteBuffer);
            final Buffer db = FileSlice.toBuffer(dstBuf);
            try {
                bb.position(srcStart);
                // Limit the source to the bytes that were asked for, otherwise the rest of the slice is copied
                // too, overflowing the destination
                bb.limit(srcStart + numBytesToRead);
                db.position(dstBufStart);
                db.limit(dstBufStart + numBytesToRead);
                dstBuf.put(byteBuffer);
            } finally {
                // Restore the window on the slice, even if the read failed, since the reader can be read again
                bb.limit(sliceStartPos + sliceLength);
                bb.position(sliceStartPos);
            }
            return numBytesToRead;
        } catch (BufferUnderflowException | IndexOutOfBoundsException | ReadOnlyBufferException e) {
            throw new IOException("Read index out of bounds");
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        }
    }

    @Override
    public byte readByte(final long offset) throws IOException {
        checkInBounds(offset, 1);
        final int idx = (int) (sliceStartPos + offset);
        try {
            return byteBuffer.get(idx);
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        }
    }

    @Override
    public int readUnsignedByte(final long offset) throws IOException {
        return readByte(offset) & 0xff;
    }

    @Override
    public int readUnsignedShort(final long offset) throws IOException {
        // (Mask with 0xffff, not 0xff -- masking with 0xff would discard the high byte of the short)
        return readShort(offset) & 0xffff;
    }

    @Override
    public short readShort(final long offset) throws IOException {
        checkInBounds(offset, 2);
        final int idx = (int) (sliceStartPos + offset);
        try {
            return byteBuffer.getShort(idx);
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        }
    }

    @Override
    public int readInt(final long offset) throws IOException {
        checkInBounds(offset, 4);
        final int idx = (int) (sliceStartPos + offset);
        try {
            return byteBuffer.getInt(idx);
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        }
    }

    @Override
    public long readUnsignedInt(final long offset) throws IOException {
        return readInt(offset) & 0xffffffffL;
    }

    @Override
    public long readLong(final long offset) throws IOException {
        checkInBounds(offset, 8);
        final int idx = (int) (sliceStartPos + offset);
        try {
            return byteBuffer.getLong(idx);
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        }
    }

    @Override
    public String readString(final long offset, final int numBytes, final boolean replaceSlashWithDot,
            final boolean stripLSemicolon) throws IOException {
        final byte[] arr = new byte[numBytes];
        if (read(offset, arr, 0, numBytes) < numBytes) {
            throw new IOException("Premature EOF while reading string");
        }
        // (Read from index 0 of arr, not from the slice offset -- read() already applied the slice offset
        // when copying into arr, and arr is only numBytes long)
        return StringUtils.readString(arr, 0, numBytes, replaceSlashWithDot, stripLSemicolon);
    }

    @Override
    public String readString(final long offset, final int numBytes) throws IOException {
        return readString(offset, numBytes, false, false);
    }
}
