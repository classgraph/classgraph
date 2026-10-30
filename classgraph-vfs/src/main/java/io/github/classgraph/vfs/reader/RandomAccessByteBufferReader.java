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

/**
 * {@link RandomAccessReader} backed by a slice of a {@link ByteBuffer}. Defaults to {@link ByteOrder#LITTLE_ENDIAN}
 * (operating on a read-only duplicate without modifying the original buffer's position, limit, or byte order).
 *
 * <p>
 * If the buffer views memory that has been released (such as a closed {@link java.lang.foreign.Arena} or a buffer
 * whose {@code isReleased} supplier returns {@code true}), reads throw {@link IOException}.
 */
public final class RandomAccessByteBufferReader implements RandomAccessReader {
    /**
     * The smallest bulk read that is copied in bulk and then fenced, rather than one {@code long} at a time, on
     * x86-64 (see {@link #canFault}). The fence costs about 40 ns, which a smaller read does not win
     * back: on JDK 26, a 64-byte read takes 62 ns one {@code long} at a time and 112 ns in bulk with the fence, a
     * 256-byte read takes about 120 ns either way, and a 1 MB read takes 74 us one {@code long} at a time and 47 us
     * in bulk. Elsewhere, no read is copied in bulk.
     */
    private static final int MIN_FENCED_BULK_COPY_SIZE = VersionFinder.IS_X86_64 ? 256 : Integer.MAX_VALUE;

    /** A lock that no thread ever holds, which {@link #awaitFault()} asks about. */
    private static final Object FAULT_FENCE = new Object();

    /** Read-only duplicate of the backing byte buffer. */
    private final ByteBuffer byteBuffer;

    /** The start index of the slice within the buffer. */
    private final int sliceStartPos;

    /** The length of the slice in bytes. */
    private final int sliceLength;

    /** Optional supplier returning true if the underlying storage has been released. */
    private final @Nullable BooleanSupplier isReleased;

    /**
     * Whether a read can fault, so that it has to be made so that the fault is thrown inside the read. A heap
     * buffer cannot fault. A read of a memory mapping faults when the page it touches cannot be read: the page is
     * past the end of a file that was truncated after it was mapped (Linux and macOS; Windows refuses to truncate a
     * mapped file), or the disk failed or was detached, or a network server stopped (every OS). The JVM turns the
     * fault into an {@link InternalError}. Once the read has been JIT-compiled, the JVM throws that error only at
     * the next point where the thread checks for pending work, which can be after the read has returned, past the
     * catch below that turns it into an {@link IOException} (seen for single-value reads and for bulk copies, on
     * JDK 25 and 26, on Linux, macOS and Windows). A return from a native method is such a point, so each
     * single-value read is followed by a call of one (see {@link #awaitFault()}), and so is each bulk copy on
     * x86-64. On aarch64 a compiled bulk copy of more than 64 bytes is done by a routine that the JVM does not
     * guard, so the fault kills the JVM (seen on Linux, macOS and Windows), and a bulk read copies one {@code long}
     * at a time instead (see {@link #copyOneLongAtATime}), which was seen to throw inside the read, cold and hot,
     * for every length tested, on every platform. That is also done on any other processor, where the bulk copy has
     * not been tested.
     */
    private final boolean canFault;

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
        this.canFault = byteBuffer.isDirect();
    }

    /**
     * If a read just made faulted, throw the {@link InternalError} for the fault here, rather than at some later
     * point outside the read's catch. The JVM throws a pending error of a JIT-compiled read on the return from a
     * native method, and {@link Thread#holdsLock} is one, and cheap (see {@link #canFault}).
     */
    private void awaitFault() {
        if (canFault && Thread.holdsLock(FAULT_FENCE)) {
            throw new IllegalStateException("No thread can hold the fault fence");
        }
    }

    /**
     * Copy bytes of the buffer into another buffer by single-value reads, rather than by a bulk copy, which kills
     * the JVM if it faults on aarch64 (see {@link #canFault}). The loop is unrolled by hand, which
     * makes a large copy about twice as fast.
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
        for (; i + 8 * Long.BYTES <= numBytes; i += 8 * Long.BYTES) {
            // All eight reads before any write, which is what makes the unrolled loop faster
            final var a = byteBuffer.getLong(srcStart + i);
            final var b = byteBuffer.getLong(srcStart + i + 8);
            final var c = byteBuffer.getLong(srcStart + i + 16);
            final var d = byteBuffer.getLong(srcStart + i + 24);
            final var e = byteBuffer.getLong(srcStart + i + 32);
            final var f = byteBuffer.getLong(srcStart + i + 40);
            final var g = byteBuffer.getLong(srcStart + i + 48);
            final var h = byteBuffer.getLong(srcStart + i + 56);
            dstBuf.putLong(dstBufStart + i, a);
            dstBuf.putLong(dstBufStart + i + 8, b);
            dstBuf.putLong(dstBufStart + i + 16, c);
            dstBuf.putLong(dstBufStart + i + 24, d);
            dstBuf.putLong(dstBufStart + i + 32, e);
            dstBuf.putLong(dstBufStart + i + 40, f);
            dstBuf.putLong(dstBufStart + i + 48, g);
            dstBuf.putLong(dstBufStart + i + 56, h);
        }
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
        // slice from reading a file that has been unmapped
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
     * Wrap the {@link InternalError} thrown when a read of a memory-mapped file touches a page that cannot be read,
     * in an {@link IOException}. The page is no longer in the file if the file was truncated after it was mapped,
     * and cannot be read if the disk fails or is detached, or the server of a network filesystem stops. (The
     * operating system signals the fault, and the JVM turns it into this error, which a read has to be made to
     * throw inside its own catch -- see {@link #canFault}.)
     *
     * @param e
     *            the error thrown by the buffer access.
     * @return the wrapping {@link IOException}.
     */
    private static IOException pageFault(final InternalError e) {
        return new IOException("Cannot read the mapped file: it was truncated, or its storage failed", e);
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
            if (canFault && numBytesToRead < MIN_FENCED_BULK_COPY_SIZE) {
                copyOneLongAtATime(sliceStartPos + srcStart, ByteBuffer.wrap(dstArr).order(byteBuffer.order()),
                        dstArrStart, numBytesToRead);
            } else {
                byteBuffer.get(sliceStartPos + srcStart, dstArr, dstArrStart, numBytesToRead);
                awaitFault();
            }
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            // The bounds were checked above, so reaching here means a bounds check is wrong rather than that
            // the caller asked for too much -- without the cause there is no record of which index was rejected
            throw new IOException("Read index out of bounds", e);
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        } catch (final InternalError e) {
            throw pageFault(e);
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
            if (canFault && numBytesToRead < MIN_FENCED_BULK_COPY_SIZE) {
                // A duplicate, so the caller's byte order is left alone
                copyOneLongAtATime(srcStart, dstBuf.duplicate().order(byteBuffer.order()), dstBufStart,
                        numBytesToRead);
            } else {
                dstBuf.put(dstBufStart, byteBuffer, srcStart, numBytesToRead);
                awaitFault();
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
            throw pageFault(e);
        }
    }

    @Override
    public byte readByte(final long offset) throws IOException {
        checkReadable(offset, 1);
        final var idx = (int) (sliceStartPos + offset);
        try {
            final var value = byteBuffer.get(idx);
            awaitFault();
            return value;
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        } catch (final InternalError e) {
            throw pageFault(e);
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
            final var value = byteBuffer.getShort(idx);
            awaitFault();
            return value;
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        } catch (final InternalError e) {
            throw pageFault(e);
        }
    }

    @Override
    public int readInt(final long offset) throws IOException {
        checkReadable(offset, 4);
        final var idx = (int) (sliceStartPos + offset);
        try {
            final var value = byteBuffer.getInt(idx);
            awaitFault();
            return value;
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        } catch (final InternalError e) {
            throw pageFault(e);
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
            final var value = byteBuffer.getLong(idx);
            awaitFault();
            return value;
        } catch (final IllegalStateException e) {
            throw unmapped(e);
        } catch (final InternalError e) {
            throw pageFault(e);
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
