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

/** Bounds checks that every {@link RandomAccessReader} does the same way. */
final class ReaderBounds {
    /** Not instantiable. */
    private ReaderBounds() {
    }

    /**
     * Check the arguments of a bulk read, and return the number of bytes it has room to write, given the size of
     * the destination and the offset it was asked to start writing at. A bulk read calls this before anything else,
     * including before it returns zero for a read of zero bytes or into a full destination, so that a bad argument
     * is reported even when the read would copy nothing.
     *
     * @param srcOffset
     *            the offset to start reading from.
     * @param dstSize
     *            the number of bytes the destination can be written to -- the length of an array, or the limit of a
     *            buffer. The limit, not the capacity: an absolute {@link ByteBuffer} transfer is bounded by the
     *            limit, and a caller that lowered it did so to say that the bytes past it are not to be written.
     * @param dstStart
     *            the offset within the destination to start writing at.
     * @param numBytes
     *            the maximum number of bytes to read.
     * @return the number of bytes free at that offset, which is zero if the destination is full there.
     * @throws IOException
     *             if the source offset or the number of bytes is negative, or if the destination offset is not
     *             within the destination. A caller that passed a bad destination offset cannot be told how many
     *             bytes are free at it, and a negative number of bytes is usually an overflow in the caller's
     *             length arithmetic. Reporting zero for either would stop the caller's copy loop as if the
     *             destination were merely full.
     */
    static int numBytesFree(final long srcOffset, final int dstSize, final int dstStart, final int numBytes)
            throws IOException {
        if (srcOffset < 0L || numBytes < 0 || dstStart < 0 || dstStart > dstSize) {
            throw new IOException("Read index out of bounds");
        }
        return dstSize - dstStart;
    }

    /**
     * Check the arguments of a bulk read into a {@link ByteBuffer}, as {@link #numBytesFree(long, int, int, int)}
     * does, and also check that the destination is not read-only.
     *
     * @param srcOffset
     *            the offset to start reading from.
     * @param dstBuf
     *            the destination, whose limit bounds the room to write.
     * @param dstBufStart
     *            the index within the destination to start writing at.
     * @param numBytes
     *            the maximum number of bytes to read.
     * @return the number of bytes free at that index, which is zero if the destination is full there.
     * @throws IOException
     *             if the destination is read-only, or if any argument is out of bounds.
     */
    static int numBytesFree(final long srcOffset, final ByteBuffer dstBuf, final int dstBufStart,
            final int numBytes) throws IOException {
        if (dstBuf.isReadOnly()) {
            // Checked here rather than left to the transfer, so that every RandomAccessReader reports a read-only
            // destination the same way, whether or not the read would copy anything: a put would throw
            // ReadOnlyBufferException, and FileChannel rejects a read-only destination with an
            // IllegalArgumentException
            throw new IOException("The destination buffer is read-only");
        }
        return numBytesFree(srcOffset, dstBuf.limit(), dstBufStart, numBytes);
    }
}
