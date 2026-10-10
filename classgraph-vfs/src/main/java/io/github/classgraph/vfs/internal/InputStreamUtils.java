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
 * Reading from an {@link InputStream} that may not keep to the contract of
 * {@link InputStream#read(byte[], int, int)}.
 */
public final class InputStreamUtils {
    /** Not instantiable. */
    private InputStreamUtils() {
    }

    /**
     * Read into a range of an array, as {@link InputStream#read(byte[], int, int)} does, but never return zero for
     * a range that is not empty.
     *
     * <p>
     * {@link InputStream#read(byte[], int, int)} is supposed to block until at least one byte has been read, but
     * some streams return zero instead, at their end or before it. Taking that zero for the end of the stream
     * truncates the content, and reading again loops forever on a stream that returns zero at its end, which is
     * what {@link InputStream#transferTo(java.io.OutputStream)} and
     * {@link InputStream#readNBytes(byte[], int, int)} do. So a zero is resolved by reading a single byte with
     * {@link InputStream#read()}, which can only return a byte or -1 for the end of the stream.
     *
     * @param inputStream
     *            the stream to read from.
     * @param buf
     *            the array to read into.
     * @param off
     *            the index in {@code buf} to read into.
     * @param len
     *            the largest number of bytes to read.
     * @return the number of bytes read, or -1 at the end of the stream. This is zero only if {@code len} is zero.
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
