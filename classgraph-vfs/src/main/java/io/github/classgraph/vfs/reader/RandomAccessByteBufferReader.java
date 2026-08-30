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
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.utils.StringUtils;

/**
 * {@link RandomAccessReader} for a {@link ByteBuffer}. Reads in <b>little endian</b> order by default, as required
 * by the zipfile format, which is what this reader was written for; pass a {@link ByteOrder} to read content
 * written in the other order. The byte order of the buffer that is passed in is not consulted and is not changed --
 * this reader duplicates the buffer and sets the order on the duplicate -- since the order the bytes were written
 * in is a property of the content rather than of the buffer holding it. See {@link RandomAccessReader} for why that
 * is not the byte order of the machine either.
 *
 * <p>
 * The buffer may be a memory mapping of a file that the {@code Vfs} releases when it is closed, which can happen
 * while this reader is being read from. Reading a released file fails with an {@link IOException}, the same
 * documented way as reading from a closed {@link java.nio.channels.FileChannel}, whichever way the JDK unmaps the
 * file: on JDK 22 and later the arena that mapped it is closed, and reading the buffer afterwards throws
 * {@link IllegalStateException}, which is translated here; below JDK 22 the address range is simply freed, and
 * reading the buffer afterwards would read memory that is no longer mapped, so this reader is given a check to ask
 * whether the file is still there before it reads.
 */
public class RandomAccessByteBufferReader implements RandomAccessReader {
    /** The byte buffer. */
    private final ByteBuffer byteBuffer;

    /** The slice start pos. */
    private final int sliceStartPos;

    /** The slice length. */
    private final int sliceLength;

    /**
     * Whether the file this reader's buffer is a view of has been released, or null if the buffer is not a view of
     * anything that can be released while this reader is alive.
     */
    private final @Nullable BooleanSupplier isReleased;

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
        this(byteBuffer, sliceStartPos, sliceLength, ByteOrder.LITTLE_ENDIAN, /* isReleased = */ null);
    }

    /**
     * Constructor.
     *
     * @param byteBuffer
     *            the byte buffer
     * @param sliceStartPos
     *            the slice start pos
     * @param sliceLength
     *            the slice length
     * @param isReleased
     *            whether the file the buffer is a view of has been released, or null if it cannot be released while
     *            this reader is alive
     */
    public RandomAccessByteBufferReader(final ByteBuffer byteBuffer, final long sliceStartPos,
            final long sliceLength, final @Nullable BooleanSupplier isReleased) {
        this(byteBuffer, sliceStartPos, sliceLength, ByteOrder.LITTLE_ENDIAN, isReleased);
    }

    /**
     * Constructor for slicing a byte buffer, reading in a given byte order.
     *
     * @param byteBuffer
     *            the byte buffer
     * @param sliceStartPos
     *            the slice start pos
     * @param sliceLength
     *            the slice length
     * @param byteOrder
     *            the byte order to read multi-byte values in. Pass {@link ByteOrder#nativeOrder()} for content
     *            written in the byte order of the machine this is running on.
     */
    public RandomAccessByteBufferReader(final ByteBuffer byteBuffer, final long sliceStartPos,
            final long sliceLength, final ByteOrder byteOrder) {
        this(byteBuffer, sliceStartPos, sliceLength, byteOrder, /* isReleased = */ null);
    }

    /**
     * Constructor.
     *
     * @param byteBuffer
     *            the byte buffer
     * @param sliceStartPos
     *            the slice start pos
     * @param sliceLength
     *            the slice length
     * @param byteOrder
     *            the byte order to read multi-byte values in. Pass {@link ByteOrder#nativeOrder()} for content
     *            written in the byte order of the machine this is running on.
     * @param isReleased
     *            whether the file the buffer is a view of has been released, or null if it cannot be released while
     *            this reader is alive
     */
    public RandomAccessByteBufferReader(final ByteBuffer byteBuffer, final long sliceStartPos,
            final long sliceLength, final ByteOrder byteOrder, final @Nullable BooleanSupplier isReleased) {
        this.isReleased = isReleased;
        // Take a read-only duplicate, so that this reader has its own position, limit and byte order, and cannot
        // write through to a buffer that may be a memory mapping shared by every thread reading the same file
        this.byteBuffer = byteBuffer.asReadOnlyBuffer();
        this.byteBuffer.order(byteOrder);
        this.sliceStartPos = (int) sliceStartPos;
        this.sliceLength = (int) sliceLength;
        this.byteBuffer.position(this.sliceStartPos);
        this.byteBuffer.limit(this.sliceStartPos + this.sliceLength);
    }

    @Override
    public ByteOrder byteOrder() {
        return byteBuffer.order();
    }

    @Override
    public long length() {
        return sliceLength;
    }

    /**
     * Check that the file can still be read, and that a read stays within the slice, so that it cannot read the
     * bytes that surround the slice in the buffer. (A zipfile can ask for a read at any offset, since offsets are
     * read from the zipfile itself.)
     *
     * @param offset
     *            the offset to read from, relative to the start of the slice
     * @param numBytes
     *            the number of bytes to read
     * @throws IOException
     *             if the file has been released, or the read would run past either end of the slice
     */
    private void checkReadable(final long offset, final int numBytes) throws IOException {
        // A reader is not closed by anything, so this check is what stops a reader that outlived the close of its
        // slice from reading a file that has been unmapped -- which below JDK 22 is memory that is no longer there
        if (isReleased != null && isReleased.getAsBoolean()) {
            throw new IOException("Cannot read a file that has been unmapped by closing the Vfs");
        }
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
        return new IOException("Cannot read a file that has been unmapped by closing the Vfs", e);
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
     *             if the file has been released, or the offset or the number of bytes is negative.
     */
    private int numBytesAvailable(final long srcOffset, final int numBytes) throws IOException {
        if (isReleased != null && isReleased.getAsBoolean()) {
            throw new IOException("Cannot read a file that has been unmapped by closing the Vfs");
        }
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
            final var srcStart = (int) srcOffset;
            // An absolute get, so this reader's own position and limit stay where the constructor put them
            byteBuffer.get(sliceStartPos + srcStart, dstArr, dstArrStart, numBytesToRead);
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            // The bounds were checked above, so reaching here means a bounds check is wrong rather than that
            // the caller asked for too much -- without the cause there is no record of which index was rejected
            throw new IOException("Read index out of bounds", e);
        } catch (final IllegalStateException e) {
            throw unmapped(e);
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
            // An absolute put, so neither buffer's position or limit is read or changed. Both ends of the copy are
            // named by index, so nothing has to be windowed first and nothing has to be put back afterwards.
            dstBuf.put(dstBufStart, byteBuffer, srcStart, numBytesToRead);
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            // The bounds were checked above, so reaching here means a bounds check is wrong rather than that
            // the caller asked for too much -- without the cause there is no record of which index was rejected
            throw new IOException("Read index out of bounds", e);
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        }
    }

    @Override
    public byte readByte(final long offset) throws IOException {
        checkReadable(offset, 1);
        final var idx = (int) (sliceStartPos + offset);
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
        checkReadable(offset, 2);
        final var idx = (int) (sliceStartPos + offset);
        try {
            return byteBuffer.getShort(idx);
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        }
    }

    @Override
    public int readInt(final long offset) throws IOException {
        checkReadable(offset, 4);
        final var idx = (int) (sliceStartPos + offset);
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
        checkReadable(offset, 8);
        final var idx = (int) (sliceStartPos + offset);
        try {
            return byteBuffer.getLong(idx);
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        }
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
        checkReadable(offset, numBytes);
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
