
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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.utils.StringUtils;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.internal.InputStreamUtils;
import io.github.classgraph.vfs.internal.slice.Slice;

/**
 * A reader that implements both {@link RandomAccessReader} and {@link SequentialReader} over an {@link InputStream}
 * or {@link VfsEntry}, buffering stream bytes on demand to support random access. Defaults to
 * {@link ByteOrder#BIG_ENDIAN}.
 *
 * <p>
 * Calling {@link #length()} reads and buffers the entire stream to determine its true length. Because content is
 * buffered in a {@code byte[]}, the maximum bufferable size is {@code Integer.MAX_VALUE - 8} bytes (or a smaller
 * {@code maxLength} passed to the constructor); reads or skips beyond that limit throw {@link IOException}. For
 * content larger than 2 GiB, stream it directly via {@link VfsEntry#open()}.
 */
public final class RandomAccessOrSequentialReader implements RandomAccessReader, SequentialReader, AutoCloseable {
    /** The underlying input stream, or null once closed. */
    private @Nullable InputStream inputStream;

    /** True if this reader opened {@link #inputStream} and should close it on {@link #close()}. */
    private final boolean ownsInputStream;

    /** Buffer holding the first {@link #arrUsed} bytes read from the stream. */
    private byte[] arr;

    /** The number of valid bytes currently in {@link #arr}. */
    private int arrUsed;

    /** The current sequential read position. */
    private int currIdx;

    /** Validated declared content length hint used to size the buffer, or -1 if unknown. */
    private int lengthHint = -1;

    /** True once the end of the stream has been reached. */
    private boolean eof;

    /** Next chunk size for buffer growth (doubles up to {@link #MAX_CHUNK_SIZE}). */
    private int chunkSize = MIN_CHUNK_SIZE;

    /** Maximum buffer size in bytes. */
    private int maxBufferSize = Slice.MAX_BUFFER_SIZE;

    /** The byte order for multi-byte values. */
    private final ByteOrder byteOrder;

    /** True if {@link #byteOrder} is {@link ByteOrder#BIG_ENDIAN}. */
    private final boolean bigEndian;

    /** Initial buffer allocation size on first read. */
    private static final int INITIAL_BUF_SIZE = 16384;

    /** Shared empty buffer before the first read. */
    private static final byte[] EMPTY_BUF = {};

    /** Minimum chunk size to read on the first buffer underrun. */
    private static final int MIN_CHUNK_SIZE = 8192 - 8;

    /** Maximum chunk size step before buffer doubling takes over. */
    private static final int MAX_CHUNK_SIZE = 65536;

    /** Maximum buffer size allocated in a single step from a declared content length. */
    private static final int MAX_DECLARED_LENGTH_TO_ALLOCATE = 16 * 1024 * 1024;

    /** Buffer size threshold at which the next growth jumps directly to {@link #lengthHint}. */
    private static final int JUMP_TO_DECLARED_LENGTH_THRESHOLD = INITIAL_BUF_SIZE;

    /** Maximum plausible ratio of declared uncompressed size to compressed size. */
    private static final int MAX_BELIEVED_COMPRESSION_RATIO = 2048;

    /** Maximum slack in bytes by which a declared uncompressed size may fall below the compressed size. */
    private static final int UNDERSIZE_SLACK = 1024;

    /** Exception message when a read extends past the end of the content. */
    private static final String END_OF_CONTENT = "Tried to read past the end of the content";

    /**
     * Construct a big-endian reader over a {@link VfsEntry}. Closing this reader closes the stream opened on the
     * entry.
     *
     * @param entry
     *            the {@link VfsEntry} to read.
     * @throws IOException
     *             if the entry could not be opened.
     */
    public RandomAccessOrSequentialReader(final VfsEntry entry) throws IOException {
        this(entry, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Construct a reader over a {@link VfsEntry} with the specified byte order. Closing this reader closes the
     * stream opened on the entry.
     *
     * @param entry
     *            the {@link VfsEntry} to read.
     * @param byteOrder
     *            the byte order for multi-byte values.
     * @throws IOException
     *             if the entry could not be opened.
     */
    public RandomAccessOrSequentialReader(final VfsEntry entry, final ByteOrder byteOrder) throws IOException {
        this(entry, byteOrder, Slice.MAX_BUFFER_SIZE);
    }

    /**
     * Construct a reader over a {@link VfsEntry} with the specified byte order and maximum buffered length. Closing
     * this reader closes the stream opened on the entry.
     *
     * @param entry
     *            the {@link VfsEntry} to read.
     * @param byteOrder
     *            the byte order for multi-byte values.
     * @param maxLength
     *            the maximum number of bytes to buffer before throwing {@link IOException} (clamped to
     *            {@code Integer.MAX_VALUE - 8}).
     * @throws IOException
     *             if the entry could not be opened.
     * @throws IllegalArgumentException
     *             if {@code maxLength} is not positive.
     */
    public RandomAccessOrSequentialReader(final VfsEntry entry, final ByteOrder byteOrder, final int maxLength)
            throws IOException {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be positive, but was " + maxLength);
        }
        maxBufferSize = Math.min(maxLength, Slice.MAX_BUFFER_SIZE);
        // The entry opens whatever it has to in order to be read, and this reader closes it
        ownsInputStream = true;
        inputStream = entry.open();
        arr = EMPTY_BUF;
        this.byteOrder = byteOrder;
        this.bigEndian = byteOrder == ByteOrder.BIG_ENDIAN;
        // Knowing how long the entry says it is saves the buffer from being grown a chunk at a time to find out.
        // It is only a hint: see the class documentation for why the declared length is not trusted as the end of
        // the content. Nor is it believed past the limit on the buffer: the content may be shorter than it
        // declares, so a declared length past the limit does not mean that the content runs on past it.
        lengthHint = Math.min(validatedLengthHint(entry.getLength(), entry.getCompressedSize()), maxBufferSize);
    }

    /**
     * Validate a declared uncompressed length against the compressed size to determine a buffer sizing hint.
     *
     * @param declaredLength
     *            the declared uncompressed length, or -1 if unknown.
     * @param compressedSize
     *            the compressed size in bytes, or -1 if unknown.
     * @return the validated length hint, or -1 if the declared length is unknown or implausible.
     */
    // Package-private rather than private so that the rules can be checked one at a time by a test
    static int validatedLengthHint(final long declaredLength, final long compressedSize) {
        if (declaredLength <= 0L) {
            // Either no length was declared, or the content is empty, in which case there is nothing to size a
            // buffer for
            return -1;
        }
        if (declaredLength == 0xffffffffL || declaredLength == 0x7fffffffL) {
            // The two values a zip entry carries when its size was never filled in: all bits set is the ZIP64
            // overflow marker left behind when there is no ZIP64 extra field to replace it, and all bits but the
            // sign bit is what a writer that clamped a size to a signed 32-bit maximum leaves behind
            return -1;
        }
        if (compressedSize > 0L) {
            if (declaredLength > compressedSize * MAX_BELIEVED_COMPRESSION_RATIO) {
                // No amount of deflated data can inflate to this much
                return -1;
            }
            if (declaredLength + UNDERSIZE_SLACK + compressedSize / 1024L < compressedSize) {
                // Deflating data can expand it, but only slightly, so content this much shorter than the bytes it
                // is stored in is not the content those bytes hold
                return -1;
            }
        }
        return (int) Math.min(declaredLength, Slice.MAX_BUFFER_SIZE);
    }

    /**
     * Construct a big-endian reader over a caller-owned {@link InputStream} (closing this reader does not close
     * {@code inputStream}).
     *
     * @param inputStream
     *            the {@link InputStream} to read from.
     */
    public RandomAccessOrSequentialReader(final InputStream inputStream) {
        this(inputStream, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Construct a reader over a caller-owned {@link InputStream} with the specified byte order (closing this reader
     * does not close {@code inputStream}).
     *
     * @param inputStream
     *            the {@link InputStream} to read from.
     * @param byteOrder
     *            the byte order for multi-byte values.
     */
    public RandomAccessOrSequentialReader(final InputStream inputStream, final ByteOrder byteOrder) {
        ownsInputStream = false;
        this.inputStream = inputStream;
        arr = EMPTY_BUF;
        this.byteOrder = byteOrder;
        this.bigEndian = byteOrder == ByteOrder.BIG_ENDIAN;
    }

    @Override
    public ByteOrder byteOrder() {
        return byteOrder;
    }

    @Override
    public long length() throws IOException {
        readToEof();
        return arrUsed;
    }

    /**
     * Returns the current sequential read position (unaffected by random-access reads).
     *
     * @return the current read position.
     */
    @Override
    public int position() {
        return currIdx;
    }

    /**
     * Returns the current capacity of the internal buffer array.
     *
     * @return the length of the buffer array.
     */
    // Package-private rather than private so that a test can pin how the buffer grows
    int bufferLength() {
        return arr.length;
    }

    /**
     * Override the maximum buffer size for testing. Must be called before reading any bytes.
     *
     * @param maxBufferSize
     *            the maximum buffer size in bytes.
     */
    // Package-private rather than private so that only a test in this package can call it
    void setMaxBufferSize(final int maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
    }

    /**
     * Ensure the buffer array has capacity for at least {@code minArrLength} bytes.
     *
     * @param minArrLength
     *            the minimum required buffer capacity.
     */
    private void ensureCapacity(final int minArrLength) {
        if (arr.length >= minArrLength) {
            return;
        }
        // Double the size of the array until it can contain the required number of bytes, starting from the
        // initial buffer size on the first read, when nothing has been allocated yet
        var newArrLength = (long) Math.max(arr.length, INITIAL_BUF_SIZE);
        while (newArrLength < minArrLength) {
            newArrLength *= 2L;
        }
        if (lengthHint > 0 && minArrLength <= lengthHint //
                && (newArrLength > lengthHint
                        // Doubling would overshoot the length the content declares for itself, so allocate
                        // exactly that length instead. A caller that asks for the whole of the content in one go
                        // -- which is what length() does, and so what Files#readAllBytes does through it --
                        // therefore gets a single allocation of exactly the right size, with nothing copied on
                        // the way there.
                        || arr.length >= JUMP_TO_DECLARED_LENGTH_THRESHOLD
                                && lengthHint <= MAX_DECLARED_LENGTH_TO_ALLOCATE)) {
            // A caller that has read this far is reading the content rather than sampling a header, so allocate
            // the whole of the length it declares for itself in one step rather than doubling towards it. This
            // is only done once the buffer has already been grown once, and only up to the length that is
            // allocated from a declared length anywhere else, so that a wrong declared length can still only
            // cost one bounded allocation.
            newArrLength = lengthHint;
        }
        arr = Arrays.copyOf(arr, (int) Math.min(newArrLength, maxBufferSize));
    }

    /**
     * Grow the buffer array toward {@code targetArrUsed}.
     *
     * @param targetArrUsed
     *            the target number of buffered bytes.
     */
    private void grow(final long targetArrUsed) {
        // Computed in long arithmetic, because arrUsed can be within a chunk of the 2GB limit, and an int sum would
        // wrap negative there rather than being clamped by the Math.min below
        final var maxStep = Math.max(Math.max((long) arrUsed + (long) chunkSize, 2L * arr.length),
                Math.min(lengthHint, MAX_DECLARED_LENGTH_TO_ALLOCATE));
        ensureCapacity((int) Math.min(Math.min(targetArrUsed, maxStep), maxBufferSize));
        // The next growth is twice as large, up to the maximum chunk size, so that a caller reading a long way
        // into the content pays a decreasing number of allocations for it, while a caller that stops after a
        // header has allocated only one small buffer
        chunkSize = Math.min(chunkSize * 2, MAX_CHUNK_SIZE);
    }

    /**
     * Read from the stream until {@link #arrUsed} reaches at least {@code targetArrUsed} or EOF is reached.
     *
     * @param targetArrUsed
     *            the target value for {@link #arrUsed}.
     * @throws IOException
     *             if an I/O error occurs or {@code targetArrUsed} exceeds {@link #maxBufferSize}.
     */
    private void readTo(final long targetArrUsed) throws IOException {
        if (targetArrUsed > maxBufferSize || targetArrUsed < 0) {
            throw new IOException("Cannot grow the buffer past " + maxBufferSize + " bytes");
        }
        if (arrUsed >= targetArrUsed || eof) {
            // Either the buffer already holds the requested bytes, or the stream has already ended, in which case
            // the buffer holds the whole of the content and there is nothing left to read
            return;
        }
        final var inputStream = this.inputStream;
        if (inputStream == null) {
            // The stream is only cleared by close(), so the buffer cannot be filled any further than it already is
            throw new IOException("Tried to read past the buffered part of a closed reader");
        }

        // Read into the buffer, starting at position arrUsed, growing it whenever it fills up. InputStream#read is
        // not required to transfer the whole of the requested range in a single call, and the channel-backed
        // streams that a module or a directory is read through really can transfer less, so keep reading until the
        // target has been reached or the stream is exhausted. (Each call may still transfer more than the target,
        // filling the rest of the buffer.)
        while (arrUsed < targetArrUsed) {
            if (arrUsed == arr.length) {
                // The buffer is full and more was asked for, so it has to be grown -- but a stream does not report
                // that it has ended until a read asks it for a byte that it does not have, and the buffer ends up
                // exactly full whenever the content declared its length correctly, which is almost always. Ask for
                // a single byte before growing: growing first would copy the whole of the content into a new array
                // of twice the size, once per entry, only to find that there was nothing left to put in it.
                if (arrUsed > 0) {
                    final var nextByte = inputStream.read();
                    if (nextByte < 0) {
                        // The end of the stream is the end of the content, whatever length it declared for itself
                        eof = true;
                        break;
                    }
                    // The content ran on past the buffer, so keep the byte that was probed for and carry on
                    grow(targetArrUsed);
                    arr[arrUsed++] = (byte) nextByte;
                    continue;
                }
                // Nothing has been read yet, so there is nothing that growing could copy, and no reason to probe
                grow(targetArrUsed);
            }
            // The buffer has room, so the read returns at least one byte, or -1 at the end of the stream, even from
            // a stream that returns zero from a read (see readAtLeastOne)
            final var numRead = InputStreamUtils.readAtLeastOne(inputStream, arr, arrUsed, arr.length - arrUsed);
            if (numRead < 0) {
                eof = true;
                break;
            }
            arrUsed += numRead;
        }
    }

    /**
     * Verify that the stream ended within {@link #maxBufferSize} rather than being truncated at the buffer limit.
     *
     * @throws IOException
     *             if the content exceeds {@link #maxBufferSize}.
     */
    private void checkContentEndedWithinBuffer() throws IOException {
        if (eof || arrUsed < maxBufferSize) {
            // Either the stream ended, so the buffer holds the whole of the content, or the buffer stopped short of
            // the limit, which readTo only does at the end of the content
            return;
        }
        final var inputStream = this.inputStream;
        if (inputStream != null && inputStream.read() < 0) {
            eof = true;
            return;
        }
        throw new IOException("Content is larger than the " + maxBufferSize + " bytes that can be buffered");
    }

    /**
     * Read the entire stream into the buffer until EOF.
     *
     * @throws IOException
     *             if the content could not be read or exceeds {@link #maxBufferSize}.
     */
    private void readToEof() throws IOException {
        if (!eof && lengthHint > 0) {
            // Read the length the content declares for itself in one go, so that content that declares its length
            // correctly -- which is almost all of it -- is read with a single allocation and without repeatedly
            // growing the buffer. The allocation is capped, since a zip entry can declare any uncompressed size it
            // likes, and believing a declared length far enough to allocate it would let a small archive ask for
            // gigabytes.
            readTo(Math.min(lengthHint, MAX_DECLARED_LENGTH_TO_ALLOCATE));
        }
        while (!eof) {
            // The content ran on past the length it declared for itself, or declared no length at all, so keep
            // pulling chunks until the stream really ends. Asking for one chunk beyond what has been read makes
            // readTo probe for a single byte before it grows the buffer, so reaching the end of content that did
            // declare its length correctly costs one read call and no allocation.
            if (arrUsed == maxBufferSize) {
                // The buffer is full to the limit. Either the content ends exactly there, in which case this sets
                // eof and the loop stops, or it runs on past what can be buffered, in which case this throws
                checkContentEndedWithinBuffer();
                break;
            }
            readTo(Math.min((long) arrUsed + chunkSize, maxBufferSize));
        }
    }

    /**
     * Ensure that {@code numBytes} starting at {@code srcOffset} are buffered in {@link #arr}.
     *
     * @param srcOffset
     *            the start offset.
     * @param numBytes
     *            the number of bytes to read.
     * @return {@code srcOffset} as an int index into {@link #arr}.
     * @throws IOException
     *             if the range is out of bounds, reaches EOF prematurely, or cannot be read.
     */
    private int bufferFor(final long srcOffset, final int numBytes) throws IOException {
        // The offset is range-checked before it is narrowed to an int, since narrowing it silently would turn a
        // read from outside the content into a read from within it
        if (srcOffset < 0L || srcOffset > maxBufferSize) {
            throw new IOException("Read offset out of range: " + srcOffset);
        }
        if (numBytes < 0) {
            // A string length read out of corrupt content can be negative, and the range check below would pass it
            throw new IOException("Read index out of bounds");
        }
        final var idx = (int) srcOffset;
        // The end of the range is compared by subtraction rather than by adding numBytes to idx, because an offset
        // and a length read out of corrupt content can sum to more than an int holds, and a wrapped sum would make
        // a read from past the end of the content look like one that is already buffered
        if (numBytes > arrUsed - idx) {
            readTo((long) idx + numBytes);
            if (numBytes > arrUsed - idx) {
                // The content ended before the whole of the value could be read, and half of a value is not a
                // value -- unless what ran out was the buffer rather than the content, which this reports instead
                checkContentEndedWithinBuffer();
                throw new IOException(END_OF_CONTENT);
            }
        }
        return idx;
    }

    /**
     * Buffer up to {@code numBytes} starting at {@code srcOffset} for a bulk read and return the number of bytes
     * available.
     *
     * @param srcOffset
     *            the non-negative start offset.
     * @param numBytes
     *            the non-negative maximum number of bytes to read.
     * @return the number of bytes available at {@code srcOffset} (0 if at or past the end of the content).
     * @throws IOException
     *             if the bytes could not be read or the content exceeds {@link #maxBufferSize}.
     */
    private int bufferForBulkRead(final long srcOffset, final int numBytes) throws IOException {
        if (srcOffset > maxBufferSize) {
            // Nothing can be buffered at an offset past the largest buffer, so this read cannot be served either
            // way -- but content that ends before the offset is at its end there, which a bulk read reports by
            // returning zero, while content that runs on past the limit is not, and reporting the end of it would
            // hand back a truncated copy. Telling those apart means finding where the content ends, and the only
            // way to do that is to read it, which is why this is the one read path that buffers the whole of the
            // content. It is reached only by seeking past the limit on the buffer, which no read of content that
            // fits in a buffer does.
            readToEof();
            return 0;
        }
        final var idx = (int) srcOffset;
        if (numBytes > arrUsed - idx) {
            readTo(Math.min((long) idx + numBytes, maxBufferSize));
        }
        // The bytes that are there may be fewer than the bytes that were asked for, if the content ended first
        final var numBytesAvailable = (int) Math.max(Math.min(numBytes, (long) arrUsed - idx), 0L);
        if (numBytesAvailable < numBytes) {
            // The read stopped short, so check that it was the content that ran out and not the buffer
            checkContentEndedWithinBuffer();
        }
        return numBytesAvailable;
    }

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        final var dstRoom = ReaderBounds.numBytesFree(srcOffset, dstArr.length, dstArrStart, numBytes);
        if (numBytes == 0 || dstRoom == 0) {
            return 0;
        }
        final var idx = (int) srcOffset;
        // Buffer only as much as the destination can take: numBytes is only the most that may be read, and the
        // content past the room in the destination could not be returned anyway
        final var numBytesToRead = bufferForBulkRead(srcOffset, Math.min(numBytes, dstRoom));
        if (numBytesToRead == 0) {
            return -1;
        }
        try {
            System.arraycopy(arr, idx, dstArr, dstArrStart, numBytesToRead);
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            // The bounds were checked above, so reaching here means a bounds check is wrong rather than that
            // the caller asked for too much -- without the cause there is no record of which index was rejected
            throw new IOException("Read index out of bounds", e);
        }
    }

    @Override
    public int read(final long srcOffset, final ByteBuffer dstBuf, final int dstBufStart, final int numBytes)
            throws IOException {
        final var dstRoom = ReaderBounds.numBytesFree(srcOffset, dstBuf, dstBufStart, numBytes);
        if (numBytes == 0 || dstRoom == 0) {
            return 0;
        }
        final var idx = (int) srcOffset;
        // Buffer only as much as the destination can take, as in the byte array overload
        final var numBytesToRead = bufferForBulkRead(srcOffset, Math.min(numBytes, dstRoom));
        if (numBytesToRead == 0) {
            return -1;
        }
        try {
            // An absolute put, so the destination's position and limit are neither read nor changed
            dstBuf.put(dstBufStart, arr, idx, numBytesToRead);
            return numBytesToRead;
        } catch (final IndexOutOfBoundsException e) {
            // The bounds were checked above, so reaching here means a bounds check is wrong rather than that
            // the caller asked for too much -- without the cause there is no record of which index was rejected
            throw new IOException("Read index out of bounds", e);
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
        final var bigEndianVal = (short) (((arr[idx] & 0xff) << 8) //
                | (arr[idx + 1] & 0xff));
        return (bigEndian ? bigEndianVal : Short.reverseBytes(bigEndianVal)) & 0xffff;
    }

    @Override
    public int readInt(final long offset) throws IOException {
        final var idx = bufferFor(offset, 4);
        final var bigEndianVal = ((arr[idx] & 0xff) << 24) //
                | ((arr[idx + 1] & 0xff) << 16) //
                | ((arr[idx + 2] & 0xff) << 8) //
                | (arr[idx + 3] & 0xff);
        return bigEndian ? bigEndianVal : Integer.reverseBytes(bigEndianVal);
    }

    @Override
    public long readUnsignedInt(final long offset) throws IOException {
        return readInt(offset) & 0xffffffffL;
    }

    @Override
    public long readLong(final long offset) throws IOException {
        final var idx = bufferFor(offset, 8);
        final var bigEndianVal = ((arr[idx] & 0xffL) << 56) //
                | ((arr[idx + 1] & 0xffL) << 48) //
                | ((arr[idx + 2] & 0xffL) << 40) //
                | ((arr[idx + 3] & 0xffL) << 32) //
                | ((arr[idx + 4] & 0xffL) << 24) //
                | ((arr[idx + 5] & 0xffL) << 16) //
                | ((arr[idx + 6] & 0xffL) << 8) //
                | (arr[idx + 7] & 0xffL);
        return bigEndian ? bigEndianVal : Long.reverseBytes(bigEndianVal);
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
            if (targetIdx > maxBufferSize) {
                throw new IOException("Tried to skip past the " + maxBufferSize + " bytes that can be buffered");
            }
            readTo(targetIdx);
            if (arrUsed < targetIdx) {
                throw new IOException(END_OF_CONTENT);
            }
        }
        currIdx = (int) targetIdx;
    }

    /**
     * Set the sequential read position (moving backward into already-buffered content or forward by
     * reading/skipping up to {@code newPosition}).
     *
     * @param newPosition
     *            the new position relative to the start of the content.
     * @throws IllegalArgumentException
     *             if {@code newPosition} is negative.
     * @throws IOException
     *             if {@code newPosition} is past the end of the content or the bytes up to it could not be read
     *             (leaving the position unchanged).
     */
    public void position(final int newPosition) throws IOException {
        if (newPosition < 0) {
            throw new IllegalArgumentException("Negative position: " + newPosition);
        }
        if (newPosition < currIdx) {
            currIdx = newPosition;
        } else {
            skip(newPosition - currIdx);
        }
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
        // Delegate to the random access overload, for the same reason as the modified UTF-8 overload above
        final var val = readString(currIdx, numBytes, charset);
        currIdx += numBytes;
        return val;
    }

    /**
     * Returns whether the {@code numBytes} starting at {@code srcOffset} equal the ASCII characters of
     * {@code asciiStr} without allocating a {@link String}.
     *
     * @param srcOffset
     *            the start offset in the content.
     * @param numBytes
     *            the number of bytes to compare.
     * @param asciiStr
     *            the ASCII string to compare against.
     * @return true if the bytes at {@code srcOffset} match {@code asciiStr}.
     * @throws IOException
     *             if the range is out of bounds, reaches EOF prematurely, or cannot be read.
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
        } catch (final IOException _) {
            // Ignore
        } finally {
            this.inputStream = null;
        }
    }
}
