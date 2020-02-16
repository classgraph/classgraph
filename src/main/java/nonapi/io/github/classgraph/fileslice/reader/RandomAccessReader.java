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
 * Copyright (c) 2020 Luke Hutchison
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
package nonapi.io.github.classgraph.fileslice.reader;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Interface for random access to values in byte order. */
public interface RandomAccessReader {
    /**
     * Read bytes into a {@link ByteBuffer}.
     * 
     * @param srcOffset
     *            The offset to start reading from.
     * @param dstBuf
     *            The {@link ByteBuffer} to write into.
     * @param dstBufStart
     *            The offset within the destination buffer to start writing at.
     * @param numBytes
     *            The number of bytes to read.
     * @return The number of bytes actually read, or -1 if no more bytes could be read.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int read(long srcOffset, ByteBuffer dstBuf, int dstBufStart, int numBytes) throws IOException;

    /**
     * Read bytes into a byte array.
     * 
     * @param srcOffset
     *            The offset to start reading from.
     * @param dstArr
     *            The byte array to write into.
     * @param dstArrStart
     *            The offset within the destination array to start writing at.
     * @param numBytes
     *            The number of bytes to read.
     * @return The number of bytes actually read, or -1 if no more bytes could be read.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int read(long srcOffset, byte[] dstArr, int dstArrStart, int numBytes) throws IOException;

    /**
     * Read a byte at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The byte at the offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public byte readByte(final long offset) throws IOException;

    /**
     * Read an unsigned byte at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The unsigned byte at the offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int readUnsignedByte(final long offset) throws IOException;

    /**
     * Read a short at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The short at the offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public short readShort(final long offset) throws IOException;

    /**
     * Read a unsigned short at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The unsigned short at the offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int readUnsignedShort(final long offset) throws IOException;

    /**
     * Read a int at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The int at the offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int readInt(final long offset) throws IOException;

    /**
     * Read a unsigned int at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The int at the offset, as a long.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public long readUnsignedInt(final long offset) throws IOException;

    /**
     * Read a long at a specific offset (without changing the current cursor offset).
     * 
     * @param offset
     *            The buffer offset to read from.
     * @return The long at the offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public long readLong(final long offset) throws IOException;

    /**
     * Reads the "modified UTF8" format defined in the Java classfile spec, optionally replacing '/' with '.', and
     * optionally removing the prefix "L" and the suffix ";".
     *
     * @param offset
     *            The start offset of the string.
     * @param numBytes
     *            The number of bytes of the UTF8 encoding of the string.
     * @param replaceSlashWithDot
     *            If true, replace '/' with '.'.
     * @param stripLSemicolon
     *            If true, string final ';' character.
     * @return The string.
     * @throws IOException
     *             If an I/O exception occurs.
     */
    public String readString(final long offset, final int numBytes, final boolean replaceSlashWithDot,
            final boolean stripLSemicolon) throws IOException;

    /**
     * Reads the "modified UTF8" format defined in the Java classfile spec.
     *
     * @param offset
     *            The start offset of the string.
     * @param numBytes
     *            The number of bytes of the UTF8 encoding of the string.
     * @return The string.
     * @throws IOException
     *             If an I/O exception occurs.
     */
    public String readString(final long offset, final int numBytes) throws IOException;
}
