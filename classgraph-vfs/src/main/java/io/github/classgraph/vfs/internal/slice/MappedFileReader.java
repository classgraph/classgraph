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
package io.github.classgraph.vfs.internal.slice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

import io.github.classgraph.vfs.reader.RandomAccessByteBufferReader;
import io.github.classgraph.vfs.reader.RandomAccessReader;

/**
 * A reader of a slice of a memory-mapped file, which reads through the mapping, except that a bulk read of at least
 * {@link PathSlice#MIN_CHANNEL_READ_SIZE} bytes goes through a file channel, which is faster for a large read.
 */
final class MappedFileReader implements RandomAccessReader {
    /** The reader of the mapping. */
    private final RandomAccessByteBufferReader mappingReader;

    /** The toplevel slice of the file, which reads it through a file channel. */
    private final PathSlice topLevelPathSlice;

    /** The start of the slice within the file. */
    private final long sliceStartPos;

    /** The length of the slice. */
    private final long sliceLength;

    /**
     * Constructor.
     *
     * @param mappingReader
     *            the reader of the slice of the mapping.
     * @param topLevelPathSlice
     *            the toplevel slice of the file.
     * @param sliceStartPos
     *            the start of the slice within the file.
     * @param sliceLength
     *            the length of the slice.
     */
    MappedFileReader(final RandomAccessByteBufferReader mappingReader, final PathSlice topLevelPathSlice,
            final long sliceStartPos, final long sliceLength) {
        this.mappingReader = mappingReader;
        this.topLevelPathSlice = topLevelPathSlice;
        this.sliceStartPos = sliceStartPos;
        this.sliceLength = sliceLength;
    }

    @Override
    public ByteOrder byteOrder() {
        return mappingReader.byteOrder();
    }

    @Override
    public long length() {
        return sliceLength;
    }

    @Override
    public int read(final long srcOffset, final ByteBuffer dstBuf, final int dstBufStart, final int numBytes)
            throws IOException {
        if (numBytes < PathSlice.MIN_CHANNEL_READ_SIZE) {
            return mappingReader.read(srcOffset, dstBuf, dstBufStart, numBytes);
        }
        return topLevelPathSlice.readThroughChannel(sliceStartPos, sliceLength, srcOffset, dstBuf, dstBufStart,
                numBytes);
    }

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        if (numBytes < PathSlice.MIN_CHANNEL_READ_SIZE) {
            return mappingReader.read(srcOffset, dstArr, dstArrStart, numBytes);
        }
        // The buffer's limit is the length of the array, so this checks the bounds the same way as a read into the
        // array would be checked
        return topLevelPathSlice.readThroughChannel(sliceStartPos, sliceLength, srcOffset, ByteBuffer.wrap(dstArr),
                dstArrStart, numBytes);
    }

    @Override
    public byte readByte(final long offset) throws IOException {
        return mappingReader.readByte(offset);
    }

    @Override
    public int readUnsignedByte(final long offset) throws IOException {
        return mappingReader.readUnsignedByte(offset);
    }

    @Override
    public short readShort(final long offset) throws IOException {
        return mappingReader.readShort(offset);
    }

    @Override
    public int readUnsignedShort(final long offset) throws IOException {
        return mappingReader.readUnsignedShort(offset);
    }

    @Override
    public int readInt(final long offset) throws IOException {
        return mappingReader.readInt(offset);
    }

    @Override
    public long readUnsignedInt(final long offset) throws IOException {
        return mappingReader.readUnsignedInt(offset);
    }

    @Override
    public long readLong(final long offset) throws IOException {
        return mappingReader.readLong(offset);
    }

    @Override
    public String readStringModifiedUtf8(final long offset, final int numBytes) throws IOException {
        return mappingReader.readStringModifiedUtf8(offset, numBytes);
    }

    @Override
    public String readString(final long offset, final int numBytes, final Charset charset) throws IOException {
        return mappingReader.readString(offset, numBytes, charset);
    }
}
