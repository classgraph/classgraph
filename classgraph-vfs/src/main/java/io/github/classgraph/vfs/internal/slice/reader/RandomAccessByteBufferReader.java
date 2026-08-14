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
package io.github.classgraph.vfs.internal.slice.reader;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.Charset;

import io.github.classgraph.base.internal.utils.StringUtils;

/**
 * {@link RandomAccessReader} for a {@link ByteBuffer}. Reads in <b>little endian</b> order, as required by the
 * zipfile format.
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
        // Take a read-only duplicate, so that this reader has its own position and limit, and cannot write through
        // to a buffer that may be a memory mapping shared by every thread reading the same file
        this.byteBuffer = byteBuffer.asReadOnlyBuffer();
        this.byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        this.sliceStartPos = (int) sliceStartPos;
        this.sliceLength = (int) sliceLength;
        this.byteBuffer.position(this.sliceStartPos);
        this.byteBuffer.limit(this.sliceStartPos + this.sliceLength);
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

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        checkInBounds(srcOffset, numBytes);
        try {
            final var numBytesToRead = Math.max(Math.min(numBytes, dstArr.length - dstArrStart), 0);
            if (numBytesToRead == 0) {
                return -1;
            }
            final var srcStart = (int) srcOffset;
            byteBuffer.position(sliceStartPos + srcStart);
            byteBuffer.get(dstArr, dstArrStart, numBytesToRead);
            byteBuffer.position(sliceStartPos);
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
        checkInBounds(srcOffset, numBytes);
        try {
            final var numBytesToRead = Math.max(Math.min(numBytes, dstBuf.capacity() - dstBufStart), 0);
            if (numBytesToRead == 0) {
                return -1;
            }
            final var srcStart = (int) (sliceStartPos + srcOffset);
            try {
                byteBuffer.position(srcStart);
                // Limit the source to the bytes that were asked for, otherwise the rest of the slice is copied
                // too, overflowing the destination
                byteBuffer.limit(srcStart + numBytesToRead);
                dstBuf.position(dstBufStart);
                dstBuf.limit(dstBufStart + numBytesToRead);
                dstBuf.put(byteBuffer);
            } finally {
                // Restore the window on the slice, even if the read failed, since the reader can be read again
                byteBuffer.limit(sliceStartPos + sliceLength);
                byteBuffer.position(sliceStartPos);
            }
            return numBytesToRead;
        } catch (BufferUnderflowException | IndexOutOfBoundsException | ReadOnlyBufferException e) {
            throw new IOException("Read index out of bounds");
        }
    }

    @Override
    public byte readByte(final long offset) throws IOException {
        checkInBounds(offset, 1);
        final var idx = (int) (sliceStartPos + offset);
        return byteBuffer.get(idx);
    }

    @Override
    public int readUnsignedByte(final long offset) throws IOException {
        checkInBounds(offset, 1);
        final var idx = (int) (sliceStartPos + offset);
        return byteBuffer.get(idx) & 0xff;
    }

    @Override
    public int readUnsignedShort(final long offset) throws IOException {
        // (Mask with 0xffff, not 0xff -- masking with 0xff would discard the high byte of the short)
        return readShort(offset) & 0xffff;
    }

    @Override
    public short readShort(final long offset) throws IOException {
        checkInBounds(offset, 2);
        final var idx = (int) (sliceStartPos + offset);
        return byteBuffer.getShort(idx);
    }

    @Override
    public int readInt(final long offset) throws IOException {
        checkInBounds(offset, 4);
        final var idx = (int) (sliceStartPos + offset);
        return byteBuffer.getInt(idx);
    }

    @Override
    public long readUnsignedInt(final long offset) throws IOException {
        return readInt(offset) & 0xffffffffL;
    }

    @Override
    public long readLong(final long offset) throws IOException {
        checkInBounds(offset, 8);
        final var idx = (int) (sliceStartPos + offset);
        return byteBuffer.getLong(idx);
    }

    /**
     * Copy a range of the slice into a new byte array.
     *
     * @param offset
     *            the offset to read from, relative to the start of the slice
     * @param numBytes
     *            the number of bytes to read
     * @return the bytes that were read.
     * @throws IOException
     *             if the read would run past either end of the slice, or if the slice ended early.
     */
    private byte[] readBytes(final long offset, final int numBytes) throws IOException {
        // Check the range before allocating the array, since a length read out of corrupt content can be negative
        // or larger than the slice, and read() would only reject it after the allocation had been attempted
        checkInBounds(offset, numBytes);
        final var arr = new byte[numBytes];
        if (read(offset, arr, 0, numBytes) < numBytes) {
            throw new IOException("Premature EOF while reading string");
        }
        return arr;
    }

    @Override
    public String readStringModifiedUtf8(final long offset, final int numBytes) throws IOException {
        // (Read from index 0 of the array, not from the slice offset -- readBytes() already applied the slice
        // offset when copying into the array, and the array is only numBytes long)
        return StringUtils.readStringModifiedUtf8(readBytes(offset, numBytes), 0, numBytes);
    }

    @Override
    public String readString(final long offset, final int numBytes, final Charset charset) throws IOException {
        return new String(readBytes(offset, numBytes), 0, numBytes, charset);
    }
}
