
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
import io.github.classgraph.vfs.internal.VfsSession;
import io.github.classgraph.vfs.internal.slice.reader.RandomAccessReader;
import org.jspecify.annotations.Nullable;

/**
 * A slice of a {@link File}, {@link ByteBuffer} or {@link InputStream}.
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

    /** The resources owned by the scan that opened this slice. */
    protected final VfsSession session;

    /** The parent slice, or null if this is a toplevel slice. */
    protected final @Nullable Slice parentSlice;

    /** The start position of the slice. */
    public final long sliceStartPos;

    /** The length of the slice. Never negative -- the constructor rejects a negative length. */
    public long sliceLength;

    /**
     * If true, the slice is a deflated zip entry, and needs to be inflated to access the content.
     */
    public final boolean isDeflatedZipEntry;

    /**
     * If the slice is a deflated zip entry, this is the expected uncompressed length, or -1L if unknown.
     */
    public final long inflatedLengthHint;

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
     * @param isDeflatedZipEntry
     *            true if this is a deflated zip entry
     * @param inflatedLengthHint
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 of this is not a deflated
     *            zip entry.
     * @param session
     *            the session that owns what is opened
     */
    protected Slice(final @Nullable Slice parentSlice, final long offset, final long length,
            final boolean isDeflatedZipEntry, final long inflatedLengthHint, final VfsSession session) {
        this.parentSlice = parentSlice;
        final var parentSliceStartPos = parentSlice == null ? 0L : parentSlice.sliceStartPos;
        this.sliceStartPos = parentSliceStartPos + offset;
        this.sliceLength = length;
        this.isDeflatedZipEntry = isDeflatedZipEntry;
        this.inflatedLengthHint = inflatedLengthHint;
        this.session = session;

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
     * @param isDeflatedZipEntry
     *            true if this is a deflated zip entry
     * @param inflatedLengthHint
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 of this is not a deflated
     *            zip entry.
     * @param session
     *            the session that owns what is opened
     */
    protected Slice(final long length, final boolean isDeflatedZipEntry, final long inflatedLengthHint,
            final VfsSession session) {
        this(/* parentSlice = */ null, 0L, length, isDeflatedZipEntry, inflatedLengthHint, session);
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
     * @param session
     *            the session that owns what is opened
     * @param log
     *            the log node, or null to skip logging
     * @return an {@link ArraySlice}, if the {@link InputStream} could be read into a byte array, otherwise a
     *         {@link FileSlice} over the temporary file that it was spilled to.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static Slice fromInputStream(final InputStream inputStream, final String tempFileBaseName,
            final long inputStreamLengthHint, final VfsSession session, final @Nullable LogNode log)
            throws IOException {
        final var maxBufferedJarRAMSize = session.vfsSpec.getMaxBufferedJarRAMSize();
        if (inputStreamLengthHint <= maxBufferedJarRAMSize) {
            // inputStreamLengthHint is unknown (-1) or shorter than maxBufferedJarRAMSize, so try
            // reading from the InputStream into an array of size maxBufferedJarRAMSize or
            // inputStreamLengthHint respectively. Also if inputStreamLengthHint == 0, which may or may not be
            // valid, use a buffer size of 16kB to avoid spilling to disk in case this is wrong but the file is
            // still small.
            final var bufSize = inputStreamLengthHint == -1L ? maxBufferedJarRAMSize
                    : inputStreamLengthHint == 0L ? 16384
                            : Math.min((int) inputStreamLengthHint, maxBufferedJarRAMSize);
            var buf = new byte[bufSize];
            final var bufLength = buf.length;

            var bufBytesUsed = 0;
            var bytesRead = 0;
            while ((bytesRead = inputStream.read(buf, bufBytesUsed, bufLength - bufBytesUsed)) > 0) {
                // Fill buffer until nothing more can be read
                bufBytesUsed += bytesRead;
            }
            if (bytesRead == 0) {
                // If bytesRead was zero rather than -1, we need to probe the InputStream (by reading one more
                // byte) to see if inputStreamHint underestimated the actual length of the stream. (The probe is
                // the single-byte InputStream#read, which returns either a byte value or -1 for the end of the
                // stream -- a probe through InputStream#read(byte[], int, int) could return zero, which is what
                // made the probe necessary in the first place, and the stream would be truncated here.)
                final var overflowByte = inputStream.read();
                if (overflowByte != -1) {
                    // We were able to read one more byte, so we're still not at the end of the stream, and we
                    // need to spill to disk, because buf is full
                    return spillToDisk(inputStream, tempFileBaseName, buf, bufBytesUsed,
                            new byte[] { (byte) overflowByte }, session, log);
                }
                // else reached the end of the stream => don't spill to disk
            }
            // Successfully reached end of stream
            if (bufBytesUsed < buf.length) {
                // Trim array if needed (this is needed if inputStreamLengthHint was -1, or overestimated the
                // length of the InputStream)
                buf = Arrays.copyOf(buf, bufBytesUsed);
            }
            // Return buf as new ArraySlice
            return new ArraySlice(buf, /* isDeflatedZipEntry = */ false, /* inflatedSizeHint = */ 0L, session);

        }
        // inputStreamLengthHint is longer than maxBufferedJarRAMSize, so immediately spill to disk
        return spillToDisk(inputStream, tempFileBaseName, /* buf = */ null, /* bufBytesUsed = */ 0,
                /* overflowBuf = */ null, session, log);
    }

    /**
     * Spill an {@link InputStream} to disk if the stream is too large to fit in RAM.
     *
     * @param inputStream
     *            The {@link InputStream}.
     * @param tempFileBaseName
     *            The stem to base the temporary filename on.
     * @param buf
     *            The first buffer to write to the beginning of the file, or null if none.
     * @param bufBytesUsed
     *            The number of bytes of {@code buf} that were filled.
     * @param overflowBuf
     *            The second buffer to write to the beginning of the file, or null if none. (Should have same
     *            nullity as buf.)
     * @param session
     *            the session that owns what is opened
     * @param log
     *            The log.
     * @return the file slice
     * @throws IOException
     *             If anything went wrong creating or writing to the temp file.
     */
    private static FileSlice spillToDisk(final InputStream inputStream, final String tempFileBaseName,
            final byte @Nullable [] buf, final int bufBytesUsed, final byte @Nullable [] overflowBuf,
            final VfsSession session, final @Nullable LogNode log) throws IOException {
        // Create temp file
        File tempFile;
        try {
            tempFile = session.makeTempFile(tempFileBaseName, /* onlyUseLeafname = */ true);
        } catch (final IOException e) {
            // Chain the cause, so that the reason the temporary file could not be created is reachable from the
            // stack trace
            throw new IOException("Could not create temporary file: " + e, e);
        }
        if (log != null) {
            log.log("Could not fit InputStream content into max RAM buffer size, saving to temporary file: "
                    + tempFileBaseName + " -> " + tempFile);
        }

        // Copy everything read so far and the rest of the InputStream to the temporary file
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            // Write already-read buffered bytes to temp file, if anything was read (buf and overflowBuf always have
            // the same nullity)
            if (buf != null && overflowBuf != null) {
                outputStream.write(buf, 0, bufBytesUsed);
                outputStream.write(overflowBuf);
            }
            // Copy the rest of the InputStream to the file. (This is InputStream#transferTo rather than a copy
            // loop of its own, because a stream that returns zero from a read of a non-empty buffer would end a
            // copy loop early, silently truncating the file, whereas transferTo keeps reading until the end of
            // the stream is reached.)
            inputStream.transferTo(outputStream);
        }

        // Return a new FileSlice for the temporary file
        return new FileSlice(tempFile, session, log);
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Get a child {@link Slice} from this parent {@link Slice}. The child slice must be smaller than the parent
     * slice, and completely contained within it.
     *
     * @param offset
     *            The offset to start slicing from, relative to this parent slice's start position.
     * @param length
     *            The length of the slice.
     * @param isDeflatedZipEntry
     *            true if this is a deflated zip entry
     * @param inflatedLengthHint
     *            the uncompressed size of a deflated zip entry, or -1 if unknown, or 0 of this is not a deflated
     *            zip entry.
     * @return The child slice.
     */
    public abstract Slice slice(long offset, long length, boolean isDeflatedZipEntry,
            final long inflatedLengthHint);

    /**
     * Open this {@link Slice} as an {@link InputStream}.
     *
     * @return the input stream
     * @throws IOException
     *             if an inflater cannot be created for this {@link Slice}.
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
     *             if an inflater cannot be created for this {@link Slice}.
     */
    public InputStream open(final @Nullable AutoCloseable resourceToClose) throws IOException {
        InputStream rawInputStream = null;
        try {
            rawInputStream = new SliceInputStream(this, resourceToClose);
            if (!isDeflatedZipEntry) {
                return rawInputStream;
            }
            return session.openInflaterInputStream(rawInputStream);
        } catch (final IOException | RuntimeException | Error e) {
            // The caller never sees a stream if this throws, so whatever was opened has to be closed here, and with
            // it resourceToClose, which the caller has already handed over
            final AutoCloseable toClose = rawInputStream == null ? resourceToClose : rawInputStream;
            if (toClose != null) {
                try {
                    toClose.close();
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
     * Take a view of the memory mapping of this slice, if the file is memory-mapped, so that the file is not
     * unmapped while the caller can still read a {@link ByteBuffer} that {@link #read()} returned -- see
     * {@link FileMapping#unmap()}. A slice that is not memory-mapped has no view to take, and returns a release
     * action that does nothing.
     *
     * @return the action that releases the view, which the caller must run once it can no longer read the buffer.
     * @throws IOException
     *             if the file has already been unmapped, so that there is nothing left to read.
     */
    // #939
    public Runnable acquireMappingView() throws IOException {
        return () -> {
            // Nothing to release
        };
    }

    /**
     * Load the slice as a byte array.
     *
     * @return the byte[]
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public abstract byte[] load() throws IOException;

    /**
     * Read an {@link InputStream} to its end, into a byte array, for a {@link #load()} implementation that has no
     * faster way to reach the content of its slice than to stream it.
     *
     * @param inputStream
     *            The {@link InputStream}. Closed by this method.
     * @param uncompressedLengthHint
     *            The length of the data once inflated from the {@link InputStream}, if known, otherwise -1L.
     * @return The contents of the {@link InputStream} as a byte array.
     * @throws IOException
     *             If the contents could not be read.
     */
    static byte[] readAllBytesAsArray(final InputStream inputStream, final long uncompressedLengthHint)
            throws IOException {
        if (uncompressedLengthHint > MAX_BUFFER_SIZE) {
            throw new IOException("InputStream is too large to read");
        }
        try (inputStream) {
            final var bufferSize = uncompressedLengthHint < 1L
                    // If fileSizeHint is zero or unknown, use default buffer size
                    ? DEFAULT_BUFFER_SIZE
                    // fileSizeHint is just a hint -- limit the max allocated buffer size, so that invalid ZipEntry
                    // lengths do not become a memory allocation attack vector
                    : Math.min((int) uncompressedLengthHint, MAX_INITIAL_BUFFER_SIZE);
            var buf = new byte[bufferSize];
            var totBytesRead = 0;
            for (int bytesRead;;) {
                while ((bytesRead = inputStream.read(buf, totBytesRead, buf.length - totBytesRead)) > 0) {
                    // Fill buffer until nothing more can be read
                    totBytesRead += bytesRead;
                }
                if (bytesRead < 0) {
                    // Reached end of stream without filling buf
                    break;
                }

                // bytesRead == 0: either the buffer was the correct size and the end of the stream has been
                // reached, or the buffer was too small. Need to try reading one more byte to see which is the case.
                final var extraByte = inputStream.read();
                if (extraByte == -1) {
                    // Reached end of stream
                    break;
                }

                // Haven't reached end of stream yet. Need to grow the buffer (double its size), and append the
                // extra byte that was just read.
                if (buf.length == MAX_BUFFER_SIZE) {
                    throw new IOException("InputStream too large to read into array");
                }
                buf = Arrays.copyOf(buf, (int) Math.min(buf.length * 2L, MAX_BUFFER_SIZE));
                buf[totBytesRead++] = (byte) extraByte;
            }
            // Return buffer and number of bytes read
            return totBytesRead == buf.length ? buf : Arrays.copyOf(buf, totBytesRead);
        }
    }

    /**
     * Load the slice as a string.
     *
     * @return the string
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
     *             Signals that an I/O exception has occurred.
     */
    public ByteBuffer read() throws IOException {
        return ByteBuffer.wrap(load()).asReadOnlyBuffer();
    }

    /**
     * Register this slice with its session, so that the session closes it when the session is closed. Call this as
     * the last statement of the constructor of a toplevel slice, once every field it needs to close itself has been
     * assigned. Registration is rejected if the session has already been closed, since the session teardown has
     * already passed this slice by -- the constructor has to close the slice itself in that case, as it does for
     * any other failure after it took the file handle, since nothing else would ever release it.
     *
     * @throws IOException
     *             if the session has already been closed.
     */
    protected final void registerAsOpen() throws IOException {
        session.markSliceAsOpen(this);
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
