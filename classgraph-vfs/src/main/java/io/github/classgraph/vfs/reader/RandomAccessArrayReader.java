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

import io.github.classgraph.base.internal.utils.StringUtils;

/**
 * {@link RandomAccessReader} backed by a byte array. Reads in <b>little endian</b> order by default, as required by
 * the zipfile format, which is what this reader was written for; pass a {@link ByteOrder} to read content written
 * in the other order. See {@link RandomAccessReader} for why the byte order is a property of the content and not of
 * the machine.
 */
public class RandomAccessArrayReader implements RandomAccessReader {
    /** The array. */
    private final byte[] arr;

    /** The start index of the slice within the array. */
    private final int sliceStartPos;

    /** The length of the slice within the array. */
    private final int sliceLength;

    /** The byte order multi-byte values are read in. */
    private final ByteOrder byteOrder;

    /**
     * Whether {@link #byteOrder} is {@link ByteOrder#BIG_ENDIAN}, kept as a field to keep the reads branch-free.
     */
    private final boolean bigEndian;

    /**
     * Constructor for slicing an array, reading in little endian order, as the zipfile format requires.
     *
     * @param arr
     *            the array to slice.
     * @param sliceStartPos
     *            the start index of the slice within the array.
     * @param sliceLength
     *            the length of the slice within the array.
     */
    public RandomAccessArrayReader(final byte[] arr, final int sliceStartPos, final int sliceLength) {
        this(arr, sliceStartPos, sliceLength, ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Constructor for slicing an array, reading in a given byte order.
     *
     * @param arr
     *            the array to slice.
     * @param sliceStartPos
     *            the start index of the slice within the array.
     * @param sliceLength
     *            the length of the slice within the array.
     * @param byteOrder
     *            the byte order to read multi-byte values in. Pass {@link ByteOrder#nativeOrder()} for content
     *            written in the byte order of the machine this is running on.
     */
    public RandomAccessArrayReader(final byte[] arr, final int sliceStartPos, final int sliceLength,
            final ByteOrder byteOrder) {
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
     * Check that a read stays within the slice, so that it cannot read the bytes that surround the slice in the
     * array. (A zipfile can ask for a read at any offset, since offsets are read from the zipfile itself.)
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
     * The number of bytes a bulk read starting at the given offset can transfer, which is the number asked for, cut
     * down to what is left of the slice.
     *
     * @param srcOffset
     *            the offset to read from, relative to the start of the slice.
     * @param numBytes
     *            the number of bytes asked for.
     * @return the number of bytes that can be read, which is zero at or past the end of the slice.
     * @throws IOException
     *             if the offset or the number of bytes is negative.
     */
    private int numBytesAvailable(final long srcOffset, final int numBytes) throws IOException {
        if (srcOffset < 0L || numBytes < 0) {
            throw new IOException("Read index out of bounds");
        }
        // A bulk read stops at the end of the slice and reports how far it got, rather than throwing, since a
        // caller copying the content out does not necessarily know how long it is
        return (int) Math.max(Math.min(numBytes, sliceLength - srcOffset), 0L);
    }

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        final var dstRoom = ReaderBounds.numBytesFree(dstArr.length, dstArrStart);
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
        final var dstRoom = ReaderBounds.numBytesFree(dstBuf.limit(), dstBufStart);
        if (numBytes == 0 || dstRoom == 0) {
            return 0;
        }
        if (dstBuf.isReadOnly()) {
            // Checked here rather than left to the put below, so that every RandomAccessReader reports a
            // read-only destination the same way -- the file channel reader cannot catch it, since FileChannel
            // rejects a read-only destination with an IllegalArgumentException of its own
            throw new IOException("Cannot read into a read-only buffer");
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
