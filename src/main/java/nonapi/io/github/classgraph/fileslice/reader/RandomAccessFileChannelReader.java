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
package nonapi.io.github.classgraph.fileslice.reader;

import java.io.File;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;

import nonapi.io.github.classgraph.fileslice.FileSlice;
import nonapi.io.github.classgraph.utils.StringUtils;

/**
 * {@link RandomAccessReader} for a {@link File}. Reads in <b>little endian</b> order, as required by the zipfile
 * format.
 */
public class RandomAccessFileChannelReader implements RandomAccessReader {
    /** Supplier of the {@link FileChannel} to read from. */
    public interface FileChannelSource {
        /**
         * Get the channel to read.
         *
         * @return the channel to read.
         * @throws IOException
         *             if the channel is closed and cannot be reopened.
         */
        FileChannel fileChannel() throws IOException;
    }

    /** Where the file channel comes from. */
    private final FileChannelSource fileChannelSource;

    /** The slice start pos. */
    private final long sliceStartPos;

    /** The slice length. */
    private final long sliceLength;

    /** The reusable byte buffer. */
    private ByteBuffer reusableByteBuffer;

    /** The scratch arr. */
    private final byte[] scratchArr = new byte[8];

    /** The scratch byte buf. */
    private final ByteBuffer scratchByteBuf = ByteBuffer.wrap(scratchArr);

    /** The utf 8 bytes. */
    private byte[] utf8Bytes;

    /**
     * Constructor.
     *
     * @param fileChannel
     *            the file channel
     * @param sliceStartPos
     *            the slice start pos
     * @param sliceLength
     *            the slice length
     */
    public RandomAccessFileChannelReader(final FileChannel fileChannel, final long sliceStartPos,
            final long sliceLength) {
        this(new FileChannelSource() {
            @Override
            public FileChannel fileChannel() {
                return fileChannel;
            }
        }, sliceStartPos, sliceLength);
    }

    /**
     * Constructor for slicing a file channel obtained from a {@link FileChannelSource}.
     *
     * @param fileChannelSource
     *            the source of the channel to read
     * @param sliceStartPos
     *            the slice start pos
     * @param sliceLength
     *            the slice length
     */
    public RandomAccessFileChannelReader(final FileChannelSource fileChannelSource, final long sliceStartPos,
            final long sliceLength) {
        this.fileChannelSource = fileChannelSource;
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
            if (srcOffset < 0L || numBytes < 0 || numBytes > sliceLength - srcOffset) {
                throw new IOException("Read index out of bounds");
            }
            // Read no more than the destination has room for, as the array-backed and ByteBuffer-backed readers
            // also do, rather than letting ByteBuffer#limit throw IllegalArgumentException
            final int numBytesToRead = Math.max(Math.min(numBytes, dstBuf.capacity() - dstBufStart), 0);
            if (numBytesToRead == 0) {
                return -1;
            }
            final long srcStart = sliceStartPos + srcOffset;
            final Buffer db = FileSlice.toBuffer(dstBuf);
            // Open the limit up to the capacity before positioning, since the destination buffer may still carry
            // the limit that a previous read left on it, and positioning past a stale limit throws
            // IllegalArgumentException
            db.limit(dstBuf.capacity());
            db.position(dstBufStart);
            db.limit(dstBufStart + numBytesToRead);
            FileChannel fileChannel = fileChannelSource.fileChannel();
            // FileChannel#read is not required to transfer the whole of the requested range in a single call,
            // and a read from a network filesystem can be short, so keep reading until the requested number of
            // bytes has been read or the end of the file is reached. (Every caller treats a short read as a
            // truncated file, so a short read that is not at the end of the file has to be completed here.)
            int numBytesRead = 0;
            while (numBytesRead < numBytesToRead) {
                final int numBytesReadThisCall;
                try {
                    numBytesReadThisCall = fileChannel.read(dstBuf, srcStart + numBytesRead);
                } catch (final ClosedByInterruptException e) {
                    // This thread was interrupted, which is a reason to stop reading
                    throw e;
                } catch (final ClosedChannelException e) {
                    // Another thread closed the channel. Carry on from where the read stopped if the source has
                    // another channel to read, which it does not if the file has been closed, or if the channel
                    // was given to this reader rather than got from a source that can reopen it
                    final FileChannel nextFileChannel = fileChannelSource.fileChannel();
                    if (nextFileChannel == fileChannel) {
                        throw e;
                    }
                    fileChannel = nextFileChannel;
                    continue;
                }
                if (numBytesReadThisCall <= 0) {
                    // -1 => end of file, which here means the file is shorter than its slice. A FileChannel is
                    // always in blocking mode, and the window above always leaves room while this loop runs, so
                    // ReadableByteChannel#read guarantees that at least one byte is read, and a FileChannel that
                    // keeps to its contract never returns 0 here
                    break;
                }
                numBytesRead += numBytesReadThisCall;
            }
            return numBytesRead == 0 ? -1 : numBytesRead;

        } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
            throw new IOException("Read index out of bounds", e);
        }
    }

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        try {
            if (srcOffset < 0L || numBytes < 0 || numBytes > sliceLength - srcOffset) {
                throw new IOException("Read index out of bounds");
            }
            if (reusableByteBuffer == null || reusableByteBuffer.array() != dstArr) {
                // If reusableByteBuffer is not set, or wraps a different array from a previous operation,
                // wrap dstArr with a new ByteBuffer
                reusableByteBuffer = ByteBuffer.wrap(dstArr);
            }
            // Read into reusableByteBuffer, which is backed with dstArr
            return read(srcOffset, reusableByteBuffer, dstArrStart, numBytes);

        } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
            throw new IOException("Read index out of bounds", e);
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
