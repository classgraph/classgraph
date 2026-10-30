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
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.utils.StringUtils;

/**
 * {@link RandomAccessReader} backed by a slice of a {@link FileChannel}. Defaults to
 * {@link ByteOrder#LITTLE_ENDIAN}.
 *
 * <p>
 * When constructed with a {@link FileChannelSource}, the source is queried before each read and re-queried if a
 * concurrent thread closes the channel via interruption ({@link ClosedChannelException} other than
 * {@link ClosedByInterruptException}), allowing shared channels to be transparently reopened.
 */
public final class RandomAccessFileChannelReader implements RandomAccessReader {
    /**
     * Supplier of the {@link FileChannel} read by a {@link RandomAccessFileChannelReader}.
     */
    @FunctionalInterface
    public interface FileChannelSource {
        /**
         * Returns the open {@link FileChannel} to read from.
         *
         * @return the channel to read.
         * @throws IOException
         *             if the channel cannot be obtained or the underlying file has been closed.
         */
        FileChannel fileChannel() throws IOException;
    }

    /** Source of the file channel. */
    private final FileChannelSource fileChannelSource;

    /** The start offset of the slice within the file. */
    private final long sliceStartPos;

    /** The length of the slice in bytes. */
    private final long sliceLength;

    /** Cached {@link ByteBuffer} wrapper for the last array read, or null. */
    private @Nullable ByteBuffer reusableByteBuffer;

    /** Scratch array for reading primitive values. */
    private final byte[] scratchArr = new byte[8];

    /** Buffer wrapping {@link #scratchArr}. */
    private final ByteBuffer scratchByteBuf = ByteBuffer.wrap(scratchArr);

    /** Reusable byte buffer for decoding strings, or null. */
    private byte @Nullable [] stringBytes;

    /** The byte order for multi-byte values. */
    private final ByteOrder byteOrder;

    /** True if {@link #byteOrder} is {@link ByteOrder#BIG_ENDIAN}. */
    private final boolean bigEndian;

    /**
     * Construct a little-endian reader over a slice of a {@link FileChannel}.
     *
     * @param fileChannel
     *            the channel to read (its position is neither used nor modified).
     * @param sliceStartPos
     *            the start offset of the slice within the file.
     * @param sliceLength
     *            the length of the slice in bytes.
     * @throws IndexOutOfBoundsException
     *             if {@code sliceStartPos} or {@code sliceLength} is negative, or their sum overflows.
     */
    public RandomAccessFileChannelReader(final FileChannel fileChannel, final long sliceStartPos,
            final long sliceLength) {
        this(fileChannel, sliceStartPos, sliceLength, ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Construct a reader over a slice of a {@link FileChannel} with the specified byte order.
     *
     * @param fileChannel
     *            the channel to read (its position is neither used nor modified).
     * @param sliceStartPos
     *            the start offset of the slice within the file.
     * @param sliceLength
     *            the length of the slice in bytes.
     * @param byteOrder
     *            the byte order for multi-byte values.
     * @throws IndexOutOfBoundsException
     *             if {@code sliceStartPos} or {@code sliceLength} is negative, or their sum overflows.
     */
    public RandomAccessFileChannelReader(final FileChannel fileChannel, final long sliceStartPos,
            final long sliceLength, final ByteOrder byteOrder) {
        this(() -> fileChannel, sliceStartPos, sliceLength, byteOrder);
    }

    /**
     * Construct a little-endian reader over a slice of a channel supplied by a {@link FileChannelSource}.
     *
     * @param fileChannelSource
     *            supplier of the channel to read (the channel's position is neither used nor modified).
     * @param sliceStartPos
     *            the start offset of the slice within the file.
     * @param sliceLength
     *            the length of the slice in bytes.
     * @throws IndexOutOfBoundsException
     *             if {@code sliceStartPos} or {@code sliceLength} is negative, or their sum overflows.
     */
    public RandomAccessFileChannelReader(final FileChannelSource fileChannelSource, final long sliceStartPos,
            final long sliceLength) {
        this(fileChannelSource, sliceStartPos, sliceLength, ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Construct a reader over a slice of a channel supplied by a {@link FileChannelSource} with the specified byte
     * order.
     *
     * @param fileChannelSource
     *            supplier of the channel to read (the channel's position is neither used nor modified).
     * @param sliceStartPos
     *            the start offset of the slice within the file.
     * @param sliceLength
     *            the length of the slice in bytes.
     * @param byteOrder
     *            the byte order for multi-byte values.
     * @throws IndexOutOfBoundsException
     *             if {@code sliceStartPos} or {@code sliceLength} is negative, or their sum overflows.
     */
    public RandomAccessFileChannelReader(final FileChannelSource fileChannelSource, final long sliceStartPos,
            final long sliceLength, final ByteOrder byteOrder) {
        // Not checked against the size of the file, which can change after this, and which a read past the end of
        // reports anyway
        Objects.checkFromIndexSize(sliceStartPos, sliceLength, Long.MAX_VALUE);
        this.fileChannelSource = fileChannelSource;
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
        final var dstRoom = ReaderBounds.numBytesFree(srcOffset, dstBuf, dstBufStart, numBytes);
        if (numBytes == 0 || dstRoom == 0) {
            return 0;
        }
        try {
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
                var fileChannel = fileChannelSource.fileChannel();
                // FileChannel#read is not required to transfer the whole of the requested range in a single call,
                // and a read from a network filesystem can be short, so keep reading until the requested number of
                // bytes has been read. (A caller that streams the content, such as VfsFileChannel or an InputStream,
                // takes a short read to mean the content has ended, so a short read has to be completed here.)
                while (numBytesRead < numBytesToRead) {
                    final int numBytesReadThisCall;
                    try {
                        numBytesReadThisCall = fileChannel.read(dstBuf, srcStart + numBytesRead);
                    } catch (final ClosedByInterruptException e) {
                        // This thread was interrupted, which is a reason to stop reading
                        throw e;
                    } catch (final ClosedChannelException e) {
                        // Another thread closed the channel. Carry on from where the read stopped if the source has
                        // another channel to read, which it does not if the file has been closed, or if the channel
                        // was given to this reader rather than got from a source that can reopen it
                        final var nextFileChannel = fileChannelSource.fileChannel();
                        if (nextFileChannel == fileChannel) {
                            throw e;
                        }
                        fileChannel = nextFileChannel;
                        continue;
                    }
                    if (numBytesReadThisCall < 0) {
                        // The end of the file is within the slice, so the file has been truncated since the slice
                        // was made. That is thrown rather than returned as a short read or -1, which a caller that
                        // streams the content would take to be the end of the content
                        throw new IOException("File is truncated");
                    }
                    if (numBytesReadThisCall == 0) {
                        // A FileChannel is always in blocking mode, and the window above always leaves room while
                        // this loop runs, so ReadableByteChannel#read guarantees that at least one byte is read,
                        // and a FileChannel that keeps to its contract never returns 0 here
                        break;
                    }
                    numBytesRead += numBytesReadThisCall;
                }
            } finally {
                dstBuf.limit(dstLimit);
                dstBuf.position(dstPosition);
            }
            return numBytesRead == 0 ? -1 : numBytesRead;

        } catch (final IllegalArgumentException e) {
            // The bounds were checked above, so reaching here means a bounds check is wrong rather than that
            // the caller asked for too much -- without the cause there is no record of which index was rejected
            throw new IOException("Read index out of bounds", e);
        }
    }

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        var byteBuffer = reusableByteBuffer;
        if (byteBuffer == null || byteBuffer.array() != dstArr) {
            // Wrap dstArr, unless the buffer from the previous array read already wraps it
            reusableByteBuffer = byteBuffer = ByteBuffer.wrap(dstArr);
        }
        // The buffer's limit is the length of the array, so this checks the bounds the same way as a read into the
        // array would be checked, and leaves the buffer's position and limit as they were
        return read(srcOffset, byteBuffer, dstArrStart, numBytes);
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
        return readByte(offset) & 0xff;
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
        checkInBounds(offset, numBytes);
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
