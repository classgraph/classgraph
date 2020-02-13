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

import java.io.File;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import nonapi.io.github.classgraph.utils.StringUtils;

/**
 * {@link RandomAccessReader} for a {@link File}. Reads in <b>little endian</b> order, as required by the zipfile
 * format.
 */
public class RandomAccessFileReader implements RandomAccessReader {
    private final FileChannel fileChannel;
    private final long sliceStartPos;
    private final long sliceLength;
    private ByteBuffer reusableByteBuffer;
    private final byte[] scratchArr = new byte[8];
    private final ByteBuffer scratchByteBuf = ByteBuffer.wrap(scratchArr);
    private byte[] utf8Bytes;

    /** Constructor. */
    public RandomAccessFileReader(final FileChannel fileChannel, final long sliceStartPos, final long sliceLength) {
        this.fileChannel = fileChannel;
        this.sliceStartPos = sliceStartPos;
        this.sliceLength = sliceLength;
    }

    @Override
    public int read(final long srcOffset, final ByteBuffer dstBuf, final int dstBufStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        try {
            if (srcOffset < 0L || numBytes < 0 || numBytes > sliceLength) {
                throw new IOException("Read index out of bounds");
            }
            final long srcStart = sliceStartPos + srcOffset;
            dstBuf.position(dstBufStart);
            dstBuf.limit(dstBufStart + numBytes);
            return fileChannel.read(dstBuf, srcStart);

        } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
            throw new IOException("Read index out of bounds");
        }
    }

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        try {
            if (reusableByteBuffer == null || reusableByteBuffer.array() != dstArr) {
                // If reusableByteBuffer is not set, or wraps a different array from a previous operation,
                // wrap dstArr with a new ByteBuffer
                reusableByteBuffer = ByteBuffer.wrap(dstArr);
            }
            // Read into reusableByteBuffer, which is backed with dstArr
            return read(srcOffset, reusableByteBuffer, dstArrStart, numBytes);

        } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
            throw new IOException("Read index out of bounds");
        }
    }

    @Override
    public byte readByte(final long offset) throws IOException {
        if (read(offset, scratchByteBuf, 0, 1) < 1) {
            throw new IOException("Premature EOF");
        }
        return scratchArr[0];
    }

    @Override
    public int readUnsignedByte(final long offset) throws IOException {
        if (read(offset, scratchByteBuf, 0, 1) < 1) {
            throw new IOException("Premature EOF");
        }
        return scratchArr[0] & 0xff;
    }

    @Override
    public short readShort(final long offset) throws IOException {
        return (short) readUnsignedShort(offset);
    }

    @Override
    public int readUnsignedShort(final long offset) throws IOException {
        if (read(offset, scratchByteBuf, 0, 2) < 2) {
            throw new IOException("Premature EOF");
        }
        return ((scratchArr[1] & 0xff) << 8) //
                | (scratchArr[0] & 0xff);
    }

    @Override
    public int readInt(final long offset) throws IOException {
        if (read(offset, scratchByteBuf, 0, 4) < 4) {
            throw new IOException("Premature EOF");
        }
        return ((scratchArr[3] & 0xff) << 24) //
                | ((scratchArr[2] & 0xff) << 16) //
                | ((scratchArr[1] & 0xff) << 8) //
                | (scratchArr[0] & 0xff);
    }

    @Override
    public long readUnsignedInt(final long offset) throws IOException {
        return readInt(offset) & 0xffffffffL;
    }

    @Override
    public long readLong(final long offset) throws IOException {
        if (read(offset, scratchByteBuf, 0, 8) < 8) {
            throw new IOException("Premature EOF");
        }
        return ((scratchArr[7] & 0xffL) << 56) //
                | ((scratchArr[6] & 0xffL) << 48) //
                | ((scratchArr[5] & 0xffL) << 40) //
                | ((scratchArr[4] & 0xffL) << 32) //
                | ((scratchArr[3] & 0xffL) << 24) //
                | ((scratchArr[2] & 0xffL) << 16) //
                | ((scratchArr[1] & 0xffL) << 8) //
                | (scratchArr[0] & 0xffL);
    }

    @Override
    public String readString(final long offset, final int numBytes, final boolean replaceSlashWithDot,
            final boolean stripLSemicolon) throws IOException {
        // Reuse UTF8 buffer array if it's non-null from a previous call, and if it's big enough
        if (utf8Bytes == null || utf8Bytes.length < numBytes) {
            utf8Bytes = new byte[numBytes];
        }
        if (read(offset, utf8Bytes, 0, numBytes) < numBytes) {
            throw new IOException("Premature EOF");
        }
        return StringUtils.readString(utf8Bytes, 0, numBytes, replaceSlashWithDot, stripLSemicolon);
    }

    @Override
    public String readString(final long offset, final int numBytes) throws IOException {
        return readString(offset, numBytes, false, false);
    }
}
