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
 * Reads values of a fixed width from the current position of some content, advancing the position by the width of
 * each value read.
 *
 * <p>
 * The byte order is a property of the content and is fixed when the reader is constructed -- see
 * {@link RandomAccessReader} for what that means and why none of these readers follows the byte order of the
 * machine. A value that is not wholly within the content throws an {@link IOException}, since half of a value is
 * not a value.
 */
public interface SequentialReader {
    /**
     * The byte order this reader reads multi-byte values in, which is fixed when the reader is constructed and does
     * not follow the byte order of the machine.
     *
     * @return the byte order.
     */
    ByteOrder byteOrder();

    /**
     * The position that the next value is read from, relative to the start of the content. It starts at zero, and
     * is advanced by each read, and by {@link #skip(int)}.
     *
     * @return the current position.
     */
    int position();

    /**
     * Read a byte at the current position.
     *
     * @return The byte at the current position.
     * @throws IOException
     *             If the value is not wholly within the content, or could not be read.
     */
    byte readByte() throws IOException;

    /**
     * Read an unsigned byte at the current position.
     *
     * @return The unsigned byte at the current position.
     * @throws IOException
     *             If the value is not wholly within the content, or could not be read.
     */
    int readUnsignedByte() throws IOException;

    /**
     * Read a short at the current position.
     *
     * @return The short at the current position.
     * @throws IOException
     *             If the value is not wholly within the content, or could not be read.
     */
    short readShort() throws IOException;

    /**
     * Read an unsigned short at the current position.
     *
     * @return The unsigned short at the current position.
     * @throws IOException
     *             If the value is not wholly within the content, or could not be read.
     */
    int readUnsignedShort() throws IOException;

    /**
     * Read an int at the current position.
     *
     * @return The int at the current position.
     * @throws IOException
     *             If the value is not wholly within the content, or could not be read.
     */
    int readInt() throws IOException;

    /**
     * Read an unsigned int at the current position.
     *
     * @return The int at the current position, as a long.
     * @throws IOException
     *             If the value is not wholly within the content, or could not be read.
     */
    long readUnsignedInt() throws IOException;

    /**
     * Read a long at the current position.
     *
     * @return The long at the current position.
     * @throws IOException
     *             If the value is not wholly within the content, or could not be read.
     */
    long readLong() throws IOException;

    /**
     * Skip the given number of bytes, advancing the position past them.
     *
     * @param bytesToSkip
     *            The number of bytes to skip.
     * @throws IOException
     *             If {@code bytesToSkip} is negative, if skipping would go past the end of the content, or if the
     *             content could not be read.
     */
    void skip(int bytesToSkip) throws IOException;

    /**
     * Read a string in the "modified UTF-8" format that the Java classfile format stores its strings in.
     *
     * @param numBytes
     *            The number of bytes of the modified UTF-8 encoding of the string.
     * @return The string.
     * @throws IOException
     *             If the bytes are not wholly within the content, or could not be read, or are not valid modified
     *             UTF-8 (a {@link java.io.UTFDataFormatException}).
     */
    String readStringModifiedUtf8(int numBytes) throws IOException;

    /**
     * Read a string in a given character encoding.
     *
     * @param numBytes
     *            The number of bytes of the encoding of the string.
     * @param charset
     *            The character encoding to decode the bytes with.
     * @return The string.
     * @throws IOException
     *             If the bytes are not wholly within the content, or could not be read.
     */
    String readString(int numBytes, Charset charset) throws IOException;

    /**
     * Read a string in UTF-8, the standard encoding, rather than the "modified UTF-8" format that the classfile
     * format stores its strings in.
     *
     * @param numBytes
     *            The number of bytes of the UTF-8 encoding of the string.
     * @return The string.
     * @throws IOException
     *             If the bytes are not wholly within the content, or could not be read.
     */
    default String readString(final int numBytes) throws IOException {
        return readString(numBytes, StandardCharsets.UTF_8);
    }
}
