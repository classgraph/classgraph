
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
package io.github.classgraph.vfs.internal.slice.reader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.Charset;
import java.util.Arrays;

import io.github.classgraph.base.internal.utils.StringUtils;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.internal.slice.Slice;
import org.jspecify.annotations.Nullable;

/**
 * A reader that works as either a {@link RandomAccessReader} or a {@link SequentialReader}. The content is read as
 * a stream, and is buffered up to the point it has been read so far, so that the random access methods can go back
 * over any part of it that has already been read. (Parsing a classfile needs exactly this: the constant pool is
 * read sequentially, then indexed into.) Reads in <b>big endian</b> order.
 */
public class RandomAccessOrSequentialReader implements RandomAccessReader, SequentialReader, AutoCloseable {
    /**
     * The stream the content is read through: either the stream that the {@link VfsEntry} opened, which inflates
     * the entry if it is deflated, or a stream that the caller opened and passed in. Null once this reader has been
     * closed.
     */
    private @Nullable InputStream inputStream;

    /**
     * True if this reader opened {@link #inputStream} itself, and so has to close it. A stream that the caller
     * passed in belongs to the caller, which closes it in its own try-with-resources.
     */
    private final boolean ownsInputStream;

    /** Buffer. */
    private byte[] arr;

    /** The number of bytes used in arr. */
    private int arrUsed;

    /** The current read index within the stream. */
    private int currIdx;

    /** The length of the content if known, or -1 if unknown (e.g. because the entry it comes from is deflated). */
    private int lengthHint = -1;

    /**
     * Initial buffer size. For most content only a prefix is read -- for a classfile, the first 16-64kb, since the
     * bytecodes are not read.
     */
    private static final int INITIAL_BUF_SIZE = 16384;

    /**
     * Read this many bytes each time there is a buffer underrun. This is smaller than 8k by 8 bytes to prevent the
     * doubling of the array size when the last chunk doesn't quite fit within the 16kb of INITIAL_BUF_SIZE, since
     * the number of bytes that can be requested is up to 8 (for longs). Otherwise we could request to read to (8kb
     * * 2 + 8), which would double the size of the buffer to 32kb, but if we only need to read between 8kb and
     * 16kb, then we unnecessarily copied the buffer content one extra time.
     */
    private static final int BUF_CHUNK_SIZE = 8192 - 8;

    /** The message of the {@link IOException} thrown when a read runs past the end of the content. */
    private static final String END_OF_CONTENT = "Tried to read past the end of the content";

    /**
     * Constructor for reading an entry of a virtual filesystem. Whatever the entry has to open in order to be read
     * is opened by this reader, and closed by {@link #close()}, so the entry itself does not need to be opened by
     * the caller.
     *
     * @param entry
     *            the {@link VfsEntry} to read.
     * @throws IOException
     *             If the entry could not be opened.
     */
    public RandomAccessOrSequentialReader(final VfsEntry entry) throws IOException {
        // The entry opens whatever it has to in order to be read, and this reader closes it
        ownsInputStream = true;
        inputStream = entry.open();
        arr = new byte[INITIAL_BUF_SIZE];
        // Telling the reader how long the entry is saves it from growing the buffer to find out
        final var length = entry.getLength();
        lengthHint = length < 0L ? -1 : (int) Math.min(length, Slice.MAX_BUFFER_SIZE);
    }

    /**
     * Constructor for reading content that is already open as a stream. The stream belongs to the caller, which
     * opens it in a try-with-resources and closes it once the reader has been closed.
     *
     * @param inputStream
     *            the {@link InputStream} to read from.
     */
    public RandomAccessOrSequentialReader(final InputStream inputStream) {
        ownsInputStream = false;
        this.inputStream = inputStream;
        arr = new byte[INITIAL_BUF_SIZE];
    }

    /**
     * Curr pos.
     *
     * @return the current read position.
     */
    public int currPos() {
        return currIdx;
    }

    /**
     * Called when there is a buffer underrun to ensure there are sufficient bytes available in the array to read
     * the given number of bytes at the given start index.
     *
     * @param targetArrUsed
     *            the target value for {@link #arrUsed} (i.e. the number of bytes that must be filled in the array).
     *            Taken as a long so that a caller can hand over a sum of two ints without having to check first
     *            whether it fits in an int -- one that does not is out of range, and is rejected below.
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void readTo(final long targetArrUsed) throws IOException {
        // Array does not need to grow larger than the length hint (if the uncompressed size of the zip entry is an
        // underestimate, the content will be truncated). If -1, assume 2GB is the max size.
        final var maxArrLen = lengthHint == -1 ? Slice.MAX_BUFFER_SIZE : lengthHint;
        final var inputStream = this.inputStream;
        if (inputStream == null) {
            // The stream is only cleared by close(), so the buffer cannot be filled any further than it already is
            throw new IOException("Tried to read past the buffered part of a closed reader");
        }
        if (targetArrUsed > Slice.MAX_BUFFER_SIZE || targetArrUsed < 0) {
            throw new IOException("Hit 2GB limit while trying to grow buffer array");
        }
        if (arrUsed == maxArrLen) {
            // The buffer already holds the whole of the content, so there is nothing left to read. (This is the
            // 2GB limit only when the length of the content is unknown; when it is known, reporting it as such
            // would send the reader of the message looking for a file thousands of times larger than the one that
            // was actually read past the end of.)
            throw new IOException(END_OF_CONTENT);
        }

        // Need to read at least BUF_CHUNK_SIZE (but don't overshoot past 2GB limit). The chunk end is computed in
        // long arithmetic, because arrUsed can be within BUF_CHUNK_SIZE of the 2GB limit, and an int sum would wrap
        // negative there rather than being clamped by the Math.min below.
        final var maxNewArrUsed = (int) Math.min(Math.max(targetArrUsed, (long) arrUsed + (long) BUF_CHUNK_SIZE),
                maxArrLen);

        // Double the size of the array if it's too small to contain the new chunk of bytes
        long newArrLength = arr.length;
        while (newArrLength < maxNewArrUsed) {
            newArrLength = Math.min(maxNewArrUsed, newArrLength * 2L);
        }
        if (newArrLength > Slice.MAX_BUFFER_SIZE) {
            throw new IOException("Hit 2GB limit while trying to grow buffer array");
        }
        arr = Arrays.copyOf(arr, (int) Math.min(newArrLength, maxArrLen));

        // Read a new chunk into the buffer, starting at position arrUsed. InputStream#read is not required to
        // transfer the whole of the requested range in a single call, and the channel-backed streams that a module
        // or a directory is read through really can transfer less, so keep reading until the target has been
        // reached or the stream is exhausted. (Each call may still transfer more than the target, filling the rest
        // of the buffer.)
        while (arrUsed < targetArrUsed) {
            final var numRead = inputStream.read(arr, arrUsed, arr.length - arrUsed);
            if (numRead <= 0) {
                // -1 => end of stream; 0 => the buffer has no space left
                break;
            }
            arrUsed += numRead;
        }

        // Check the buffer was able to be filled to the requested position. The stream can only stop short of the
        // target if the content ran out, either at the end of the stream or at the length hint.
        if (arrUsed < targetArrUsed) {
            throw new IOException(END_OF_CONTENT);
        }
    }

    /**
     * Ensure that the given number of bytes have been read into the buffer, starting at the given offset, so that
     * the read that follows can be served straight out of the buffer.
     *
     * @param srcOffset
     *            the offset the read starts at.
     * @param numBytes
     *            the number of bytes to be read.
     * @return {@code srcOffset} as an index into the buffer.
     * @throws IOException
     *             on EOF, or if the range is out of bounds, or if the bytes could not be read.
     */
    private int bufferFor(final long srcOffset, final int numBytes) throws IOException {
        // The offset is range-checked before it is narrowed to an int, since narrowing it silently would turn a
        // read from outside the content into a read from within it
        if (srcOffset < 0L || srcOffset > Slice.MAX_BUFFER_SIZE) {
            throw new IOException("Read offset out of range: " + srcOffset);
        }
        final var idx = (int) srcOffset;
        // The end of the range is compared by subtraction rather than by adding numBytes to idx, because an offset
        // and a length read out of corrupt content can sum to more than an int holds, and a wrapped sum would make
        // a read from past the end of the content look like one that is already buffered
        if (numBytes > arrUsed - idx) {
            readTo((long) idx + numBytes);
        }
        return idx;
    }

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        final var idx = bufferFor(srcOffset, numBytes);
        final var numBytesToRead = Math.max(Math.min(numBytes, dstArr.length - dstArrStart), 0);
        if (numBytesToRead == 0) {
            return -1;
        }
        try {
            System.arraycopy(arr, idx, dstArr, dstArrStart, numBytesToRead);
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            throw new IOException("Read index out of bounds");
        }
    }

    @Override
    public int read(final long srcOffset, final ByteBuffer dstBuf, final int dstBufStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        final var idx = bufferFor(srcOffset, numBytes);
        final var numBytesToRead = Math.max(Math.min(numBytes, dstBuf.capacity() - dstBufStart), 0);
        if (numBytesToRead == 0) {
            return -1;
        }
        try {
            // Open the limit up to the capacity before positioning, since the destination buffer may still carry
            // the limit that a previous read left on it, and positioning past a stale limit throws
            // IllegalArgumentException
            dstBuf.limit(dstBuf.capacity());
            dstBuf.position(dstBufStart);
            dstBuf.limit(dstBufStart + numBytesToRead);
            dstBuf.put(arr, idx, numBytesToRead);
            return numBytesToRead;
        } catch (BufferUnderflowException | IndexOutOfBoundsException | ReadOnlyBufferException e) {
            throw new IOException("Read index out of bounds");
        }
    }

    @Override
    public byte readByte(final long offset) throws IOException {
        final var idx = bufferFor(offset, 1);
        return arr[idx];
    }

    @Override
    public int readUnsignedByte(final long offset) throws IOException {
        final var idx = bufferFor(offset, 1);
        return arr[idx] & 0xff;
    }

    @Override
    public short readShort(final long offset) throws IOException {
        return (short) readUnsignedShort(offset);
    }

    @Override
    public int readUnsignedShort(final long offset) throws IOException {
        final var idx = bufferFor(offset, 2);
        return ((arr[idx] & 0xff) << 8) //
                | (arr[idx + 1] & 0xff);
    }

    @Override
    public int readInt(final long offset) throws IOException {
        final var idx = bufferFor(offset, 4);
        return ((arr[idx] & 0xff) << 24) //
                | ((arr[idx + 1] & 0xff) << 16) //
                | ((arr[idx + 2] & 0xff) << 8) //
                | (arr[idx + 3] & 0xff);
    }

    @Override
    public long readUnsignedInt(final long offset) throws IOException {
        return readInt(offset) & 0xffffffffL;
    }

    @Override
    public long readLong(final long offset) throws IOException {
        final var idx = bufferFor(offset, 8);
        return ((arr[idx] & 0xffL) << 56) //
                | ((arr[idx + 1] & 0xffL) << 48) //
                | ((arr[idx + 2] & 0xffL) << 40) //
                | ((arr[idx + 3] & 0xffL) << 32) //
                | ((arr[idx + 4] & 0xffL) << 24) //
                | ((arr[idx + 5] & 0xffL) << 16) //
                | ((arr[idx + 6] & 0xffL) << 8) //
                | (arr[idx + 7] & 0xffL);
    }

    @Override
    public byte readByte() throws IOException {
        final var val = readByte(currIdx);
        currIdx++;
        return val;
    }

    @Override
    public int readUnsignedByte() throws IOException {
        final var val = readUnsignedByte(currIdx);
        currIdx++;
        return val;
    }

    @Override
    public short readShort() throws IOException {
        final var val = readShort(currIdx);
        currIdx += 2;
        return val;
    }

    @Override
    public int readUnsignedShort() throws IOException {
        final var val = readUnsignedShort(currIdx);
        currIdx += 2;
        return val;
    }

    @Override
    public int readInt() throws IOException {
        final var val = readInt(currIdx);
        currIdx += 4;
        return val;
    }

    @Override
    public long readUnsignedInt() throws IOException {
        final var val = readUnsignedInt(currIdx);
        currIdx += 4;
        return val;
    }

    @Override
    public long readLong() throws IOException {
        final var val = readLong(currIdx);
        currIdx += 8;
        return val;
    }

    @Override
    public void skip(final int bytesToSkip) throws IOException {
        if (bytesToSkip < 0) {
            // The number of bytes to skip is usually the length of a part of the content that is not of interest,
            // read from the content itself, so a negative value means corrupt content rather than a caller error:
            // a classfile attribute length is an unsigned 32-bit value, and one larger than 2GB reads back as a
            // negative int
            throw new IOException("Tried to skip a negative number of bytes");
        }
        // The target position is computed in long arithmetic, because a length close to 2GB read from corrupt
        // content would otherwise wrap the position negative, and the read after it would be made outside the
        // buffer rather than being rejected here
        final var targetIdx = currIdx + (long) bytesToSkip;
        if (targetIdx > arrUsed) {
            if (targetIdx > Slice.MAX_BUFFER_SIZE) {
                throw new IOException("Tried to skip past the 2GB limit");
            }
            readTo(targetIdx);
        }
        currIdx = (int) targetIdx;
    }

    @Override
    public String readStringModifiedUtf8(final long offset, final int numBytes) throws IOException {
        final var idx = bufferFor(offset, numBytes);
        return StringUtils.readStringModifiedUtf8(arr, idx, numBytes);
    }

    @Override
    public String readStringModifiedUtf8(final int numBytes) throws IOException {
        // Delegate to the random access overload, as the other sequential read methods do, so that the buffer is
        // grown to cover the requested bytes first. Reading straight out of arr would silently return whatever
        // happened to be in the buffer past arrUsed.
        final var val = readStringModifiedUtf8(currIdx, numBytes);
        currIdx += numBytes;
        return val;
    }

    @Override
    public String readString(final long offset, final int numBytes, final Charset charset) throws IOException {
        final var idx = bufferFor(offset, numBytes);
        return new String(arr, idx, numBytes, charset);
    }

    @Override
    public String readString(final int numBytes, final Charset charset) throws IOException {
        // Delegate to the random access overload, for the same reason as the modified UTF8 overload above
        final var val = readString(currIdx, numBytes, charset);
        currIdx += numBytes;
        return val;
    }

    /**
     * Compare the bytes at the given offset with the given ASCII string, without building a {@link String} out of
     * them to compare it with. Each byte is compared as an unsigned value, so a byte outside the ASCII range
     * differs from every character of an ASCII string.
     *
     * @param srcOffset
     *            the offset the bytes start at.
     * @param numBytes
     *            the number of bytes to compare.
     * @param asciiStr
     *            the ASCII string to compare the bytes with.
     * @return true if the bytes at the offset are the given string.
     * @throws IOException
     *             on EOF, or if the range is out of bounds, or if the bytes could not be read.
     */
    public boolean contentEqualsAscii(final long srcOffset, final int numBytes, final String asciiStr)
            throws IOException {
        if (numBytes != asciiStr.length()) {
            // Nothing has to be read to know that a different number of bytes cannot be the string. This also means
            // that a string holding any character that is not one byte long, whatever the encoding, is never equal
            // to an ASCII string of the same length.
            return false;
        }
        final var idx = bufferFor(srcOffset, numBytes);
        for (var i = 0; i < numBytes; i++) {
            if ((char) (arr[idx + i] & 0xff) != asciiStr.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        // Only the stream that this reader opened on a VfsEntry is closed here. A stream that the caller opened
        // belongs to the caller, which closes it in its own try-with-resources.
        try {
            final var inputStream = this.inputStream;
            if (ownsInputStream && inputStream != null) {
                inputStream.close();
            }
        } catch (final IOException e) {
            // Ignore
        } finally {
            this.inputStream = null;
        }
    }
}
