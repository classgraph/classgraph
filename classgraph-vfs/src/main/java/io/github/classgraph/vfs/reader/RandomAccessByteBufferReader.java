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
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.utils.StringUtils;
import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.base.internal.utils.VersionFinder.OperatingSystem;

/**
 * {@link RandomAccessReader} backed by a slice of a {@link ByteBuffer}. Defaults to {@link ByteOrder#LITTLE_ENDIAN}
 * (operating on a read-only duplicate without modifying the original buffer's position, limit, or byte order).
 *
 * <p>
 * If the buffer views memory that has been released (such as a closed {@code java.lang.foreign.Arena} or a buffer
 * whose {@code isReleased} supplier returns {@code true}), reads throw {@link IOException}.
 */
public final class RandomAccessByteBufferReader implements RandomAccessReader {
    /**
     * Whether a bulk read out of a direct buffer has to copy it one {@code long} at a time. A read of a memory
     * mapping beyond the end of a file that was truncated after it was mapped faults. For a single-value read, the
     * JVM turns the fault into an {@link InternalError} thrown by the read, which is caught below. For a bulk copy
     * that has been JIT-compiled, it does not: on x86-64 the error is thrown only after the copy has returned, in
     * the caller, past the catch, and on aarch64 the stub that does the copy is not guarded, so the fault kills the
     * JVM (seen on JDK 25 and 26, on Linux and macOS). Windows does not allow a mapped file to be truncated, so a
     * bulk copy is safe there.
     */
    private static final boolean COPY_ONE_LONG_AT_A_TIME = VersionFinder.OS != OperatingSystem.Windows;

    /** Read-only duplicate of the backing byte buffer. */
    private final ByteBuffer byteBuffer;

    /** The start index of the slice within the buffer. */
    private final int sliceStartPos;

    /** The length of the slice in bytes. */
    private final int sliceLength;

    /** Optional supplier returning true if the underlying storage has been released. */
    private final @Nullable BooleanSupplier isReleased;

    /**
     * Whether bulk reads copy one {@code long} at a time (see {@link #COPY_ONE_LONG_AT_A_TIME}). A heap buffer
     * cannot fault, so it is always copied in bulk.
     */
    private final boolean copyOneLongAtATime;

    /**
     * Construct a little-endian reader over a slice of a {@link ByteBuffer}.
     *
     * @param byteBuffer
     *            the buffer to read (its position, limit, and byte order are ignored).
     * @param sliceStartPos
     *            the start index of the slice within the buffer.
     * @param sliceLength
     *            the length of the slice in bytes.
     * @throws IndexOutOfBoundsException
     *             if the slice is not within the buffer's capacity.
     */
    public RandomAccessByteBufferReader(final ByteBuffer byteBuffer, final long sliceStartPos,
            final long sliceLength) {
        this(byteBuffer, sliceStartPos, sliceLength, ByteOrder.LITTLE_ENDIAN, /* isReleased = */ null);
    }

    /**
     * Construct a reader over a slice of a {@link ByteBuffer} with the specified byte order.
     *
     * @param byteBuffer
     *            the buffer to read (its position, limit, and byte order are ignored).
     * @param sliceStartPos
     *            the start index of the slice within the buffer.
     * @param sliceLength
     *            the length of the slice in bytes.
     * @param byteOrder
     *            the byte order for multi-byte values.
     * @throws IndexOutOfBoundsException
     *             if the slice is not within the buffer's capacity.
     */
    public RandomAccessByteBufferReader(final ByteBuffer byteBuffer, final long sliceStartPos,
            final long sliceLength, final ByteOrder byteOrder) {
        this(byteBuffer, sliceStartPos, sliceLength, byteOrder, /* isReleased = */ null);
    }

    /**
     * Construct a reader over a slice of a {@link ByteBuffer} with the specified byte order and release check.
     *
     * @param byteBuffer
     *            the buffer to read (its position, limit, and byte order are ignored).
     * @param sliceStartPos
     *            the start index of the slice within the buffer.
     * @param sliceLength
     *            the length of the slice in bytes.
     * @param byteOrder
     *            the byte order for multi-byte values.
     * @param isReleased
     *            supplier returning true if the underlying storage has been released, or null if not applicable.
     * @throws IndexOutOfBoundsException
     *             if the slice is not within the buffer's capacity.
     */
    public RandomAccessByteBufferReader(final ByteBuffer byteBuffer, final long sliceStartPos,
            final long sliceLength, final ByteOrder byteOrder, final @Nullable BooleanSupplier isReleased) {
        // Checked here, so that a slice that does not fit is reported when the reader is made rather than by a
        // read, and so that the casts to int below cannot truncate
        Objects.checkFromIndexSize(sliceStartPos, sliceLength, byteBuffer.capacity());
        this.isReleased = isReleased;
        // Take a read-only duplicate, so that this reader has its own position, limit and byte order, and cannot
        // write through to a buffer that may be a memory mapping shared by every thread reading the same file
        this.byteBuffer = byteBuffer.asReadOnlyBuffer();
        this.byteBuffer.order(byteOrder);
        this.sliceStartPos = (int) sliceStartPos;
        this.sliceLength = (int) sliceLength;
        // The limit is set first, since the duplicate has the caller's limit, which may be below sliceStartPos, and
        // a position past the limit is rejected. Setting the limit moves the position back to it if it was past it.
        this.byteBuffer.limit(this.sliceStartPos + this.sliceLength);
        this.byteBuffer.position(this.sliceStartPos);
        this.copyOneLongAtATime = COPY_ONE_LONG_AT_A_TIME && byteBuffer.isDirect();
    }

    /**
     * Copy bytes of the buffer into another buffer by single-value reads, which throw when they fault, rather than
     * by a bulk copy, which may not (see {@link #COPY_ONE_LONG_AT_A_TIME}).
     *
     * @param srcStart
     *            the index in the buffer to copy from.
     * @param dstBuf
     *            the buffer to copy into, with the same byte order as {@link #byteBuffer}.
     * @param dstBufStart
     *            the index in the destination buffer to copy to.
     * @param numBytes
     *            the number of bytes to copy, which the caller has checked fit in both buffers.
     */
    private void copyOneLongAtATime(final int srcStart, final ByteBuffer dstBuf, final int dstBufStart,
            final int numBytes) {
        var i = 0;
        for (; i + Long.BYTES <= numBytes; i += Long.BYTES) {
            dstBuf.putLong(dstBufStart + i, byteBuffer.getLong(srcStart + i));
        }
        for (; i < numBytes; i++) {
            dstBuf.put(dstBufStart + i, byteBuffer.get(srcStart + i));
        }
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
     * Verify that the underlying storage is still open and that a read of {@code numBytes} at {@code offset} lies
     * within the slice.
     *
     * @param offset
     *            the offset relative to the start of the slice.
     * @param numBytes
     *            the number of bytes to read.
     * @throws IOException
     *             if the underlying storage has been released or the range is out of bounds.
     */
    private void checkReadable(final long offset, final int numBytes) throws IOException {
        // A reader is not closed by anything, so this check is what stops a reader that outlived the close of its
        // slice from reading a file that has been unmapped -- which below JDK 22 is memory that is no longer there
        if (isReleased != null && isReleased.getAsBoolean()) {
            throw new IOException("Cannot read a file that has been unmapped by closing what it was read through");
        }
        // Compare by subtraction rather than addition, so that a large offset plus a large numBytes cannot
        // overflow and slip past the check
        if (offset < 0L || numBytes < 0 || numBytes > sliceLength - offset) {
            throw new IOException("Read index out of bounds");
        }
    }

    /**
     * Wrap an {@link IllegalStateException} thrown when accessing a closed memory-mapped arena in an
     * {@link IOException}.
     *
     * @param e
     *            the exception thrown by the buffer access.
     * @return the wrapping {@link IOException}.
     */
    private static IOException unmapped(final IllegalStateException e) {
        return new IOException("Cannot read a file that has been unmapped by closing what it was read through", e);
    }

    /**
     * Wrap the {@link InternalError} thrown when a read of a memory-mapped file touches a page that is no longer in
     * the file, because the file was truncated after it was mapped, in an {@link IOException}. (The operating
     * system signals the fault, and the JVM turns it into this error at the read that caused it, if the read is a
     * single-value read -- see {@link #COPY_ONE_LONG_AT_A_TIME}.)
     *
     * @param e
     *            the error thrown by the buffer access.
     * @return the wrapping {@link IOException}.
     */
    private static IOException truncated(final InternalError e) {
        return new IOException("File is truncated", e);
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
     * @throws IOException
     *             if the underlying storage has been released.
     */
    private int numBytesAvailable(final long srcOffset, final int numBytes) throws IOException {
        if (isReleased != null && isReleased.getAsBoolean()) {
            throw new IOException("Cannot read a file that has been unmapped by closing what it was read through");
        }
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
            final var srcStart = (int) srcOffset;
            // An absolute get, so this reader's own position and limit stay where the constructor put them
            if (copyOneLongAtATime) {
                copyOneLongAtATime(sliceStartPos + srcStart, ByteBuffer.wrap(dstArr).order(byteBuffer.order()),
                        dstArrStart, numBytesToRead);
            } else {
                byteBuffer.get(sliceStartPos + srcStart, dstArr, dstArrStart, numBytesToRead);
            }
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            // The bounds were checked above, so reaching here means a bounds check is wrong rather than that
            // the caller asked for too much -- without the cause there is no record of which index was rejected
            throw new IOException("Read index out of bounds", e);
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        } catch (final InternalError e) {
            throw truncated(e);
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
            // An absolute put, so neither buffer's position or limit is read or changed. Both ends of the copy are
            // named by index, so nothing has to be windowed first and nothing has to be put back afterwards.
            if (copyOneLongAtATime) {
                // A duplicate, so the caller's byte order is left alone
                copyOneLongAtATime(srcStart, dstBuf.duplicate().order(byteBuffer.order()), dstBufStart,
                        numBytesToRead);
            } else {
                dstBuf.put(dstBufStart, byteBuffer, srcStart, numBytesToRead);
            }
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            // The bounds were checked above, so reaching here means a bounds check is wrong rather than that
            // the caller asked for too much -- without the cause there is no record of which index was rejected
            throw new IOException("Read index out of bounds", e);
        } catch (final IllegalStateException e) {
            // Either buffer can be a view of a closed arena, and the exception does not say which
            throw new IOException("Cannot read a file that has been unmapped by closing what it was read through,"
                    + " or write into a destination buffer whose memory has been freed", e);
        } catch (final InternalError e) {
            throw truncated(e);
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
        } catch (final InternalError e) {
            throw truncated(e);
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
        } catch (final InternalError e) {
            throw truncated(e);
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
        } catch (final InternalError e) {
            throw truncated(e);
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
        } catch (final InternalError e) {
            throw truncated(e);
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
