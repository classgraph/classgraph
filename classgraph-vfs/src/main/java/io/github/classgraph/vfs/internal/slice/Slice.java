
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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.internal.InputStreamUtils;
import io.github.classgraph.vfs.internal.TempFile;
import io.github.classgraph.vfs.reader.RandomAccessReader;
import org.jspecify.annotations.Nullable;

/**
 * A slice of a file ({@link PathSlice}) or of a byte array ({@link ArraySlice}). The content of an
 * {@link InputStream} is read into one or the other by {@link #fromInputStream}.
 *
 * <p>
 * A slice is a range of bytes, and gives the same bytes whichever way it is read. It does not decode what it holds:
 * the slice of a deflated zip entry holds the entry's compressed bytes, and the entry is inflated by
 * {@code FastZipEntry}.
 *
 * <p>
 * A {@link Slice} may be shared between threads -- a zipfile's slice is read concurrently by all the threads
 * scanning that zipfile. It is the objects obtained <i>from</i> a slice that are single-threaded: each call to
 * {@link #randomAccessReader()} or {@link #open()} returns a new reader or stream with its own read position and
 * scratch buffers, and each of those must be used by only one thread.
 */
public abstract class Slice implements AutoCloseable {
    /**
     * The maximum size of a byte array that the content of a slice can be loaded into. Eight bytes smaller than
     * {@link Integer#MAX_VALUE}, since some VMs reserve header words in arrays.
     */
    public static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    /** The size of the buffer to read into when the length of the content is not known ahead of time. */
    private static final int DEFAULT_BUFFER_SIZE = 16384;

    /** The largest buffer that a length hint is allowed to allocate up front. */
    private static final int MAX_INITIAL_BUFFER_SIZE = 16 * 1024 * 1024;

    /** The {@link Vfs} that opened this slice, which every slice reaches its shared settings and pools through. */
    protected final Vfs vfs;

    /** The parent slice, or null if this is a toplevel slice. */
    protected final @Nullable Slice parentSlice;

    /** The start position of the slice. */
    public final long sliceStartPos;

    /** The length of the slice. Never negative -- the constructor rejects a negative length. */
    public final long sliceLength;

    /** The cached hashCode. */
    private int hashCode;

    /**
     * Constructor for treating a range of a slice as a sub-slice.
     *
     * @param parentSlice
     *            the parent slice, or null if this is a toplevel slice
     * @param offset
     *            the offset of the sub-slice within the parent slice
     * @param length
     *            the length of the sub-slice
     * @param vfs
     *            the {@link Vfs} that opened this slice
     */
    protected Slice(final @Nullable Slice parentSlice, final long offset, final long length, final Vfs vfs) {
        this.parentSlice = parentSlice;
        final var parentSliceStartPos = parentSlice == null ? 0L : parentSlice.sliceStartPos;
        this.sliceStartPos = parentSliceStartPos + offset;
        this.sliceLength = length;
        this.vfs = vfs;

        if (offset < 0L || sliceStartPos < 0L) {
            throw new IllegalArgumentException("Invalid startPos");
        }
        if (length < 0L) {
            throw new IllegalArgumentException("Invalid length");
        }
        // Compare the length against the space left in the parent by subtraction, rather than comparing the end of
        // this slice against the end of the parent by addition -- a zip entry can claim a compressed size large
        // enough to overflow that addition, which would make an out-of-range slice look contained, and reading it
        // would then return bytes from beyond the end of the entry. Both operands here are non-negative, so the
        // subtraction cannot overflow.
        if (parentSlice != null && length > parentSlice.sliceLength - offset) {
            throw new IllegalArgumentException("Child slice is not completely contained within parent slice");
        }
    }

    /**
     * Constructor.
     *
     * @param length
     *            the length
     * @param vfs
     *            the {@link Vfs} that opened this slice
     */
    protected Slice(final long length, final Vfs vfs) {
        this(/* parentSlice = */ null, 0L, length, vfs);
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Read all the bytes of an {@link InputStream} into a {@link Slice}, spilling over to a temporary file on disk
     * if the content is too large to buffer in RAM.
     *
     * @param inputStream
     *            the {@link InputStream} to read from. Read to its end, but not closed: whoever opened the stream
     *            owns it, and one of the callers of this method is handed its stream by the user of the API.
     * @param tempFileBaseName
     *            the source URL or zip entry that inputStream was opened from (used to name the temporary file, if
     *            one is needed).
     * @param inputStreamLengthHint
     *            the length of inputStream if known, else -1L.
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @param log
     *            the log node, or null to skip logging
     * @return an {@link ArraySlice}, if the {@link InputStream} could be read into a byte array, otherwise a
     *         {@link PathSlice} over the temporary file that it was spilled to.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static Slice fromInputStream(final InputStream inputStream, final String tempFileBaseName,
            final long inputStreamLengthHint, final Vfs vfs, final @Nullable LogNode log) throws IOException {
        // The setting can be as large as Integer.MAX_VALUE, but the buffer must not grow past MAX_BUFFER_SIZE. Some
        // JVMs refuse a larger array with OutOfMemoryError ("Requested array size exceeds VM limit") however large
        // the heap is, and the jar would then not be read at all, instead of the rest of it being spilled to disk.
        // A known length longer than MAX_BUFFER_SIZE cannot fit in an array either, so it goes straight to disk.
        final var maxBufferedJarRAMSize = Math.min(vfs.getVfsSpec().getMaxBufferedJarRAMSize(), MAX_BUFFER_SIZE);
        if (inputStreamLengthHint > maxBufferedJarRAMSize) {
            // inputStreamLengthHint is longer than maxBufferedJarRAMSize, so immediately spill to disk
            return spillToDisk(inputStream, tempFileBaseName, /* prefix = */ null, vfs, log);
        }
        // inputStreamLengthHint is unknown (-1) or no longer than maxBufferedJarRAMSize, so read the stream into an
        // array, unless it turns out to be longer than maxBufferedJarRAMSize after all
        final var prefix = readUpTo(inputStream, inputStreamLengthHint, maxBufferedJarRAMSize);
        if (prefix.overflowByte() < 0) {
            return new ArraySlice(prefix.bytes(), vfs);
        }
        // The buffer is not allowed to grow any further, so the rest of the stream goes to disk, after what has
        // already been read from it
        return spillToDisk(inputStream, tempFileBaseName, prefix, vfs, log);
    }

    /**
     * The start of a stream, read into an array by {@link #readUpTo}.
     *
     * @param bytes
     *            the bytes read from the stream, in order. The array holds nothing else.
     * @param overflowByte
     *            the byte that was read after {@code bytes} had reached the largest size allowed, or -1 if the
     *            stream ended first, in which case {@code bytes} holds the whole of the stream.
     */
    private record StreamPrefix(byte[] bytes, int overflowByte) {
    }

    /**
     * Read a stream into an array, until the end of the stream, or until the array has reached a given size and
     * there is more of the stream to read.
     *
     * <p>
     * A known length is allocated up front, since it is usually right, but only up to
     * {@link #MAX_INITIAL_BUFFER_SIZE}: the length is only a hint, e.g. the uncompressed size that a zip entry
     * claims, which can be anything, and a zipfile must not be able to make the reader allocate more memory than
     * its content needs just by claiming that an entry is enormous. A length that is unknown, or that is zero and
     * so may or may not be right, starts at {@link #DEFAULT_BUFFER_SIZE}. Either way the array is doubled from
     * there as it fills, so that reading a small stream does not allocate the whole of {@code maxSize}.
     *
     * <p>
     * The array is only grown once it is full, and a stream does not report that it has ended until it is asked for
     * a byte that it does not have, so a full array is followed by a read of a single byte, to find out whether
     * there is more of the stream before growing the array to hold it. That byte is the overflow byte if the array
     * cannot grow any further. A read into an array with room left in it goes through
     * {@link InputStreamUtils#readAtLeastOne}, so a stream that returns zero from such a read is neither taken to
     * have ended there, which would truncate the content, nor taken to have filled the array, which would grow the
     * array once per zero, up to {@code maxSize}, to hold a handful of bytes.
     *
     * @param inputStream
     *            the stream to read from. Not closed by this method.
     * @param lengthHint
     *            the length of the stream if known, else -1L.
     * @param maxSize
     *            the largest size that the array may grow to, which is at most {@link #MAX_BUFFER_SIZE}.
     * @return the bytes read, and the byte that was read past {@code maxSize}, if any.
     * @throws IOException
     *             if the stream could not be read.
     */
    private static StreamPrefix readUpTo(final InputStream inputStream, final long lengthHint, final int maxSize)
            throws IOException {
        var buf = new byte[lengthHint <= 0L ? Math.min(DEFAULT_BUFFER_SIZE, maxSize)
                : (int) Math.min(Math.min(lengthHint, MAX_INITIAL_BUFFER_SIZE), maxSize)];
        var bufBytesUsed = 0;
        for (;;) {
            if (bufBytesUsed == buf.length) {
                // The array is full, so find out whether the stream has ended before growing the array
                final var nextByte = inputStream.read();
                if (nextByte < 0) {
                    break;
                }
                if (buf.length >= maxSize) {
                    return new StreamPrefix(buf, nextByte);
                }
                // Double the size of the array (in long arithmetic, since the doubling can overflow an int)
                buf = Arrays.copyOf(buf, (int) Math.min(buf.length * 2L, maxSize));
                buf[bufBytesUsed++] = (byte) nextByte;
            } else {
                final var bytesRead = InputStreamUtils.readAtLeastOne(inputStream, buf, bufBytesUsed,
                        buf.length - bufBytesUsed);
                if (bytesRead < 0) {
                    break;
                }
                bufBytesUsed += bytesRead;
            }
        }
        // Trim the array to the length of the stream, if the length was unknown or overstated
        return new StreamPrefix(bufBytesUsed == buf.length ? buf : Arrays.copyOf(buf, bufBytesUsed),
                /* overflowByte = */ -1);
    }

    /**
     * Spill an {@link InputStream} to disk if the stream is too large to fit in RAM.
     *
     * @param inputStream
     *            The {@link InputStream}.
     * @param tempFileBaseName
     *            The stem to base the temporary filename on.
     * @param prefix
     *            The start of the stream that has already been read, which ran past the largest size allowed, to
     *            write to the beginning of the file, or null if nothing has been read from the stream.
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @param log
     *            The log.
     * @return the file slice
     * @throws IOException
     *             If anything went wrong creating or writing to the temp file.
     */
    private static PathSlice spillToDisk(final InputStream inputStream, final String tempFileBaseName,
            final @Nullable StreamPrefix prefix, final Vfs vfs, final @Nullable LogNode log) throws IOException {
        // Create temp file
        File tempFile;
        try {
            tempFile = TempFile.create(tempFileBaseName);
        } catch (final IOException e) {
            // Chain the cause, so that the reason the temporary file could not be created is reachable from the
            // stack trace
            throw new IOException("Could not create temporary file: " + e, e);
        }
        if (log != null) {
            log.log("Could not fit InputStream content into max RAM buffer size, saving to temporary file: "
                    + tempFileBaseName + " -> " + tempFile);
        }

        // From here on the temporary file has to be deleted by whatever fails, until it is handed to the PathSlice
        // that owns it -- nothing else knows it exists
        try {
            return spillToTempFile(inputStream, tempFile, prefix, vfs, log);
        } catch (final IOException | RuntimeException | Error e) {
            TempFile.delete(tempFile);
            throw e;
        }
    }

    /**
     * Write the content of an {@link InputStream} to a temporary file that has already been created, and open a
     * {@link PathSlice} that owns the file.
     *
     * @param inputStream
     *            The {@link InputStream}.
     * @param tempFile
     *            The temporary file to write to.
     * @param prefix
     *            The start of the stream that has already been read, which ran past the largest size allowed, to
     *            write to the beginning of the file, or null if nothing has been read from the stream.
     * @param vfs
     *            the {@link Vfs} that opened this slice
     * @param log
     *            The log.
     * @return the file slice, which owns the temporary file.
     * @throws IOException
     *             If anything went wrong writing to the temp file, or opening the slice over it.
     */
    private static PathSlice spillToTempFile(final InputStream inputStream, final File tempFile,
            final @Nullable StreamPrefix prefix, final Vfs vfs, final @Nullable LogNode log) throws IOException {
        // Copy everything read so far and the rest of the InputStream to the temporary file
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            // Write the bytes that have already been read, including the one read past the end of the buffer
            if (prefix != null) {
                outputStream.write(prefix.bytes());
                outputStream.write(prefix.overflowByte());
            }
            // Copy the rest of the InputStream to the file. This does not use InputStream#transferTo, which reads
            // forever from a stream that returns zero at its end rather than -1.
            final var copyBuf = new byte[DEFAULT_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = InputStreamUtils.readAtLeastOne(inputStream, copyBuf, 0, copyBuf.length)) > 0) {
                outputStream.write(copyBuf, 0, bytesRead);
            }
        }

        // Return a new PathSlice that owns the temporary file, and deletes it when it is closed
        return PathSlice.forTempFile(tempFile, vfs, log);
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Get a child {@link Slice} from this parent {@link Slice}. The child slice must be completely contained within
     * the parent slice, and may be the whole of it.
     *
     * @param offset
     *            The offset to start slicing from, relative to this parent slice's start position.
     * @param length
     *            The length of the slice.
     * @return The child slice.
     */
    public abstract Slice slice(long offset, long length);

    /**
     * Open this {@link Slice} as an {@link InputStream}.
     *
     * @return the input stream
     * @throws IOException
     *             if this slice has been closed.
     */
    public InputStream open() throws IOException {
        return open(null);
    }

    /**
     * Open this {@link Slice} as an {@link InputStream}.
     *
     * @param resourceToClose
     *            the {@link AutoCloseable} to close when the returned {@code InputStream} is closed, or null if
     *            none.
     * @return the input stream
     * @throws IOException
     *             if this slice has been closed, in which case resourceToClose is closed too.
     */
    public InputStream open(final @Nullable AutoCloseable resourceToClose) throws IOException {
        try {
            return new SliceInputStream(this, resourceToClose);
        } catch (final IOException | RuntimeException | Error e) {
            // The caller never sees a stream if this throws, so resourceToClose, which the caller has already
            // handed over, has to be closed here
            if (resourceToClose != null) {
                try {
                    resourceToClose.close();
                } catch (final Exception e2) {
                    e.addSuppressed(e2);
                }
            }
            throw e;
        }
    }

    /**
     * Create a new {@link RandomAccessReader} for this {@link Slice}.
     *
     * @return the random access reader
     * @throws IOException
     *             if this slice has been closed, so that there is nothing left to read through.
     */
    public abstract RandomAccessReader randomAccessReader() throws IOException;

    /**
     * Load the slice as a byte array.
     *
     * @return the content of the slice
     * @throws IOException
     *             if the slice could not be read.
     */
    public abstract byte[] load() throws IOException;

    /**
     * Read an {@link InputStream} to its end, into a byte array, for content that can only be reached by streaming
     * it, such as a deflated zip entry, which has to be inflated.
     *
     * @param inputStream
     *            The {@link InputStream}. Closed by this method.
     * @param uncompressedLengthHint
     *            The length of the data once inflated from the {@link InputStream}, if known, otherwise -1L.
     * @return The contents of the {@link InputStream} as a byte array.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static byte[] readAllBytesAsArray(final InputStream inputStream, final long uncompressedLengthHint)
            throws IOException {
        try (inputStream) {
            if (uncompressedLengthHint > MAX_BUFFER_SIZE) {
                throw new IOException("InputStream is too large to read");
            }
            final var prefix = readUpTo(inputStream, uncompressedLengthHint, MAX_BUFFER_SIZE);
            if (prefix.overflowByte() >= 0) {
                throw new IOException("InputStream too large to read into array");
            }
            return prefix.bytes();
        }
    }

    /**
     * Load the slice as a string.
     *
     * @return the content of the slice, decoded as UTF-8
     * @throws IOException
     *             if slice cannot be read.
     */
    public String loadAsString() throws IOException {
        return new String(load(), StandardCharsets.UTF_8);
    }

    /**
     * Read the slice into a {@link ByteBuffer}.
     *
     * <p>
     * The returned buffer starts at position zero, has a capacity equal to the length of the slice, and is
     * read-only, so that it cannot reach or overwrite anything outside the slice. Use {@link #load()} instead to
     * obtain the content of the slice as a byte array that the caller owns and may modify.
     *
     * @return the byte buffer
     * @throws IOException
     *             if the slice could not be read.
     */
    public ByteBuffer read() throws IOException {
        return ByteBuffer.wrap(load()).asReadOnlyBuffer();
    }

    @Override
    public void close() throws IOException {
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            final var parent = parentSlice;
            hashCode = parent == null ? System.identityHashCode(this)
                    : parent.hashCode() ^ ((int) sliceStartPos * 7) ^ ((int) sliceLength * 15);
            if (hashCode == 0) {
                hashCode = 1;
            }
        }
        return hashCode;
    }

    /**
     * A child slice is equal to another child slice of the same parent slice that spans the same range of it. A
     * toplevel slice is only equal to itself, since it owns the resource it reads through -- two toplevel slices of
     * the same file are two separate handles on that file, and closing one of them does not close the other.
     */
    @Override
    public boolean equals(final @Nullable Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof final Slice other) || this.parentSlice == null) {
            return false;
        } else {
            return this.parentSlice == other.parentSlice && this.sliceStartPos == other.sliceStartPos
                    && this.sliceLength == other.sliceLength;
        }
    }
}
