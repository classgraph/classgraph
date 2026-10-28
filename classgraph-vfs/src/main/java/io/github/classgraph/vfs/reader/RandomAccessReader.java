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
import java.nio.charset.StandardCharsets;

/**
 * Random-access reader for primitive values, strings, and byte ranges at arbitrary offsets within binary content.
 *
 * <ul>
 * <li><b>Byte order:</b> Fixed at construction time and reported by {@link #byteOrder()} (pass
 * {@link ByteOrder#nativeOrder()} to constructors when machine byte order is desired).
 * <li><b>End-of-content behavior:</b> Bulk {@code read} methods stop at the end of the content and return the
 * number of bytes read, {@code 0} if {@code numBytes == 0} or no room remains in the destination, or {@code -1} if
 * {@code srcOffset} is at or past the end of the content (copy loops must terminate on both {@code <= 0}
 * conditions). Single-value and string read methods throw {@link IOException} if the requested bytes do not lie
 * wholly within the content.
 * <li><b>{@link ByteBuffer} destinations:</b> {@link #read(long, ByteBuffer, int, int)} performs an absolute write
 * at {@code dstBufStart} bounded by {@code dstBuf.limit()}, without reading or modifying {@code dstBuf.position()}
 * or {@code dstBuf.limit()}.
 * <li><b>Thread safety:</b> Readers are not thread-safe; use a separate reader instance per thread.
 * </ul>
 */
public interface RandomAccessReader {
    /**
     * Returns the byte order used to read multi-byte values.
     *
     * @return the byte order.
     */
    ByteOrder byteOrder();

    /**
     * Returns the total length of the content in bytes.
     *
     * <p>
     * For stream-backed readers (such as {@link RandomAccessOrSequentialReader}), calling this method reads and
     * buffers the entire stream to determine its length.
     *
     * @return the number of bytes of content.
     * @throws IOException
     *             if the content length could not be determined.
     */
    long length() throws IOException;

    /**
     * Read up to {@code numBytes} into a {@link ByteBuffer} starting at {@code srcOffset}, without modifying the
     * destination buffer's position or limit.
     *
     * @param srcOffset
     *            the offset in the content to start reading from.
     * @param dstBuf
     *            the destination {@link ByteBuffer}.
     * @param dstBufStart
     *            the start index within {@code dstBuf}.
     * @param numBytes
     *            the maximum number of bytes to read.
     * @return the number of bytes read, {@code 0} if {@code numBytes == 0} or {@code dstBuf} has no space left
     *         before its limit at {@code dstBufStart}, or {@code -1} if {@code srcOffset} is at or past the end of
     *         the content.
     * @throws IOException
     *             if {@code srcOffset} or {@code numBytes} is negative, {@code dstBufStart} is out of bounds,
     *             {@code dstBuf} is read-only, or an I/O error occurs.
     */
    int read(long srcOffset, ByteBuffer dstBuf, int dstBufStart, int numBytes) throws IOException;

    /**
     * Read up to {@code numBytes} into a byte array starting at {@code srcOffset}.
     *
     * @param srcOffset
     *            the offset in the content to start reading from.
     * @param dstArr
     *            the destination byte array.
     * @param dstArrStart
     *            the start index within {@code dstArr}.
     * @param numBytes
     *            the maximum number of bytes to read.
     * @return the number of bytes read, {@code 0} if {@code numBytes == 0} or {@code dstArr} has no space left at
     *         {@code dstArrStart}, or {@code -1} if {@code srcOffset} is at or past the end of the content.
     * @throws IOException
     *             if {@code srcOffset} or {@code numBytes} is negative, {@code dstArrStart} is out of bounds, or an
     *             I/O error occurs.
     */
    int read(long srcOffset, byte[] dstArr, int dstArrStart, int numBytes) throws IOException;

    /**
     * Read a signed 8-bit byte at the given offset.
     *
     * @param offset
     *            the offset to read from.
     * @return the byte at the offset.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    byte readByte(long offset) throws IOException;

    /**
     * Read an unsigned 8-bit byte at the given offset.
     *
     * @param offset
     *            the offset to read from.
     * @return the unsigned byte at the offset.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    int readUnsignedByte(long offset) throws IOException;

    /**
     * Read a signed 16-bit short at the given offset.
     *
     * @param offset
     *            the offset to read from.
     * @return the short at the offset.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    short readShort(long offset) throws IOException;

    /**
     * Read an unsigned 16-bit short at the given offset.
     *
     * @param offset
     *            the offset to read from.
     * @return the unsigned short at the offset.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    int readUnsignedShort(long offset) throws IOException;

    /**
     * Read a signed 32-bit int at the given offset.
     *
     * @param offset
     *            the offset to read from.
     * @return the int at the offset.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    int readInt(long offset) throws IOException;

    /**
     * Read an unsigned 32-bit int at the given offset.
     *
     * @param offset
     *            the offset to read from.
     * @return the unsigned int at the offset, as a long.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    long readUnsignedInt(long offset) throws IOException;

    /**
     * Read a signed 64-bit long at the given offset.
     *
     * @param offset
     *            the offset to read from.
     * @return the long at the offset.
     * @throws IOException
     *             if the value is not wholly within the content, or could not be read.
     */
    long readLong(long offset) throws IOException;

    /**
     * Read a Java classfile "modified UTF-8" string at the given offset.
     *
     * @param offset
     *            the start offset of the string.
     * @param numBytes
     *            the number of encoded bytes.
     * @return the decoded string.
     * @throws IOException
     *             if the bytes are not wholly within the content, could not be read, or are not valid modified
     *             UTF-8.
     */
    String readStringModifiedUtf8(long offset, int numBytes) throws IOException;

    /**
     * Read a string at the given offset using the specified {@link Charset}.
     *
     * @param offset
     *            the start offset of the string.
     * @param numBytes
     *            the number of encoded bytes.
     * @param charset
     *            the character encoding.
     * @return the decoded string.
     * @throws IOException
     *             if the bytes are not wholly within the content, or could not be read.
     */
    String readString(long offset, int numBytes, Charset charset) throws IOException;

    /**
     * Read a standard UTF-8 string at the given offset.
     *
     * @param offset
     *            the start offset of the string.
     * @param numBytes
     *            the number of UTF-8 bytes.
     * @return the decoded string.
     * @throws IOException
     *             if the bytes are not wholly within the content, or could not be read.
     */
    default String readString(final long offset, final int numBytes) throws IOException {
        return readString(offset, numBytes, StandardCharsets.UTF_8);
    }
}
