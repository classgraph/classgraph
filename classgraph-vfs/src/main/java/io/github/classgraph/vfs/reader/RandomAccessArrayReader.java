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
package io.github.classgraph.vfs.reader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Objects;

import io.github.classgraph.base.internal.utils.StringUtils;

/**
 * {@link RandomAccessReader} backed by a slice of a byte array. Defaults to {@link ByteOrder#LITTLE_ENDIAN}.
 */
public final class RandomAccessArrayReader implements RandomAccessReader {
    /** The backing byte array. */
    private final byte[] arr;

    /** The start index of the slice within the array. */
    private final int sliceStartPos;

    /** The length of the slice within the array. */
    private final int sliceLength;

    /** The byte order for multi-byte values. */
    private final ByteOrder byteOrder;

    /** True if {@link #byteOrder} is {@link ByteOrder#BIG_ENDIAN}. */
    private final boolean bigEndian;

    /**
     * Construct a little-endian reader over a slice of a byte array.
     *
     * @param arr
     *            the array to slice.
     * @param sliceStartPos
     *            the start index of the slice within the array.
     * @param sliceLength
     *            the length of the slice within the array.
     * @throws IndexOutOfBoundsException
     *             if the slice is not within the array.
     */
    public RandomAccessArrayReader(final byte[] arr, final int sliceStartPos, final int sliceLength) {
        this(arr, sliceStartPos, sliceLength, ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Construct a reader over a slice of a byte array with the specified byte order.
     *
     * @param arr
     *            the array to slice.
     * @param sliceStartPos
     *            the start index of the slice within the array.
     * @param sliceLength
     *            the length of the slice within the array.
     * @param byteOrder
     *            the byte order for multi-byte values.
     * @throws IndexOutOfBoundsException
     *             if the slice is not within the array.
     */
    public RandomAccessArrayReader(final byte[] arr, final int sliceStartPos, final int sliceLength,
            final ByteOrder byteOrder) {
        Objects.checkFromIndexSize(sliceStartPos, sliceLength, arr.length);
        this.arr = arr;
        this.sliceStartPos = sliceStartPos;
        this.sliceLength = sliceLength;
        this.byteOrder = byteOrder;
        this.bigEndian = byteOrder == ByteOrder.BIG_ENDIAN;
    }

    @Override
    public ByteOrder byteOrder() {
        return byteOrder;
    }

    @Override
    public long length() {
        return sliceLength;
    }

    /**
     * Verify that a read of {@code numBytes} at {@code offset} lies within the slice.
     *
     * @param offset
     *            the offset relative to the start of the slice.
     * @param numBytes
     *            the number of bytes to read.
     * @throws IOException
     *             if the range is out of bounds.
     */
    private void checkInBounds(final long offset, final int numBytes) throws IOException {
        // Compare by subtraction rather than addition, so that a large offset plus a large numBytes cannot
        // overflow and slip past the check
        if (offset < 0L || numBytes < 0 || numBytes > sliceLength - offset) {
            throw new IOException("Read index out of bounds");
        }
    }

    /**
     * Returns the number of bytes available for a bulk read of up to {@code numBytes} starting at
     * {@code srcOffset}.
     *
     * @param srcOffset
     *            the non-negative offset relative to the start of the slice.
     * @param numBytes
     *            the non-negative number of bytes requested.
     * @return the number of bytes available (0 if at or past the end of the slice).
     */
    private int numBytesAvailable(final long srcOffset, final int numBytes) {
        // A bulk read stops at the end of the slice and reports how far it got, rather than throwing, since a
        // caller copying the content out does not necessarily know how long it is
        return (int) Math.max(Math.min(numBytes, sliceLength - srcOffset), 0L);
    }

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        final var dstRoom = ReaderBounds.numBytesFree(srcOffset, dstArr.length, dstArrStart, numBytes);
        if (numBytes == 0 || dstRoom == 0) {
            return 0;
        }
        final var numBytesInSlice = numBytesAvailable(srcOffset, numBytes);
        if (numBytesInSlice == 0) {
            return -1;
        }
        try {
            final var numBytesToRead = Math.min(numBytesInSlice, dstRoom);
            final var srcStart = (int) (sliceStartPos + srcOffset);
            System.arraycopy(arr, srcStart, dstArr, dstArrStart, numBytesToRead);
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            // The bounds were checked above, so reaching here means a bounds check is wrong rather than that
            // the caller asked for too much -- without the cause there is no record of which index was rejected
            throw new IOException("Read index out of bounds", e);
        }
    }

    @Override
    public int read(final long srcOffset, final ByteBuffer dstBuf, final int dstBufStart, final int numBytes)
            throws IOException {
        final var dstRoom = ReaderBounds.numBytesFree(srcOffset, dstBuf, dstBufStart, numBytes);
        if (numBytes == 0 || dstRoom == 0) {
            return 0;
        }
        final var numBytesInSlice = numBytesAvailable(srcOffset, numBytes);
        if (numBytesInSlice == 0) {
            return -1;
        }
        try {
            final var numBytesToRead = Math.min(numBytesInSlice, dstRoom);
            final var srcStart = (int) (sliceStartPos + srcOffset);
            // An absolute put, so the destination's position and limit are neither read nor changed
            dstBuf.put(dstBufStart, arr, srcStart, numBytesToRead);
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            // The bounds were checked above, so reaching here means a bounds check is wrong rather than that
            // the caller asked for too much -- without the cause there is no record of which index was rejected
            throw new IOException("Read index out of bounds", e);
        }
    }

    @Override
    public byte readByte(final long offset) throws IOException {
        checkInBounds(offset, 1);
        final var idx = sliceStartPos + (int) offset;
        return arr[idx];
    }

    @Override
    public int readUnsignedByte(final long offset) throws IOException {
        checkInBounds(offset, 1);
        final var idx = sliceStartPos + (int) offset;
        return arr[idx] & 0xff;
    }

    @Override
    public short readShort(final long offset) throws IOException {
        return (short) readUnsignedShort(offset);
    }

    @Override
    public int readUnsignedShort(final long offset) throws IOException {
        checkInBounds(offset, 2);
        final var idx = sliceStartPos + (int) offset;
        final var bigEndianVal = (short) (((arr[idx] & 0xff) << 8) //
                | (arr[idx + 1] & 0xff));
        return (bigEndian ? bigEndianVal : Short.reverseBytes(bigEndianVal)) & 0xffff;
    }

    @Override
    public int readInt(final long offset) throws IOException {
        checkInBounds(offset, 4);
        final var idx = sliceStartPos + (int) offset;
        final var bigEndianVal = ((arr[idx] & 0xff) << 24) //
                | ((arr[idx + 1] & 0xff) << 16) //
                | ((arr[idx + 2] & 0xff) << 8) //
                | (arr[idx + 3] & 0xff);
        return bigEndian ? bigEndianVal : Integer.reverseBytes(bigEndianVal);
    }

    @Override
    public long readUnsignedInt(final long offset) throws IOException {
        return readInt(offset) & 0xffffffffL;
    }

    @Override
    public long readLong(final long offset) throws IOException {
        checkInBounds(offset, 8);
        final var idx = sliceStartPos + (int) offset;
        final var bigEndianVal = ((arr[idx] & 0xffL) << 56) //
                | ((arr[idx + 1] & 0xffL) << 48) //
                | ((arr[idx + 2] & 0xffL) << 40) //
                | ((arr[idx + 3] & 0xffL) << 32) //
                | ((arr[idx + 4] & 0xffL) << 24) //
                | ((arr[idx + 5] & 0xffL) << 16) //
                | ((arr[idx + 6] & 0xffL) << 8) //
                | (arr[idx + 7] & 0xffL);
        return bigEndian ? bigEndianVal : Long.reverseBytes(bigEndianVal);
    }

    @Override
    public String readStringModifiedUtf8(final long offset, final int numBytes) throws IOException {
        // (StringUtils range-checks against the whole array, so the slice has to be checked here)
        checkInBounds(offset, numBytes);
        final var idx = sliceStartPos + (int) offset;
        return StringUtils.readStringModifiedUtf8(arr, idx, numBytes);
    }

    @Override
    public String readString(final long offset, final int numBytes, final Charset charset) throws IOException {
        // (String's constructor range-checks against the whole array, so the slice has to be checked here)
        checkInBounds(offset, numBytes);
        final var idx = sliceStartPos + (int) offset;
        return new String(arr, idx, numBytes, charset);
    }
}
