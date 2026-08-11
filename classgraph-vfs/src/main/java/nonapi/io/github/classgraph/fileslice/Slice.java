
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
package nonapi.io.github.classgraph.fileslice;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;
import nonapi.io.github.classgraph.utils.LogNode;
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
public abstract class Slice implements Closeable {
    /** The resources owned by the scan that opened this slice. */
    protected final ScanResources scanResources;

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
     * @param scanResources
     *            the resources owned by the scan
     */
    protected Slice(final @Nullable Slice parentSlice, final long offset, final long length,
            final boolean isDeflatedZipEntry, final long inflatedLengthHint, final ScanResources scanResources) {
        this.parentSlice = parentSlice;
        final var parentSliceStartPos = parentSlice == null ? 0L : parentSlice.sliceStartPos;
        this.sliceStartPos = parentSliceStartPos + offset;
        this.sliceLength = length;
        this.isDeflatedZipEntry = isDeflatedZipEntry;
        this.inflatedLengthHint = inflatedLengthHint;
        this.scanResources = scanResources;

        if (sliceStartPos < 0L) {
            throw new IllegalArgumentException("Invalid startPos");
        }
        if (length < 0L) {
            throw new IllegalArgumentException("Invalid length");
        }
        if (parentSlice != null && (sliceStartPos < parentSliceStartPos
                || sliceStartPos + length > parentSliceStartPos + parentSlice.sliceLength)) {
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
     * @param scanResources
     *            the resources owned by the scan
     */
    protected Slice(final long length, final boolean isDeflatedZipEntry, final long inflatedLengthHint,
            final ScanResources scanResources) {
        this(/* parentSlice = */ null, 0L, length, isDeflatedZipEntry, inflatedLengthHint, scanResources);
    }

    // ---------------------------------------------------------------------------------------------------------

    /**
     * Read all the bytes of an {@link InputStream} into a {@link Slice}, spilling over to a temporary file on disk
     * if the content is too large to buffer in RAM.
     *
     * @param inputStream
     *            the {@link InputStream} to read from.
     * @param tempFileBaseName
     *            the source URL or zip entry that inputStream was opened from (used to name the temporary file, if
     *            one is needed).
     * @param inputStreamLengthHint
     *            the length of inputStream if known, else -1L.
     * @param scanResources
     *            the resources owned by the scan
     * @param log
     *            the log node, or null to skip logging
     * @return an {@link ArraySlice}, if the {@link InputStream} could be read into a byte array, otherwise a
     *         {@link FileSlice} over the temporary file that it was spilled to.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static Slice fromInputStream(final InputStream inputStream, final String tempFileBaseName,
            final long inputStreamLengthHint, final ScanResources scanResources, final @Nullable LogNode log)
            throws IOException {
        final var maxBufferedJarRAMSize = scanResources.vfsScanSpec.maxBufferedJarRAMSize;
        try (inputStream) {
            if (inputStreamLengthHint <= maxBufferedJarRAMSize) {
                // inputStreamLengthHint is unknown (-1) or shorter than vfsScanSpec.maxBufferedJarRAMSize, so try
                // reading from the InputStream into an array of size vfsScanSpec.maxBufferedJarRAMSize or
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
                    // byte) to see if inputStreamHint underestimated the actual length of the stream
                    final var overflowBuf = new byte[1];
                    final var overflowBufBytesUsed = inputStream.read(overflowBuf, 0, 1);
                    if (overflowBufBytesUsed == 1) {
                        // We were able to read one more byte, so we're still not at the end of the stream, and we
                        // need to spill to disk, because buf is full
                        return spillToDisk(inputStream, tempFileBaseName, buf, overflowBuf, scanResources, log);
                    }
                    // else (overflowBufBytesUsed == -1), so reached the end of the stream => don't spill to disk
                }
                // Successfully reached end of stream
                if (bufBytesUsed < buf.length) {
                    // Trim array if needed (this is needed if inputStreamLengthHint was -1, or overestimated the
                    // length of the InputStream)
                    buf = Arrays.copyOf(buf, bufBytesUsed);
                }
                // Return buf as new ArraySlice
                return new ArraySlice(buf, /* isDeflatedZipEntry = */ false, /* inflatedSizeHint = */ 0L,
                        scanResources);

            }
            // inputStreamLengthHint is longer than vfsScanSpec.maxBufferedJarRAMSize, so immediately spill to disk
            return spillToDisk(inputStream, tempFileBaseName, /* buf = */ null, /* overflowBuf = */ null,
                    scanResources, log);
        }
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
     * @param overflowBuf
     *            The second buffer to write to the beginning of the file, or null if none. (Should have same
     *            nullity as buf.)
     * @param scanResources
     *            the resources owned by the scan
     * @param log
     *            The log.
     * @return the file slice
     * @throws IOException
     *             If anything went wrong creating or writing to the temp file.
     */
    private static FileSlice spillToDisk(final InputStream inputStream, final String tempFileBaseName,
            final byte @Nullable [] buf, final byte @Nullable [] overflowBuf, final ScanResources scanResources,
            final @Nullable LogNode log) throws IOException {
        // Create temp file
        File tempFile;
        try {
            tempFile = scanResources.makeTempFile(tempFileBaseName, /* onlyUseLeafname = */ true);
        } catch (final IOException e) {
            throw new IOException("Could not create temporary file: " + e.getMessage());
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
                outputStream.write(buf);
                outputStream.write(overflowBuf);
            }
            // Copy the rest of the InputStream to the file
            final var copyBuf = new byte[8192];
            for (int bytesRead; (bytesRead = inputStream.read(copyBuf, 0, copyBuf.length)) > 0;) {
                outputStream.write(copyBuf, 0, bytesRead);
            }
        }

        // Return a new FileSlice for the temporary file
        return new FileSlice(tempFile, scanResources, log);
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
     *            the {@link Closeable} to close when the returned {@code InputStream} is closed, or null if none.
     * @return the input stream
     * @throws IOException
     *             if an inflater cannot be created for this {@link Slice}.
     */
    public InputStream open(final @Nullable Closeable resourceToClose) throws IOException {
        final InputStream rawInputStream = new SliceInputStream(this, resourceToClose);
        return isDeflatedZipEntry ? scanResources.openInflaterInputStream(rawInputStream) : rawInputStream;
    }

    /**
     * Create a new {@link RandomAccessReader} for this {@link Slice}.
     *
     * @return the random access reader
     */
    public abstract RandomAccessReader randomAccessReader();

    /**
     * Load the slice as a byte array.
     *
     * @return the byte[]
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public abstract byte[] load() throws IOException;

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
     * @return the byte buffer
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    public ByteBuffer read() throws IOException {
        return ByteBuffer.wrap(load());
    }

    @Override
    public void close() throws IOException {
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = (parentSlice == null ? 1 : parentSlice.hashCode()) ^ ((int) sliceStartPos * 7)
                    ^ ((int) sliceLength * 15);
            if (hashCode == 0) {
                hashCode = 1;
            }
        }
        return hashCode;
    }

    @Override
    public boolean equals(final @Nullable Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof final Slice other)) {
            return false;
        } else {
            return this.parentSlice == other.parentSlice && this.sliceStartPos == other.sliceStartPos
                    && this.sliceLength == other.sliceLength;
        }
    }
}
