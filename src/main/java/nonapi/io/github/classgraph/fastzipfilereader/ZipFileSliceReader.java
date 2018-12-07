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
 * Copyright (c) 2018 Luke Hutchison
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
package nonapi.io.github.classgraph.fastzipfilereader;

import java.io.EOFException;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

class ZipFileSliceReader implements AutoCloseable {
    private final ZipFileSlice zipFileSlice;
    private final ByteBuffer[] chunkCache;
    private final byte[] buf = new byte[8];

    public ZipFileSliceReader(final ZipFileSlice zipFileSlice) {
        this.zipFileSlice = zipFileSlice;
        this.chunkCache = new ByteBuffer[zipFileSlice.physicalZipFile.numMappedByteBuffers];
    }

    private ByteBuffer getChunk(final int chunkIdx) throws IOException {
        ByteBuffer chunk = chunkCache[chunkIdx];
        if (chunk == null) {
            final ByteBuffer byteBufferDup = zipFileSlice.physicalZipFile.getByteBuffer(chunkIdx).duplicate();
            chunk = chunkCache[chunkIdx] = byteBufferDup;
        }
        return chunk;
    }

    /**
     * Copy from an offset within the file into a byte[] array (possibly spanning the boundary between two 2GB
     * chunks).
     */
    int read(final long off, final byte[] buf, final int bufStart, final int numBytesToRead) throws IOException {
        if (off < 0 || bufStart < 0 || bufStart + numBytesToRead > buf.length) {
            throw new IndexOutOfBoundsException();
        }
        int currBufStart = bufStart;
        int remainingBytesToRead = numBytesToRead;
        int totBytesRead = 0;
        for (long currOff = off; remainingBytesToRead > 0;) {
            // Find the ByteBuffer chunk to read from
            final long currOffAbsolute = zipFileSlice.startOffsetWithinPhysicalZipFile + currOff;
            final int chunkIdx = (int) (currOffAbsolute >> 32);
            final ByteBuffer chunk = getChunk(chunkIdx);
            final long chunkStartAbsolute = ((long) chunkIdx) << 32;
            final int startReadPos = (int) (currOffAbsolute - chunkStartAbsolute);

            // Read from current chunk.
            // N.B. the cast to Buffer is necessary, see:
            // https://github.com/plasma-umass/doppio/issues/497#issuecomment-334740243
            // https://github.com/classgraph/classgraph/issues/284#issuecomment-443612800
            // Otherwise compiling in JDK<9 compatibility mode using JDK9+ causes runtime breakage. 
            ((Buffer) chunk).mark();
            ((Buffer) chunk).position(startReadPos);
            final int numBytesRead = Math.min(chunk.remaining(), remainingBytesToRead);
            try {
                chunk.get(buf, currBufStart, numBytesRead);
            } catch (final BufferUnderflowException e) {
                // Should not happen
                throw new EOFException("Unexpected EOF");
            }
            ((Buffer) chunk).reset();

            currOff += numBytesRead;
            currBufStart += numBytesRead;
            totBytesRead += numBytesRead;
            remainingBytesToRead -= numBytesRead;
        }
        return totBytesRead == 0 && numBytesToRead > 0 ? -1 : totBytesRead;
    }

    int getShort(final long off) throws IOException {
        if (off < 0 || off > zipFileSlice.len - 2) {
            throw new IndexOutOfBoundsException();
        }
        if (read(off, buf, 0, 2) < 2) {
            throw new EOFException("Unexpected EOF");
        }
        return ((buf[1] & 0xff) << 8) | (buf[0] & 0xff);
    }

    int getInt(final long off) throws IOException {
        if (off < 0 || off > zipFileSlice.len - 2) {
            throw new IndexOutOfBoundsException();
        }
        if (read(off, buf, 0, 4) < 4) {
            throw new EOFException("Unexpected EOF");
        }
        return ((buf[3] & 0xff) << 24) | ((buf[2] & 0xff) << 16) | ((buf[1] & 0xff) << 8) | (buf[0] & 0xff);
    }

    long getLong(final long off) throws IOException {
        if (off < 0 || off > zipFileSlice.len - 2) {
            throw new IndexOutOfBoundsException();
        }
        if (read(off, buf, 0, 8) < 8) {
            throw new EOFException("Unexpected EOF");
        }
        return (((long) (((buf[7] & 0xff) << 24) | ((buf[6] & 0xff) << 16) | ((buf[5] & 0xff) << 8)
                | (buf[4] & 0xff))) << 32) | ((buf[3] & 0xff) << 24) | ((buf[2] & 0xff) << 16)
                | ((buf[1] & 0xff) << 8) | (buf[0] & 0xff);
    }

    String getString(final long off, final int numBytes) throws IOException {
        if (off < 0 || off > zipFileSlice.len - numBytes) {
            throw new IndexOutOfBoundsException();
        }
        final byte[] strBytes = new byte[numBytes];
        if (read(off, strBytes, 0, numBytes) < numBytes) {
            throw new EOFException("Unexpected EOF");
        }
        // Assume the entry names are encoded in UTF-8 (should be the case for all jars; the only other
        // valid zipfile charset is CP437, which is the same as ASCII for printable high-bit-clear chars)
        return new String(strBytes, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        Arrays.fill(chunkCache, null);
    }
}