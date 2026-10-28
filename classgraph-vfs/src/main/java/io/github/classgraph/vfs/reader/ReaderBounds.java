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

/** Bounds checks shared by {@link RandomAccessReader} implementations. */
final class ReaderBounds {
    /** Not instantiable. */
    private ReaderBounds() {
    }

    /**
     * Validate bulk read arguments and return the number of bytes available to write in the destination.
     *
     * @param srcOffset
     *            the source offset to start reading from.
     * @param dstSize
     *            the writable size of the destination (array length or buffer limit).
     * @param dstStart
     *            the destination start index.
     * @param numBytes
     *            the maximum number of bytes to read.
     * @return the number of writable bytes remaining in the destination starting at {@code dstStart}.
     * @throws IOException
     *             if {@code srcOffset} or {@code numBytes} is negative, or {@code dstStart} is out of bounds.
     */
    static int numBytesFree(final long srcOffset, final int dstSize, final int dstStart, final int numBytes)
            throws IOException {
        if (srcOffset < 0L || numBytes < 0 || dstStart < 0 || dstStart > dstSize) {
            throw new IOException("Read index out of bounds");
        }
        return dstSize - dstStart;
    }

    /**
     * Validate bulk read arguments for a {@link ByteBuffer} destination and return the number of bytes available up
     * to the buffer's limit.
     *
     * @param srcOffset
     *            the source offset to start reading from.
     * @param dstBuf
     *            the destination buffer.
     * @param dstBufStart
     *            the destination start index.
     * @param numBytes
     *            the maximum number of bytes to read.
     * @return the number of writable bytes remaining up to {@code dstBuf.limit()} starting at {@code dstBufStart}.
     * @throws IOException
     *             if {@code dstBuf} is read-only or any argument is out of bounds.
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
