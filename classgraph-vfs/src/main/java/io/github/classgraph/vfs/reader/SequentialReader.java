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
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Sequential reader for primitive values and strings from a cursor position that advances with each read.
 *
 * <p>
 * Multi-byte values are read in the fixed {@link #byteOrder()} configured at construction time. Any read or skip
 * that extends past the end of the content throws {@link IOException}.
 */
public interface SequentialReader {
    /**
     * Returns the byte order used to read multi-byte values.
     *
     * @return the byte order.
     */
    ByteOrder byteOrder();

    /**
     * Returns the current read position relative to the start of the content.
     *
     * @return the current position.
     */
    int position();

    /**
     * Read a signed 8-bit byte at the current position and advance the position by 1.
     *
     * @return the byte at the current position.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    byte readByte() throws IOException;

    /**
     * Read an unsigned 8-bit byte at the current position and advance the position by 1.
     *
     * @return the unsigned byte at the current position.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    int readUnsignedByte() throws IOException;

    /**
     * Read a signed 16-bit short at the current position and advance the position by 2.
     *
     * @return the short at the current position.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    short readShort() throws IOException;

    /**
     * Read an unsigned 16-bit short at the current position and advance the position by 2.
     *
     * @return the unsigned short at the current position.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    int readUnsignedShort() throws IOException;

    /**
     * Read a signed 32-bit int at the current position and advance the position by 4.
     *
     * @return the int at the current position.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    int readInt() throws IOException;

    /**
     * Read an unsigned 32-bit int at the current position and advance the position by 4.
     *
     * @return the unsigned int at the current position, as a long.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    long readUnsignedInt() throws IOException;

    /**
     * Read a signed 64-bit long at the current position and advance the position by 8.
     *
     * @return the long at the current position.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    long readLong() throws IOException;

    /**
     * Advance the position by {@code bytesToSkip} bytes.
     *
     * @param bytesToSkip
     *            the number of bytes to skip.
     * @throws IOException
     *             if {@code bytesToSkip} is negative, skipping would go past the end of the content, or the content
     *             could not be read.
     */
    void skip(int bytesToSkip) throws IOException;

    /**
     * Read a Java classfile "modified UTF-8" string at the current position and advance the position by
     * {@code numBytes}.
     *
     * @param numBytes
     *            the number of encoded bytes.
     * @return the decoded string.
     * @throws IOException
     *             if the bytes are not wholly within the content, could not be read, or are not valid modified
     *             UTF-8.
     */
    String readStringModifiedUtf8(int numBytes) throws IOException;

    /**
     * Read a string at the current position using the specified {@link Charset} and advance the position by
     * {@code numBytes}.
     *
     * @param numBytes
     *            the number of encoded bytes.
     * @param charset
     *            the character encoding.
     * @return the decoded string.
     * @throws IOException
     *             if the bytes are not wholly within the content, or could not be read.
     */
    String readString(int numBytes, Charset charset) throws IOException;

    /**
     * Read a standard UTF-8 string at the current position and advance the position by {@code numBytes}.
     *
     * @param numBytes
     *            the number of UTF-8 bytes.
     * @return the decoded string.
     * @throws IOException
     *             if the bytes are not wholly within the content, or could not be read.
     */
    default String readString(final int numBytes) throws IOException {
        return readString(numBytes, StandardCharsets.UTF_8);
    }
}
