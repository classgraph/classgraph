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
package io.github.classgraph.vfs.internal;

import java.io.IOException;
import java.io.InputStream;

/**
 * Utility methods for reading from an {@link InputStream}.
 */
public final class InputStreamUtils {
    /** Not instantiable. */
    private InputStreamUtils() {
    }

    /**
     * Read into a range of an array, as {@link InputStream#read(byte[], int, int)} does, falling back to
     * {@link InputStream#read()} if {@code read(buf, off, len)} returns zero for a non-empty range so that a
     * non-compliant stream never returns zero when {@code len > 0}.
     *
     * @param inputStream
     *            the stream to read from.
     * @param buf
     *            the destination buffer.
     * @param off
     *            the start offset in {@code buf}.
     * @param len
     *            the maximum number of bytes to read.
     * @return the number of bytes read, or -1 at the end of the stream (zero only when {@code len == 0}).
     * @throws IOException
     *             if the stream could not be read.
     */
    public static int readAtLeastOne(final InputStream inputStream, final byte[] buf, final int off, final int len)
            throws IOException {
        final var numBytesRead = inputStream.read(buf, off, len);
        if (numBytesRead != 0 || len == 0) {
            return numBytesRead;
        }
        final var nextByte = inputStream.read();
        if (nextByte < 0) {
            return -1;
        }
        buf[off] = (byte) nextByte;
        return 1;
    }
}
