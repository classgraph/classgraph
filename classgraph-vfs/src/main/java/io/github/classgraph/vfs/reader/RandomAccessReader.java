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
 * Reads values of a fixed width at any offset of some content, which may be a byte array, a {@link ByteBuffer}, a
 * file, or a stream buffered up to the point it has been read to.
 *
 * <h2>Byte order</h2>
 *
 * <p>
 * The byte order a reader reads multi-byte values in is a property of the <i>content</i>, not of the machine: it is
 * fixed by whatever wrote the bytes. The zipfile format is defined as little endian and the Java classfile format
 * as big endian, on every platform, so a reader of either reads the same values on a little endian and on a big
 * endian machine. Every reader in this package therefore has a byte order that is fixed when it is constructed and
 * reported by {@link #byteOrder()}, and none of them follows the byte order of the machine unless the caller asks
 * for that explicitly by passing {@link ByteOrder#nativeOrder()}.
 *
 * <p>
 * Each reader has a default byte order, which is the one the content it was written for is defined in, and a
 * constructor that takes a {@link ByteOrder} for content in the other order. Reading content whose byte order is
 * recorded in the content itself -- a TIFF file, or a machine-endian memory dump -- means reading the marker first
 * and then opening a second reader in the order it names.
 *
 * <h2>Reading past the end of the content</h2>
 *
 * <p>
 * The two {@code read} methods that fill a destination stop at the end of the content and report how far they got,
 * the way {@link java.io.InputStream#read(byte[], int, int)} does, because a caller that is copying content out
 * does not necessarily know how long it is -- and, for a deflated zip entry, neither does the reader until it has
 * inflated it. The methods that read a single value of a fixed width throw an {@link IOException} instead if the
 * value is not wholly within the content, since half of a value is not a value.
 *
 * <p>
 * Only the end of the content is reported as the end of the content. A read that was asked for no bytes, or that
 * was given a destination with no room left in it, has not reached the end of anything, and returns zero -- the way
 * {@link java.io.InputStream#read(byte[], int, int)} returns zero for a zero-length read, even at the end of the
 * stream.
 *
 * <p>
 * -1 means the end of the content was reached; 0 means the end of the destination was reached. A read-copy loop
 * must break on 0, since continuing until -1 loops forever, reading 0 bytes each time.
 *
 * <h2>Writing into a {@link ByteBuffer}</h2>
 *
 * <p>
 * The {@code read} method that fills a {@link ByteBuffer} writes at the index it is given, and does not change the
 * destination's position or limit -- it is an absolute transfer, like
 * {@link ByteBuffer#put(int, byte[], int, int)}. A caller that wants the position advanced over what was read, the
 * way a {@link java.nio.channels.SeekableByteChannel} advances it, has to do that itself.
 *
 * <p>
 * It writes no further than the destination's limit, not its capacity, since a caller that lowered the limit did so
 * to say that the bytes past it are not to be written. A read that would start past the limit is out of bounds.
 *
 * <p>
 * No method ever reports content that is not there. A reader whose length is overstated -- by a zip entry that
 * declares an uncompressed size larger than what its deflate stream actually holds -- stops at the last byte that
 * could really be read, rather than padding with zeroes.
 */
public interface RandomAccessReader {
    /**
     * The byte order this reader reads multi-byte values in, which is fixed when the reader is constructed and does
     * not follow the byte order of the machine.
     *
     * @return the byte order.
     */
    ByteOrder byteOrder();

    /**
     * The number of bytes of content this reader can read.
     *
     * <p>
     * For a reader over content whose length is already known -- an array, a buffer, a file, or a zip entry stored
     * uncompressed -- this is a field read. For a reader over a stream whose length is not known until the end of
     * it is reached -- a deflated zip entry, or a module resource -- the whole of the content has to be read to
     * answer, and is buffered, so a caller that does not need the length should not ask for it.
     *
     * @return the number of bytes of content.
     * @throws IOException
     *             If the content had to be read to find its length, and could not be read.
     */
    long length() throws IOException;

    /**
     * Read bytes into a {@link ByteBuffer}, stopping at the end of the content.
     *
     * @param srcOffset
     *            The offset to start reading from.
     * @param dstBuf
     *            The {@link ByteBuffer} to write into.
     * @param dstBufStart
     *            The index within the destination buffer to start writing at, which is not affected by, and does
     *            not affect, the destination's position.
     * @param numBytes
     *            The maximum number of bytes to read.
     * @return The number of bytes actually read, which is fewer than {@code numBytes} if the content ended first; 0
     *         if {@code numBytes} is zero, or the destination has no room left at {@code dstBufStart}; or -1 if
     *         {@code srcOffset} is at or past the end of the content. A read-copy loop must break on 0, or it loops
     *         forever: see {@link RandomAccessReader}.
     * @throws IOException
     *             If there was an exception while reading, or if {@code dstBufStart} is not within the destination.
     */
    int read(long srcOffset, ByteBuffer dstBuf, int dstBufStart, int numBytes) throws IOException;

    /**
     * Read bytes into a byte array, stopping at the end of the content.
     *
     * @param srcOffset
     *            The offset to start reading from.
     * @param dstArr
     *            The byte array to write into.
     * @param dstArrStart
     *            The offset within the destination array to start writing at.
     * @param numBytes
     *            The maximum number of bytes to read.
     * @return The number of bytes actually read, which is fewer than {@code numBytes} if the content ended first; 0
     *         if {@code numBytes} is zero, or the destination has no room left at {@code dstArrStart}; or -1 if
     *         {@code srcOffset} is at or past the end of the content. A read-copy loop must break on 0, or it loops
     *         forever: see {@link RandomAccessReader}.
     * @throws IOException
     *             If there was an exception while reading, or if {@code dstArrStart} is not within the destination.
     */
    int read(long srcOffset, byte[] dstArr, int dstArrStart, int numBytes) throws IOException;

    /**
     * Read a byte at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The byte at the offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    byte readByte(final long offset) throws IOException;

    /**
     * Read an unsigned byte at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The unsigned byte at the offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    int readUnsignedByte(final long offset) throws IOException;

    /**
     * Read a short at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The short at the offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    short readShort(final long offset) throws IOException;

    /**
     * Read a unsigned short at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The unsigned short at the offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    int readUnsignedShort(final long offset) throws IOException;

    /**
     * Read a int at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The int at the offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    int readInt(final long offset) throws IOException;

    /**
     * Read a unsigned int at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The int at the offset, as a long.
     * @throws IOException
     *             If there was an exception while reading.
     */
    long readUnsignedInt(final long offset) throws IOException;

    /**
     * Read a long at a specific offset (without changing the current cursor offset).
     *
     * @param offset
     *            The buffer offset to read from.
     * @return The long at the offset.
     * @throws IOException
     *             If there was an exception while reading.
     */
    long readLong(final long offset) throws IOException;

    /**
     * Reads the "modified UTF8" format defined in the Java classfile spec.
     *
     * @param offset
     *            The start offset of the string.
     * @param numBytes
     *            The number of bytes of the modified UTF8 encoding of the string.
     * @return The string.
     * @throws IOException
     *             If an I/O exception occurs.
     */
    String readStringModifiedUtf8(final long offset, final int numBytes) throws IOException;

    /**
     * Read a string in a given character encoding.
     *
     * @param offset
     *            The start offset of the string.
     * @param numBytes
     *            The number of bytes of the encoding of the string.
     * @param charset
     *            The character encoding to decode the bytes with.
     * @return The string.
     * @throws IOException
     *             If an I/O exception occurs.
     */
    String readString(final long offset, final int numBytes, final Charset charset) throws IOException;

    /**
     * Read a string in UTF-8, the standard encoding, rather than the "modified UTF8" format that the classfile
     * format stores its strings in.
     *
     * @param offset
     *            The start offset of the string.
     * @param numBytes
     *            The number of bytes of the UTF-8 encoding of the string.
     * @return The string.
     * @throws IOException
     *             If an I/O exception occurs.
     */
    default String readString(final long offset, final int numBytes) throws IOException {
        return readString(offset, numBytes, StandardCharsets.UTF_8);
    }
}
