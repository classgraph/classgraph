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

/** Bounds checks that every {@link RandomAccessReader} does the same way. */
final class ReaderBounds {
    /** Cannot be instantiated. */
    private ReaderBounds() {
    }

    /**
     * The number of bytes a bulk read has room to write, given the size of the destination and the offset it was
     * asked to start writing at.
     *
     * @param dstSize
     *            the number of bytes the destination can be written to -- the length of an array, or the limit of a
     *            buffer. The limit, not the capacity: an absolute {@link java.nio.ByteBuffer} transfer is bounded
     *            by the limit, and a caller that lowered it did so to say that the bytes past it are not to be
     *            written.
     * @param dstStart
     *            the offset within the destination to start writing at.
     * @return the number of bytes free at that offset, which is zero if the destination is full there.
     * @throws IOException
     *             if the offset is not within the destination, since a caller that passed one cannot be told how
     *             many bytes are free at it, and reporting zero would stop its copy loop as if the destination were
     *             merely full.
     */
    static int numBytesFree(final int dstSize, final int dstStart) throws IOException {
        if (dstStart < 0 || dstStart > dstSize) {
            throw new IOException("Read index out of bounds");
        }
        return dstSize - dstStart;
    }
}
