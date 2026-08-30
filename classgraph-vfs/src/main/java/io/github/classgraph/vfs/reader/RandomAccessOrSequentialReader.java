
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

import io.github.classgraph.base.internal.utils.StringUtils;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.internal.slice.Slice;
import org.jspecify.annotations.Nullable;

/**
 * A reader that works as either a {@link RandomAccessReader} or a {@link SequentialReader}. The content is read as
 * a stream, and is buffered up to the point it has been read so far, so that the random access methods can go back
 * over any part of it that has already been read. (Parsing a classfile needs exactly this: the constant pool is
 * read sequentially, then indexed into. So does reading the header of a deflated zip entry through a channel: only
 * the header has to be inflated.)
 *
 * <p>
 * Reads in <b>big endian</b> order by default, as required by the Java classfile format, which is what this reader
 * was written for; pass a {@link ByteOrder} to read content written in the other order. See
 * {@link RandomAccessReader} for why the byte order is a property of the content and not of the machine.
 *
 * <p>
 * The length of the content is only known once the end of the stream has been reached, so {@link #length()} reads
 * the whole of the content -- a caller that does not need the length should not ask for it. A length passed to the
 * constructor, such as the uncompressed size a zip entry declares for itself, is used to size the buffer and
 * nothing else: the zipfile format does not guarantee that field is right, and the JDK's own {@code ZipFile} and
 * {@code jdk.nio.zipfs} do not trust it either, so the end of the content is where the stream ends, whether that is
 * before or after the declared length.
 *
 * <p>
 * Because the content is buffered in a {@code byte[]}, this reader cannot go further into content than the largest
 * array the JVM can allocate, which is just under 2GB. Content that runs on past that point is not truncated
 * silently: any read that would have to go past the limit throws an {@link IOException} saying so. To read content
 * larger than 2GB, stream it with {@link VfsEntry#open()} instead, which has no such limit.
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

    /**
     * The length the content declares for itself, if it declares one, or -1 if it does not. This is a hint used to
     * size the buffer, and is not where the content is taken to end -- see the class documentation.
     */
    private int lengthHint = -1;

    /** True once the end of the stream has been reached, at which point {@link #arrUsed} is the true length. */
    private boolean eof;

    /** The number of bytes the buffer is next grown by. Doubles up to {@link #MAX_CHUNK_SIZE}. */
    private int chunkSize = MIN_CHUNK_SIZE;

    /**
     * The largest buffer this reader will grow, which is the largest array the JVM can allocate. Held as a field
     * rather than read from {@link Slice#MAX_BUFFER_SIZE} directly so that a test can lower it and reach the
     * behavior at the limit without allocating two gigabytes; nothing outside a test changes it.
     */
    private int maxBufferSize = Slice.MAX_BUFFER_SIZE;

    /** The byte order multi-byte values are read in. */
    private final ByteOrder byteOrder;

    /**
     * Whether {@link #byteOrder} is {@link ByteOrder#BIG_ENDIAN}, kept as a field to keep the reads branch-free.
     */
    private final boolean bigEndian;

    /**
     * The size the buffer is grown to the first time anything is read, unless the first read is of more than this.
     * For most content only a prefix is read -- for a classfile, the first 16-64kb, since the bytecodes are not
     * read -- so the declared length of the content is deliberately not used here: a caller that reads a 64-byte
     * header of a 1MB entry should not be given a 1MB buffer.
     */
    private static final int INITIAL_BUF_SIZE = 16384;

    /**
     * The buffer before anything has been read. Nothing is allocated until the first read, so that a caller that
     * asks for the whole of the content in one read gets a buffer of exactly that size, rather than a buffer of
     * {@link #INITIAL_BUF_SIZE} that is thrown away and copied into a larger one straight afterwards.
     */
    private static final byte[] EMPTY_BUF = {};

    /**
     * Read at least this many bytes the first time there is a buffer underrun. This is smaller than 8k by 8 bytes
     * to prevent the doubling of the array size when the last chunk doesn't quite fit within the 16kb of
     * INITIAL_BUF_SIZE, since the number of bytes that can be requested is up to 8 (for longs). Otherwise we could
     * request to read to (8kb * 2 + 8), which would double the size of the buffer to 32kb, but if we only need to
     * read between 8kb and 16kb, then we unnecessarily copied the buffer content one extra time.
     */
    private static final int MIN_CHUNK_SIZE = 8192 - 8;

    /**
     * The largest number of bytes the buffer is grown by at once. The chunk doubles from {@link #MIN_CHUNK_SIZE} up
     * to this, so that a caller reading a long way into the content pays a decreasing number of allocations for it,
     * while a caller reading only a header still allocates only one small buffer.
     *
     * <p>
     * Reading every entry of a 2.8GB archive of deflated text, with the declared length ignored so that this is the
     * only thing that decides how the buffer grows, measured the same at every ceiling from 8k to 1MB, single
     * threaded and on 32 threads. This is therefore set at the point where raising it stopped changing anything,
     * rather than at a measured optimum. It is only the growth floor: once the buffer is larger than this it
     * doubles instead, and each read fills as much of the buffer as has room, whatever the chunk asked for.
     */
    private static final int MAX_CHUNK_SIZE = 65536;

    /**
     * The largest buffer that is allocated in one go from the length the content declares for itself, rather than
     * by doubling. A zip entry can declare any uncompressed size it likes, so believing a declared length far
     * enough to allocate it would let a small archive ask for gigabytes.
     */
    private static final int MAX_DECLARED_LENGTH_TO_ALLOCATE = 16 * 1024 * 1024;

    /**
     * Once the buffer has been grown to at least this size, the next growth allocates the whole of the length the
     * content declares for itself, rather than continuing to double towards it. A caller that has read this far is
     * reading the content and not sampling a header, and doubling the rest of the way to the declared length copies
     * roughly the whole of the content again on the way there. This is the size of the first buffer, so the jump
     * happens on the second growth: the first growth is what tells a header read apart from a content read, and
     * nothing smaller can do that job.
     */
    private static final int JUMP_TO_DECLARED_LENGTH_THRESHOLD = INITIAL_BUF_SIZE;

    /**
     * The largest ratio of declared uncompressed size to compressed size that is believed. The best ratio the
     * DEFLATE format can reach is 1032:1, so a declared length far above that for the number of compressed bytes
     * behind it cannot be right. The margin over 1032 is wide because the cost of believing a wrong length is one
     * bounded over-allocation, while the cost of disbelieving a right one is a buffer grown by doubling.
     */
    private static final int MAX_BELIEVED_COMPRESSION_RATIO = 2048;

    /**
     * The number of bytes by which a declared uncompressed size may fall below the compressed size before it is
     * disbelieved. Deflating incompressible data expands it, but only by about 5 bytes per 64kb block, so a
     * declared length that is a whole kilobyte below the compressed size, plus a further allowance that grows with
     * the entry, is describing different content than the entry holds.
     */
    private static final int UNDERSIZE_SLACK = 1024;

    /** The message of the {@link IOException} thrown when a read runs past the end of the content. */
    private static final String END_OF_CONTENT = "Tried to read past the end of the content";

    /**
     * The message of the {@link IOException} thrown when the content runs on past the 2GB that can be buffered.
     */
    private static final String CONTENT_TOO_LARGE = "Content is larger than the 2GB that can be buffered";

    /**
     * Constructor for reading an entry of a virtual filesystem. Whatever the entry has to open in order to be read
     * is opened by this reader, and closed by {@link #close()}, so the entry itself does not need to be opened by
     * the caller.
     *
     * <p>
     * An entry larger than 2GB cannot be read through this reader, which buffers what it has read in a
     * {@code byte[]}: see the class documentation. Read such an entry as a stream, with {@link VfsEntry#open()}.
     *
     * @param entry
     *            the {@link VfsEntry} to read.
     * @throws IOException
     *             If the entry could not be opened.
     */
    public RandomAccessOrSequentialReader(final VfsEntry entry) throws IOException {
        this(entry, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Constructor for reading an entry of a virtual filesystem in a given byte order. Whatever the entry has to
     * open in order to be read is opened by this reader, and closed by {@link #close()}, so the entry itself does
     * not need to be opened by the caller.
     *
     * <p>
     * An entry larger than 2GB cannot be read through this reader, which buffers what it has read in a
     * {@code byte[]}: see the class documentation. Read such an entry as a stream, with {@link VfsEntry#open()}.
     *
     * @param entry
     *            the {@link VfsEntry} to read.
     * @param byteOrder
     *            the byte order to read multi-byte values in. Pass {@link ByteOrder#nativeOrder()} for content
     *            written in the byte order of the machine this is running on.
     * @throws IOException
     *             If the entry could not be opened.
     */
    public RandomAccessOrSequentialReader(final VfsEntry entry, final ByteOrder byteOrder) throws IOException {
        // The entry opens whatever it has to in order to be read, and this reader closes it
        ownsInputStream = true;
        inputStream = entry.open();
        arr = EMPTY_BUF;
        this.byteOrder = byteOrder;
        this.bigEndian = byteOrder == ByteOrder.BIG_ENDIAN;
        // Knowing how long the entry says it is saves the buffer from being grown a chunk at a time to find out.
        // It is only a hint: see the class documentation for why the declared length is not trusted as the end of
        // the content.
        lengthHint = validatedLengthHint(entry.getLength(), entry.getCompressedSize());
    }

    /**
     * Check a declared uncompressed length against the number of compressed bytes that are supposed to produce it,
     * and reject it if the two cannot describe the same content. A wrong declared length is never allowed to
     * truncate or pad what is read -- the end of the content is always where the stream ends -- so this only
     * decides whether the length is worth believing far enough to size the buffer from it. Believing a wrong one
     * costs an allocation that is thrown away.
     *
     * <p>
     * For an entry that is not stored compressed the two sizes are the same, so none of the checks can fire, and
     * the declared length is used as it stands.
     *
     * @param declaredLength
     *            the uncompressed length the content declares for itself, or -1 if it declares none.
     * @param compressedSize
     *            the number of stored bytes the content is read from, or -1 if that is not known.
     * @return the length to size the buffer from, or -1 if the declared length is not worth believing.
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
     * Constructor for reading content that is already open as a stream. The stream belongs to the caller, which
     * opens it in a try-with-resources and closes it once the reader has been closed.
     *
     * <p>
     * Content larger than 2GB cannot be read through this reader, which buffers what it has read in a
     * {@code byte[]}: see the class documentation. Read such content from the stream directly.
     *
     * @param inputStream
     *            the {@link InputStream} to read from.
     */
    public RandomAccessOrSequentialReader(final InputStream inputStream) {
        this(inputStream, ByteOrder.BIG_ENDIAN);
    }

    /**
     * Constructor for reading content that is already open as a stream, in a given byte order. The stream belongs
     * to the caller, which opens it in a try-with-resources and closes it once the reader has been closed.
     *
     * <p>
     * Content larger than 2GB cannot be read through this reader, which buffers what it has read in a
     * {@code byte[]}: see the class documentation. Read such content from the stream directly.
     *
     * @param inputStream
     *            the {@link InputStream} to read from.
     * @param byteOrder
     *            the byte order to read multi-byte values in. Pass {@link ByteOrder#nativeOrder()} for content
     *            written in the byte order of the machine this is running on.
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
     * The position the sequential read methods read from next, which starts at zero and is advanced by each of
     * them, and by {@link #skip(int)}. The random access read methods take the position to read from as an
     * argument, and do not move it.
     *
     * @return the current read position.
     */
    public int currPos() {
        return currIdx;
    }

    /**
     * The size of the buffer the content has been read into so far. This is how much has been allocated, not how
     * much has been read, so it says how the buffer was grown rather than how long the content is.
     *
     * @return the length of the buffer array.
     */
    // Package-private rather than private so that a test can pin how the buffer grows
    int bufferLength() {
        return arr.length;
    }

    /**
     * Lower the largest buffer this reader will grow, so that a test can reach the behavior at the limit without
     * allocating two gigabytes. Must be called before anything is read.
     *
     * @param maxBufferSize
     *            the limit to apply in place of the largest array the JVM can allocate.
     */
    // Package-private rather than private so that only a test in this package can call it
    void setMaxBufferSize(final int maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
    }

    /**
     * Grow the buffer array, if needed, so that it can hold at least the given number of bytes.
     *
     * @param minArrLength
     *            the number of bytes the array has to be able to hold.
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
     * Grow the buffer array so that at least one more byte can be read into it, by at least a whole chunk, so that
     * a caller reading one value at a time does not pay one allocation per value.
     *
     * @param targetArrUsed
     *            the number of bytes the current read is trying to reach.
     */
    private void grow(final long targetArrUsed) {
        // The chunk end is computed in long arithmetic, because arrUsed can be within a chunk of the 2GB limit,
        // and an int sum would wrap negative there rather than being clamped by the Math.min below
        ensureCapacity((int) Math.min(Math.max(targetArrUsed, (long) arrUsed + (long) chunkSize), maxBufferSize));
        // The next growth is twice as large, up to the maximum chunk size, so that a caller reading a long way
        // into the content pays a decreasing number of allocations for it, while a caller that stops after a
        // header has allocated only one small buffer
        chunkSize = Math.min(chunkSize * 2, MAX_CHUNK_SIZE);
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
        if (targetArrUsed > maxBufferSize || targetArrUsed < 0) {
            throw new IOException("Hit 2GB limit while trying to grow buffer array");
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
            final var numRead = inputStream.read(arr, arrUsed, arr.length - arrUsed);
            if (numRead < 0) {
                eof = true;
                break;
            }
            if (numRead == 0) {
                // The stream transferred nothing into a buffer that has room, which InputStream#read only does at
                // the end of a stream that does not report the end by returning -1
                break;
            }
            arrUsed += numRead;
        }
    }

    /**
     * Check that the content really ended where the buffer stops, rather than running on past the 2GB that a buffer
     * array can hold. Called wherever a read would otherwise report the end of the content at the 2GB limit: the
     * caller cannot tell that apart from the real end of the content, so reporting it would hand back a silently
     * truncated copy of a longer entry.
     *
     * <p>
     * Content that ends exactly at the limit is not truncated, and a stream only reports its end once a read asks
     * it for a byte it does not have, so the stream is asked for one more byte before this concludes anything. The
     * byte is discarded, since there is nowhere left to put it, but that only happens on the path that throws.
     *
     * @throws IOException
     *             if the content is longer than can be buffered.
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
        throw new IOException(CONTENT_TOO_LARGE);
    }

    /**
     * Read the whole of the content into the buffer, so that {@link #arrUsed} is its true length. Does nothing if
     * the end of the stream has already been reached.
     *
     * @throws IOException
     *             if the content could not be read.
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
        if (srcOffset < 0L || srcOffset > maxBufferSize) {
            throw new IOException("Read offset out of range: " + srcOffset);
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
     * Ensure that as many as possible of the given number of bytes have been read into the buffer, starting at the
     * given offset, and report how many of them are there. Unlike {@link #bufferFor(long, int)} this does not throw
     * at the end of the content, since a bulk read stops at the end of the content and reports how far it got.
     *
     * @param srcOffset
     *            the offset the read starts at.
     * @param numBytes
     *            the maximum number of bytes to be read.
     * @return the number of bytes that can be read at {@code srcOffset}, which is zero at or past the end of the
     *         content.
     * @throws IOException
     *             if the offset or the number of bytes is negative, or if the bytes could not be read.
     */
    private int bufferForBulkRead(final long srcOffset, final int numBytes) throws IOException {
        if (srcOffset < 0L || numBytes < 0) {
            throw new IOException("Read index out of bounds");
        }
        if (srcOffset > maxBufferSize) {
            // Nothing can be buffered at an offset past the largest buffer, so this read cannot be served either
            // way -- but content that ends before the offset is at its end there, which a bulk read reports by
            // returning zero, while content that runs on past the limit is not, and reporting the end of it would
            // hand back a truncated copy. Telling those apart means finding where the content ends, and the only
            // way to do that is to read it, which is why this is the one read path that buffers the whole of the
            // content. It is reached only by seeking past 2GB, which no read of content that fits in a buffer
            // does.
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
        final var dstRoom = ReaderBounds.numBytesFree(dstArr.length, dstArrStart);
        if (numBytes == 0 || dstRoom == 0) {
            return 0;
        }
        final var idx = (int) srcOffset;
        final var numBytesInContent = bufferForBulkRead(srcOffset, numBytes);
        if (numBytesInContent == 0) {
            return -1;
        }
        final var numBytesToRead = Math.min(numBytesInContent, dstRoom);
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
        final var dstRoom = ReaderBounds.numBytesFree(dstBuf.limit(), dstBufStart);
        if (numBytes == 0 || dstRoom == 0) {
            return 0;
        }
        if (dstBuf.isReadOnly()) {
            // Checked here rather than left to the put below, so that every RandomAccessReader reports a
            // read-only destination the same way -- the file channel reader cannot catch it, since FileChannel
            // rejects a read-only destination with an IllegalArgumentException of its own
            throw new IOException("The destination buffer is read-only");
        }
        final var idx = (int) srcOffset;
        final var numBytesInContent = bufferForBulkRead(srcOffset, numBytes);
        if (numBytesInContent == 0) {
            return -1;
        }
        final var numBytesToRead = Math.min(numBytesInContent, dstRoom);
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
                throw new IOException("Tried to skip past the 2GB limit");
            }
            readTo(targetIdx);
            if (arrUsed < targetIdx) {
                throw new IOException(END_OF_CONTENT);
            }
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
