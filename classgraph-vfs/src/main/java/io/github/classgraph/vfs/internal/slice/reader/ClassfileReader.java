
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
import java.util.Arrays;
import java.util.Objects;

import io.github.classgraph.base.internal.utils.FileUtils;
import io.github.classgraph.base.internal.utils.StringUtils;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.internal.slice.ArraySlice;
import io.github.classgraph.vfs.internal.slice.FileSlice;
import io.github.classgraph.vfs.internal.slice.Slice;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Slice} reader that works as either a {@link RandomAccessReader} or a {@link SequentialReader}. The file
 * is buffered up to the point it has been read so far. Reads in <b>big endian</b> order, as required by the
 * classfile format.
 */
public class ClassfileReader implements RandomAccessReader, SequentialReader, AutoCloseable {
    /**
     * The stream the classfile is read through, if it is not read by random access: either an
     * {@link java.util.zip.InflaterInputStream} that this reader opened on a deflated {@link Slice}, or a stream
     * that the caller opened and passed in.
     */
    private @Nullable InputStream inflaterInputStream;

    /**
     * True if this reader opened {@link #inflaterInputStream} itself, and so has to close it. A stream that the
     * caller passed in belongs to the caller, which closes it in its own try-with-resources.
     */
    private final boolean ownsInputStream;

    /**
     * If slice is not deflated, a {@link RandomAccessReader} for either the {@link ArraySlice} or {@link FileSlice}
     * concrete subclass.
     */
    private @Nullable RandomAccessReader randomAccessReader;

    /** Buffer. */
    private byte[] arr;

    /** The number of bytes used in arr. */
    private int arrUsed;

    /** The current read index within the slice. */
    private int currIdx;

    /**
     * The length of the classfile if known (because it is not deflated), or -1 if unknown (because it is deflated).
     */
    private int classfileLengthHint = -1;

    /**
     * Initial buffer size. For most classfiles, only the first 16-64kb needs to be read (we don't read the
     * bytecodes).
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

    /**
     * Constructor for reading a classfile out of a virtual filesystem. Whatever the entry has to open in order to
     * be read is opened by this reader, and closed by {@link #close()}, so the entry itself does not need to be
     * opened by the caller.
     *
     * @param entry
     *            the {@link VfsEntry} to read the classfile from.
     * @throws IOException
     *             If the entry could not be opened.
     */
    public ClassfileReader(final VfsEntry entry) throws IOException {
        // The entry opens whatever it has to in order to be read, and this reader closes it
        ownsInputStream = true;
        inflaterInputStream = entry.open();
        arr = new byte[INITIAL_BUF_SIZE];
        // Telling the reader how long the classfile is saves it from growing the buffer to find out
        final var length = entry.getLength();
        classfileLengthHint = length < 0L ? -1 : (int) Math.min(length, FileUtils.MAX_BUFFER_SIZE);
    }

    /**
     * Constructor. The {@link Slice} stays open: it belongs to the caller, which closes it. If the slice is
     * deflated, the inflater stream that this reader opens on it does belong to this reader, and is closed by
     * {@link #close()}.
     *
     * @param slice
     *            the {@link Slice} to read.
     * @throws IOException
     *             If an inflater cannot be opened on the {@link Slice}.
     */
    public ClassfileReader(final Slice slice) throws IOException {
        // Only the deflated branch opens a stream, and only a stream this reader opened is closed by close()
        ownsInputStream = true;
        if (slice.isDeflatedZipEntry) {
            // If this is a deflated slice, need to read from an InflaterInputStream to fill buffer
            inflaterInputStream = slice.open();
            classfileLengthHint = (int) Math.min(slice.inflatedLengthHint, FileUtils.MAX_BUFFER_SIZE);
            arr = new byte[INITIAL_BUF_SIZE];
        } else if (slice instanceof final ArraySlice arraySlice) {
            // If slice is an ArraySlice, avoid copying by simply reusing the wrapped byte array in place of the
            // buffer array, and mark it as fully loaded
            if (arraySlice.sliceStartPos == 0 && arraySlice.sliceLength == arraySlice.arr.length) {
                // ArraySlice is the whole array
                arr = arraySlice.arr;
            } else {
                // ArraySlice covers only a partial array, and this class doesn't support a starting offset, so
                // copy the sliced part of the array to a new buffer
                arr = Arrays.copyOfRange(arraySlice.arr, (int) arraySlice.sliceStartPos,
                        (int) (arraySlice.sliceStartPos + arraySlice.sliceLength));
            }
            arrUsed = arr.length;
            classfileLengthHint = arr.length;
        } else {
            // Otherwise this is a FileSlice -- need to fetch chunks of bytes using a random access reader
            randomAccessReader = slice.randomAccessReader();
            classfileLengthHint = (int) Math.min(slice.sliceLength, FileUtils.MAX_BUFFER_SIZE);
            arr = new byte[INITIAL_BUF_SIZE];
        }
    }

    /**
     * Constructor for reading a classfile that is already open as a stream. The stream belongs to the caller, which
     * opens it in a try-with-resources and closes it once the reader has been closed.
     *
     * @param inputStream
     *            the {@link InputStream} to read from.
     */
    public ClassfileReader(final InputStream inputStream) {
        ownsInputStream = false;
        inflaterInputStream = inputStream;
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
     * Buf.
     *
     * @return the buffer.
     */
    public byte[] buf() {
        return arr;
    }

    /**
     * Called when there is a buffer underrun to ensure there are sufficient bytes available in the array to read
     * the given number of bytes at the given start index.
     *
     * @param targetArrUsed
     *            the target value for {@link #arrUsed} (i.e. the number of bytes that must be filled in the array)
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void readTo(final int targetArrUsed) throws IOException {
        // Array does not need to grow larger than the length hint (if the uncompressed size of the zip entry is an
        // underestimate, classfile will be truncated). If -1, assume 2GB is the max size.
        final var maxArrLen = classfileLengthHint == -1 ? FileUtils.MAX_BUFFER_SIZE : classfileLengthHint;
        final var inflaterInputStream = this.inflaterInputStream;
        final var randomAccessReader = this.randomAccessReader;
        if (inflaterInputStream == null && randomAccessReader == null) {
            // If neither inflaterInputStream nor randomAccessReader is set, then slice is an ArraySlice, and array
            // is already "fully loaded" (the ArraySlice's backing array is used as the buffer).
            throw new IOException("Tried to read past end of fixed array buffer");
        }
        if (targetArrUsed > FileUtils.MAX_BUFFER_SIZE || targetArrUsed < 0 || arrUsed == maxArrLen) {
            throw new IOException("Hit 2GB limit while trying to grow buffer array");
        }

        // Need to read at least BUF_CHUNK_SIZE (but don't overshoot past 2GB limit)
        final var maxNewArrUsed = (int) Math.min(Math.max(targetArrUsed, (long) (arrUsed + BUF_CHUNK_SIZE)),
                maxArrLen);

        // Double the size of the array if it's too small to contain the new chunk of bytes
        long newArrLength = arr.length;
        while (newArrLength < maxNewArrUsed) {
            newArrLength = Math.min(maxNewArrUsed, newArrLength * 2L);
        }
        if (newArrLength > FileUtils.MAX_BUFFER_SIZE) {
            throw new IOException("Hit 2GB limit while trying to grow buffer array");
        }
        arr = Arrays.copyOf(arr, (int) Math.min(newArrLength, maxArrLen));

        // Figure out the maximum number of bytes that can be read into the array
        final var maxBytesToRead = arr.length - arrUsed;

        // Read a new chunk into the buffer, starting at position arrUsed
        if (inflaterInputStream != null) {
            // Read from the input stream. InputStream#read is not required to transfer the whole of the requested
            // range in a single call, and the channel-backed streams that a module or a directory is read through
            // really can transfer less, so keep reading until the target has been reached or the stream is
            // exhausted. (Each call may still transfer more than the target, filling the rest of the buffer.)
            while (arrUsed < targetArrUsed) {
                final var numRead = inflaterInputStream.read(arr, arrUsed, arr.length - arrUsed);
                if (numRead <= 0) {
                    // -1 => end of stream; 0 => the buffer has no space left
                    break;
                }
                arrUsed += numRead;
            }
        } else /* inflaterInputStream == null, so this is a (non-deflated) FileSlice */ {
            // Don't read past end of slice
            final var bytesToRead = Math.min(maxBytesToRead, maxArrLen - arrUsed);
            // Read bytes from FileSlice into arr randomAccessReader is non-null if inflaterInputStream is null (see
            // above)
            final var numBytesRead = Objects.requireNonNull(randomAccessReader).read(/* srcOffset = */ arrUsed,
                    /* dstArr = */ arr, /* dstArrStart = */ arrUsed, /* numBytes = */ bytesToRead);
            if (numBytesRead > 0) {
                arrUsed += numBytesRead;
            }
        }

        // Check the buffer was able to be filled to the requested position
        if (arrUsed < targetArrUsed) {
            throw new IOException("Buffer underflow");
        }
    }

    /**
     * Ensure that the given number of bytes have been read into the buffer from the beginning of the slice.
     *
     * @param numBytes
     *            the number of bytes to ensure have been buffered
     * @throws IOException
     *             on EOF or if the bytes could not be read.
     */
    public void bufferTo(final int numBytes) throws IOException {
        if (numBytes > arrUsed) {
            readTo(numBytes);
        }
    }

    @Override
    public int read(final long srcOffset, final byte[] dstArr, final int dstArrStart, final int numBytes)
            throws IOException {
        if (numBytes == 0) {
            return 0;
        }
        final var idx = (int) srcOffset;
        if (idx + numBytes > arrUsed) {
            readTo(idx + numBytes);
        }
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
        final var idx = (int) srcOffset;
        if (idx + numBytes > arrUsed) {
            readTo(idx + numBytes);
        }
        final var numBytesToRead = Math.max(Math.min(numBytes, dstBuf.capacity() - dstBufStart), 0);
        if (numBytesToRead == 0) {
            return -1;
        }
        try {
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
        final var idx = (int) offset;
        if (idx + 1 > arrUsed) {
            readTo(idx + 1);
        }
        return arr[idx];
    }

    @Override
    public int readUnsignedByte(final long offset) throws IOException {
        final var idx = (int) offset;
        if (idx + 1 > arrUsed) {
            readTo(idx + 1);
        }
        return arr[idx] & 0xff;
    }

    @Override
    public short readShort(final long offset) throws IOException {
        return (short) readUnsignedShort(offset);
    }

    @Override
    public int readUnsignedShort(final long offset) throws IOException {
        final var idx = (int) offset;
        if (idx + 2 > arrUsed) {
            readTo(idx + 2);
        }
        return ((arr[idx] & 0xff) << 8) //
                | (arr[idx + 1] & 0xff);
    }

    @Override
    public int readInt(final long offset) throws IOException {
        final var idx = (int) offset;
        if (idx + 4 > arrUsed) {
            readTo(idx + 4);
        }
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
        final var idx = (int) offset;
        if (idx + 8 > arrUsed) {
            readTo(idx + 8);
        }
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
            // The number of bytes to skip is the length of a part of the classfile that is not of interest, read
            // from the classfile itself, so a negative value is a corrupt classfile rather than a caller error: an
            // attribute length is an unsigned 32-bit value, and one larger than 2GB reads back as a negative int
            throw new IOException("Tried to skip a negative number of bytes");
        }
        // The target position is computed in long arithmetic, because a length close to 2GB read from a corrupt
        // classfile would otherwise wrap the position negative, and the read after it would be made outside the
        // buffer rather than being rejected here
        final var targetIdx = currIdx + (long) bytesToSkip;
        if (targetIdx > arrUsed) {
            if (targetIdx > FileUtils.MAX_BUFFER_SIZE) {
                throw new IOException("Tried to skip past the 2GB limit");
            }
            readTo((int) targetIdx);
        }
        currIdx = (int) targetIdx;
    }

    @Override
    public String readString(final long offset, final int numBytes, final boolean replaceSlashWithDot,
            final boolean stripLSemicolon) throws IOException {
        final var idx = (int) offset;
        if (idx + numBytes > arrUsed) {
            readTo(idx + numBytes);
        }
        return StringUtils.readString(arr, idx, numBytes, replaceSlashWithDot, stripLSemicolon);
    }

    @Override
    public String readString(final int numBytes, final boolean replaceSlashWithDot, final boolean stripLSemicolon)
            throws IOException {
        // Delegate to the random access overload, as the other sequential read methods do, so that the buffer is
        // grown to cover the requested bytes first. Reading straight out of arr would silently return whatever
        // happened to be in the buffer past arrUsed.
        final var val = readString(currIdx, numBytes, replaceSlashWithDot, stripLSemicolon);
        currIdx += numBytes;
        return val;
    }

    @Override
    public String readString(final long offset, final int numBytes) throws IOException {
        return readString(offset, numBytes, false, false);
    }

    @Override
    public String readString(final int numBytes) throws IOException {
        return readString(numBytes, false, false);
    }

    @Override
    public void close() {
        // Only the inflater stream that this reader opened on a deflated slice is closed here. Everything else the
        // reader was given -- a slice, or a stream the caller opened -- belongs to the caller, which closes it.
        try {
            final var inflaterInputStream = this.inflaterInputStream;
            if (ownsInputStream && inflaterInputStream != null) {
                inflaterInputStream.close();
            }
        } catch (final IOException e) {
            // Ignore
        } finally {
            this.inflaterInputStream = null;
        }
    }
}
