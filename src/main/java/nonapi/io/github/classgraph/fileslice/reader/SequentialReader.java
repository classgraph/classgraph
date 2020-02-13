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

/** Interface for sequentially reading values in byte order. */
public interface SequentialReader {
    /**
     * Read a byte at the current cursor position.
     *
     * @return The byte at the current cursor position.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public byte readByte() throws IOException;

    /**
     * Read an unsigned byte at the current cursor position.
     *
     * @return The unsigned byte at the current cursor position.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int readUnsignedByte() throws IOException;

    /**
     * Read a short at the current cursor position.
     *
     * @return The short at the current cursor position.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public short readShort() throws IOException;

    /**
     * Read a unsigned short at the current cursor position.
     *
     * @return The unsigned shortat the current cursor position.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int readUnsignedShort() throws IOException;

    /**
     * Read a int at the current cursor position.
     *
     * @return The int at the current cursor position.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public int readInt() throws IOException;

    /**
     * Read a unsigned int at the current cursor position.
     *
     * @return The int at the current cursor position, as a long.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public long readUnsignedInt() throws IOException;

    /**
     * Read a long at the current cursor position.
     * 
     * @return The long at the current cursor position.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public long readLong() throws IOException;

    /**
     * Skip the given number of bytes.
     * 
     * @param bytesToSkip
     *            The number of bytes to skip.
     * @throws IOException
     *             If there was an exception while reading.
     */
    public void skip(final int bytesToSkip) throws IOException;

    /**
     * Reads the "modified UTF8" format defined in the Java classfile spec, optionally replacing '/' with '.', and
     * optionally removing the prefix "L" and the suffix ";".
     *
     * @param numBytes
     *            The number of bytes of the UTF8 encoding of the string, or if -1, read the length of the string as
     *            a short in the first two bytes at offset.
     * @param replaceSlashWithDot
     *            If true, replace '/' with '.'.
     * @param stripLSemicolon
     *            If true, string final ';' character.
     * @return The string.
     * @throws IOException
     *             If an I/O exception occurs.
     */
    public String readString(final int numBytes, final boolean replaceSlashWithDot, final boolean stripLSemicolon)
            throws IOException;

    /**
     * Reads the "modified UTF8" format defined in the Java classfile spec.
     *
     * @param numBytes
     *            The number of bytes of the UTF8 encoding of the string, or if -1, read the length of the string as
     *            a short in the first two bytes at offset.
     * @return The string.
     * @throws IOException
     *             If an I/O exception occurs.
     */
    public String readString(final int numBytes) throws IOException;
}
