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

import java.io.File;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;

import io.github.classgraph.base.internal.utils.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * {@link RandomAccessReader} for a {@link File}. Reads in <b>little endian</b> order by default, as required by the
 * zipfile format, which is what this reader was written for; pass a {@link ByteOrder} to read content written in
 * the other order. See {@link RandomAccessReader} for why the byte order is a property of the content and not of
 * the machine.
 */
public class RandomAccessFileChannelReader implements RandomAccessReader {

    /** The file channel. */
    private final FileChannel fileChannel;

    /** The slice start pos. */
    private final long sliceStartPos;

    /** The slice length. */
    private final long sliceLength;

    /** The reusable byte buffer, or null until the first array read. */
    private @Nullable ByteBuffer reusableByteBuffer;

    /** The scratch arr. */
    private final byte[] scratchArr = new byte[8];

    /** The scratch byte buf. */
    private final ByteBuffer scratchByteBuf = ByteBuffer.wrap(scratchArr);

    /** The reusable buffer that strings are read into, or null until the first string read. */
    private byte @Nullable [] stringBytes;

    /** The byte order multi-byte values are read in. */
    private final ByteOrder byteOrder;

    /**
     * Whether {@link #byteOrder} is {@link ByteOrder#BIG_ENDIAN}, kept as a field to keep the reads branch-free.
     */
    private final boolean bigEndian;

    /**
     * Constructor.
     *
     * @param fileChannel
     *            the file channel
     * @param sliceStartPos
     *            the slice start pos
     * @param sliceLength
     *            the slice length
     */
    public RandomAccessFileChannelReader(final FileChannel fileChannel, final long sliceStartPos,
            final long sliceLength) {
        this(fileChannel, sliceStartPos, sliceLength, ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Constructor.
     *
     * @param fileChannel
     *            the file channel
     * @param sliceStartPos
     *            the slice start pos
     * @param sliceLength
     *            the slice length
     * @param byteOrder
     *            the byte order to read multi-byte values in. Pass {@link ByteOrder#nativeOrder()} for content
     *            written in the byte order of the machine this is running on.
     */
    public RandomAccessFileChannelReader(final FileChannel fileChannel, final long sliceStartPos,
            final long sliceLength, final ByteOrder byteOrder) {
        this.fileChannel = fileChannel;
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
        try {
            if (srcOffset < 0L || numBytes < 0) {
                throw new IOException("Read index out of bounds");
            }
            // A bulk read stops at the end of the slice and reports how far it got, rather than throwing, since a
            // caller copying the content out does not necessarily know how long it is
            final var numBytesInSlice = (int) Math.max(Math.min(numBytes, sliceLength - srcOffset), 0L);
            if (numBytesInSlice == 0) {
                return -1;
            }
            // Read no more than the destination has room for, as the array-backed and ByteBuffer-backed readers
            // also do, rather than letting ByteBuffer#limit throw IllegalArgumentException
            final var numBytesToRead = Math.min(numBytesInSlice, dstRoom);
            final var srcStart = sliceStartPos + srcOffset;
            // FileChannel#read transfers relative to the destination's position, so unlike the other readers this
            // one has to window the destination, and put its position and limit back afterwards -- including when
            // the read throws, since a caller that catches the exception is left holding the buffer
            final var dstPosition = dstBuf.position();
            final var dstLimit = dstBuf.limit();
            var numBytesRead = 0;
            try {
                dstBuf.position(dstBufStart);
                dstBuf.limit(dstBufStart + numBytesToRead);
                // FileChannel#read is not required to transfer the whole of the requested range in a single call,
                // and a read from a network filesystem can be short, so keep reading until the requested number of
                // bytes has been read or the end of the file is reached. (Every caller treats a short read as a
                // truncated file, so a short read that is not at the end of the file has to be completed here.)
                while (numBytesRead < numBytesToRead) {
                    final var numBytesReadThisCall = fileChannel.read(dstBuf, srcStart + numBytesRead);
                    if (numBytesReadThisCall <= 0) {
                        // -1 => end of file; 0 => the destination buffer has no space left
                        break;
                    }
                    numBytesRead += numBytesReadThisCall;
                }
            } finally {
                dstBuf.limit(dstLimit);
                dstBuf.position(dstPosition);
            }
            return numBytesRead == 0 ? -1 : numBytesRead;

        } catch (final BufferUnderflowException | IndexOutOfBoundsException e) {
            // The bounds were checked above, so reaching here means a bounds check is wrong rather than that
            // the caller asked for too much -- without the cause there is no record of which index was rejected
            throw new IOException("Read index out of bounds", e);
        }
    }

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        try {
            if (srcOffset < 0L || numBytes < 0) {
                throw new IOException("Read index out of bounds");
            }
            var byteBuffer = reusableByteBuffer;
            if (byteBuffer == null || byteBuffer.array() != dstArr) {
                // If reusableByteBuffer is not set, or wraps a different array from a previous operation, wrap
                // dstArr with a new ByteBuffer
                reusableByteBuffer = byteBuffer = ByteBuffer.wrap(dstArr);
            }
            // Read into reusableByteBuffer, which is backed with dstArr
            return read(srcOffset, byteBuffer, dstArrStart, numBytes);

        } catch (final BufferUnderflowException | IndexOutOfBoundsException e) {
            // The bounds were checked above, so reaching here means a bounds check is wrong rather than that
            // the caller asked for too much -- without the cause there is no record of which index was rejected
            throw new IOException("Read index out of bounds", e);
        }
    }

    /**
     * Check that a read of a value stays within the slice, so that it cannot read the bytes that surround the slice
     * in the file. (A zipfile can ask for a read at any offset, since offsets are read from the zipfile itself.)
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
    public byte readByte(final long offset) throws IOException {
        checkInBounds(offset, 1);
        if (read(offset, scratchByteBuf, 0, 1) < 1) {
            throw new IOException("Premature EOF");
        }
        return scratchArr[0];
    }

    @Override
    public int readUnsignedByte(final long offset) throws IOException {
        checkInBounds(offset, 1);
        if (read(offset, scratchByteBuf, 0, 1) < 1) {
            throw new IOException("Premature EOF");
        }
        return scratchArr[0] & 0xff;
    }

    @Override
    public short readShort(final long offset) throws IOException {
        return (short) readUnsignedShort(offset);
    }

    @Override
    public int readUnsignedShort(final long offset) throws IOException {
        checkInBounds(offset, 2);
        if (read(offset, scratchByteBuf, 0, 2) < 2) {
            throw new IOException("Premature EOF");
        }
        final var bigEndianVal = (short) (((scratchArr[0] & 0xff) << 8) //
                | (scratchArr[1] & 0xff));
        return (bigEndian ? bigEndianVal : Short.reverseBytes(bigEndianVal)) & 0xffff;
    }

    @Override
    public int readInt(final long offset) throws IOException {
        checkInBounds(offset, 4);
        if (read(offset, scratchByteBuf, 0, 4) < 4) {
            throw new IOException("Premature EOF");
        }
        final var bigEndianVal = ((scratchArr[0] & 0xff) << 24) //
                | ((scratchArr[1] & 0xff) << 16) //
                | ((scratchArr[2] & 0xff) << 8) //
                | (scratchArr[3] & 0xff);
        return bigEndian ? bigEndianVal : Integer.reverseBytes(bigEndianVal);
    }

    @Override
    public long readUnsignedInt(final long offset) throws IOException {
        return readInt(offset) & 0xffffffffL;
    }

    @Override
    public long readLong(final long offset) throws IOException {
        checkInBounds(offset, 8);
        if (read(offset, scratchByteBuf, 0, 8) < 8) {
            throw new IOException("Premature EOF");
        }
        final var bigEndianVal = ((scratchArr[0] & 0xffL) << 56) //
                | ((scratchArr[1] & 0xffL) << 48) //
                | ((scratchArr[2] & 0xffL) << 40) //
                | ((scratchArr[3] & 0xffL) << 32) //
                | ((scratchArr[4] & 0xffL) << 24) //
                | ((scratchArr[5] & 0xffL) << 16) //
                | ((scratchArr[6] & 0xffL) << 8) //
                | (scratchArr[7] & 0xffL);
        return bigEndian ? bigEndianVal : Long.reverseBytes(bigEndianVal);
    }

    /**
     * Copy a range of the slice into the reusable string buffer array.
     *
     * @param offset
     *            the offset to read from, relative to the start of the slice
     * @param numBytes
     *            the number of bytes to read
     * @return the buffer array, which holds the bytes that were read in its first {@code numBytes} positions, and
     *         which may be longer than that.
     * @throws IOException
     *             if the read would run past either end of the slice, or if the file ended early.
     */
    private byte[] readIntoStringBytes(final long offset, final int numBytes) throws IOException {
        // Check the range before growing the buffer, since a length read out of corrupt content can be negative or
        // larger than the slice, and read() would only reject it after the allocation had been attempted
        if (offset < 0L || numBytes < 0 || numBytes > sliceLength - offset) {
            throw new IOException("Read index out of bounds");
        }
        // Reuse the string buffer array if it's non-null from a previous call, and if it's big enough
        var stringBytesBuf = stringBytes;
        if (stringBytesBuf == null || stringBytesBuf.length < numBytes) {
            stringBytes = stringBytesBuf = new byte[numBytes];
        }
        if (read(offset, stringBytesBuf, 0, numBytes) < numBytes) {
            throw new IOException("Premature EOF");
        }
        return stringBytesBuf;
    }

    @Override
    public String readStringModifiedUtf8(final long offset, final int numBytes) throws IOException {
        return StringUtils.readStringModifiedUtf8(readIntoStringBytes(offset, numBytes), 0, numBytes);
    }

    @Override
    public String readString(final long offset, final int numBytes, final Charset charset) throws IOException {
        return new String(readIntoStringBytes(offset, numBytes), 0, numBytes, charset);
    }
}
