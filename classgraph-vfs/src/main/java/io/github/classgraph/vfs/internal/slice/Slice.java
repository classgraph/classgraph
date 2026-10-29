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

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.internal.InputStreamUtils;
import io.github.classgraph.vfs.internal.TempFile;
import io.github.classgraph.vfs.reader.RandomAccessReader;

/**
 * A raw byte range within a file ({@link PathSlice}) or byte array ({@link ArraySlice}).
 *
 * <p>
 * A {@link Slice} is thread-safe for concurrent access, whereas each {@link RandomAccessReader} or
 * {@link InputStream} returned by {@link #randomAccessReader()} or {@link #open()} maintains its own state and is
 * single-threaded.
 */
public abstract class Slice implements AutoCloseable {
    /**
     * The maximum size of a byte array into which a slice can be loaded ({@code Integer.MAX_VALUE - 8}).
     */
    public static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    /**
     * Initial buffer size when reading a stream of unknown length, and the least that the buffer grows to once a
     * stream runs on past the length it was said to have.
     */
    private static final int DEFAULT_BUFFER_SIZE = 65536;

    /** Maximum initial buffer size allocated up front from a stream length hint. */
    private static final int MAX_INITIAL_BUFFER_SIZE = 16 * 1024 * 1024;

    /** The {@link Vfs} that opened this slice. */
    protected final Vfs vfs;

    /** The parent slice, or null if this is a toplevel slice. */
    protected final @Nullable Slice parentSlice;

    /** The start position of the slice within the toplevel backing store. */
    public final long sliceStartPos;

    /** The non-negative length of the slice in bytes. */
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
     * Read all bytes of an {@link InputStream} into a {@link Slice}, spilling to a temporary file on disk if the
     * stream exceeds {@link io.github.classgraph.vfs.VfsSpec#getMaxBufferedJarRAMSize()}.
     *
     * @param inputStream
     *            the {@link InputStream} to read from (read to EOF, not closed by this method).
     * @param tempFileBaseName
     *            the source URL or zip entry path from which to derive a temporary filename if needed.
     * @param inputStreamLengthHint
     *            the expected length of {@code inputStream}, or -1L if unknown.
     * @param vfs
     *            the {@link Vfs} that opened this slice.
     * @param log
     *            the log node, or null to skip logging.
     * @return an {@link ArraySlice} if buffered in RAM, or a {@link PathSlice} over the temporary file if spilled
     *         to disk.
     * @throws IOException
     *             if the stream could not be read or spilled to disk.
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
        // Each block read counts as progress, so that the threads waiting for this one, for example to open the
        // same jarfile, do not time out while a slow download is still arriving
        final var prefix = readUpTo(inputStream, inputStreamLengthHint, maxBufferedJarRAMSize,
                vfs.interruptionChecker());
        if (prefix.overflowByte() < 0) {
            return new ArraySlice(prefix.bytes(), vfs);
        }
        // The buffer is not allowed to grow any further, so the rest of the stream goes to disk, after what has
        // already been read from it
        return spillToDisk(inputStream, tempFileBaseName, prefix, vfs, log);
    }

    /**
     * Prefix bytes read from a stream by {@link #readUpTo}.
     *
     * @param bytes
     *            the bytes read from the stream.
     * @param overflowByte
     *            the first byte read past {@code maxSize}, or -1 if the stream ended within {@code maxSize}.
     */
    private record StreamPrefix(byte[] bytes, int overflowByte) {
    }

    /**
     * Read from {@code inputStream} into a byte array until EOF or until {@code maxSize} bytes have been read and
     * at least one additional byte remains.
     *
     * @param inputStream
     *            the stream to read from (not closed by this method).
     * @param lengthHint
     *            the expected stream length, or -1L if unknown.
     * @param maxSize
     *            the maximum buffer size (at most {@link #MAX_BUFFER_SIZE}).
     * @param interruptionChecker
     *            the {@link InterruptionChecker} to notify of read progress, or null if none.
     * @return the bytes read and the first overflow byte past {@code maxSize} (or -1 if EOF was reached).
     * @throws IOException
     *             if the stream could not be read.
     */
    private static StreamPrefix readUpTo(final InputStream inputStream, final long lengthHint, final int maxSize,
            final @Nullable InterruptionChecker interruptionChecker) throws IOException {
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
                if (interruptionChecker != null) {
                    interruptionChecker.progress();
                }
                if (buf.length >= maxSize) {
                    return new StreamPrefix(buf, nextByte);
                }
                // Grow the array to the expected length if the stream has not reached it yet (the array starts out
                // smaller than a long expected length, so that a length that is wrong cannot make it allocate a
                // large array up front), or else double its size (in long arithmetic, since the doubling can
                // overflow an int). A stream that has run on past its expected length has shown that the length
                // was wrong, so the array grows to at least DEFAULT_BUFFER_SIZE rather than doubling from a length
                // that may have been only a few bytes.
                buf = Arrays.copyOf(buf, (int) Math.min(
                        lengthHint > buf.length ? lengthHint : Math.max(buf.length * 2L, DEFAULT_BUFFER_SIZE),
                        maxSize));
                buf[bufBytesUsed++] = (byte) nextByte;
            } else {
                final var bytesRead = InputStreamUtils.readAtLeastOne(inputStream, buf, bufBytesUsed,
                        buf.length - bufBytesUsed);
                if (bytesRead < 0) {
                    break;
                }
                bufBytesUsed += bytesRead;
                if (interruptionChecker != null) {
                    interruptionChecker.progress();
                }
            }
        }
        // Trim the array to the length of the stream, if the length was unknown or overstated
        return new StreamPrefix(bufBytesUsed == buf.length ? buf : Arrays.copyOf(buf, bufBytesUsed),
                /* overflowByte = */ -1);
    }

    /**
     * Spill an {@link InputStream} (and any already-read prefix) to a temporary file and return a {@link PathSlice}
     * that owns the file.
     *
     * @param inputStream
     *            the {@link InputStream}.
     * @param tempFileBaseName
     *            the path or name from which to derive the temporary filename.
     * @param prefix
     *            the prefix bytes already read from the stream, or null if none.
     * @param vfs
     *            the {@link Vfs} that opened this slice.
     * @param log
     *            the log node, or null to skip logging.
     * @return the {@link PathSlice} over the temporary file.
     * @throws IOException
     *             if the temporary file could not be created, written, or opened.
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
            if (!TempFile.delete(tempFile)) {
                TempFile.deleteOnExit(tempFile);
            }
            throw e;
        }
    }

    /**
     * Write {@code prefix} (if non-null) and the remainder of {@code inputStream} to {@code tempFile}, then open a
     * {@link PathSlice} that owns {@code tempFile}.
     *
     * @param inputStream
     *            the {@link InputStream}.
     * @param tempFile
     *            the temporary file to write to.
     * @param prefix
     *            the prefix bytes already read from the stream, or null if none.
     * @param vfs
     *            the {@link Vfs} that opened this slice.
     * @param log
     *            the log node, or null to skip logging.
     * @return the {@link PathSlice} over the temporary file.
     * @throws IOException
     *             if writing to or opening the temporary file fails.
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
                // Each block written counts as progress, so that the threads waiting for this one do not time out
                // while a slow download is still arriving
                vfs.interruptionChecker().progress();
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
     *             if this slice has been closed.
     */
    public abstract RandomAccessReader randomAccessReader() throws IOException;

    /**
     * Acquire a view of the memory mapping of this slice, if memory-mapped, so that the file is not unmapped while
     * the caller is reading a {@link ByteBuffer} returned by {@link #read()}.
     *
     * @return the action that releases the view.
     * @throws IOException
     *             if the file has already been unmapped.
     */
    // #939. The throws clause is declared even though nothing here throws the exception, since PathSlice overrides
    // this method for a memory-mapped file, and throws it once the file has been unmapped
    @SuppressWarnings("unused")
    public Runnable acquireMappingView() throws IOException {
        return () -> {
            // Nothing to release
        };
    }

    /**
     * Load the slice as a byte array.
     *
     * @return the content of the slice
     * @throws IOException
     *             if the slice could not be read.
     */
    public abstract byte[] load() throws IOException;

    /**
     * Read an {@link InputStream} completely into a byte array and close the stream.
     *
     * @param inputStream
     *            the {@link InputStream} (closed by this method).
     * @param uncompressedLengthHint
     *            the expected uncompressed length of the stream, or -1L if unknown.
     * @return the stream contents as a byte array.
     * @throws IOException
     *             if the stream could not be read or exceeds {@link #MAX_BUFFER_SIZE}.
     */
    public static byte[] readAllBytesAsArray(final InputStream inputStream, final long uncompressedLengthHint)
            throws IOException {
        try (inputStream) {
            // An entry that says it is too long for an array is rejected before inflating up to 2GB of it. (A
            // length that is wrong cannot make readUpTo allocate more than MAX_INITIAL_BUFFER_SIZE up front either
            // way.)
            if (uncompressedLengthHint > MAX_BUFFER_SIZE) {
                throw new IOException("InputStream is too large to read");
            }
            final var prefix = readUpTo(inputStream, uncompressedLengthHint, MAX_BUFFER_SIZE,
                    /* interruptionChecker = */ null);
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
     * Read the slice into a read-only {@link ByteBuffer} starting at position zero with capacity equal to
     * {@link #sliceLength}.
     *
     * @return the read-only byte buffer
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
     * Two child slices are equal if they share the same parent slice, start position, and length; a toplevel slice
     * is equal only to itself.
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
