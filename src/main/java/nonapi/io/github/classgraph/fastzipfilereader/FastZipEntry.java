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
 * Copyright (c) 2019 Luke Hutchison
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
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import nonapi.io.github.classgraph.recycler.RecycleOnClose;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.VersionFinder;

/** A zip entry within a {@link LogicalZipFile}. */
public class FastZipEntry implements Comparable<FastZipEntry> {
    /** The parent logical zipfile. */
    final LogicalZipFile parentLogicalZipFile;

    /** The offset of the entry's local header, as an offset relative to the parent logical zipfile. */
    private final long locHeaderPos;

    /** The start offset of the entry's compressed data, as an absolute offset within the physical zipfile. */
    private long entryDataStartOffsetWithinPhysicalZipFile = -1L;

    /** The zip entry path. */
    public final String entryName;

    /** True if the zip entry is deflated; false if the zip entry is stored. */
    final boolean isDeflated;

    /** The compressed size of the zip entry, in bytes. */
    public final long compressedSize;

    /** The uncompressed size of the zip entry, in bytes. */
    public final long uncompressedSize;

    /** The last modified millis since the epoch, or 0L if it is unknown */
    private long lastModifiedTimeMillis;

    /** The last modified time in MSDOS format, if {@link FastZipEntry#lastModifiedTimeMillis} is 0L. */
    private final int lastModifiedTimeMSDOS;

    /** The last modified date in MSDOS format, if {@link FastZipEntry#lastModifiedTimeMillis} is 0L. */
    private final int lastModifiedDateMSDOS;

    /** The file attributes for this resource, or 0 if unknown */
    public final int fileAttributes;

    /**
     * The version code (&gt;= 9), or 8 for the base layer or a non-versioned jar (whether JDK 7 or 8 compatible).
     */
    final int version;

    /**
     * The unversioned entry name (i.e. entryName with "META_INF/versions/{versionInt}/" stripped)
     */
    public final String entryNameUnversioned;

    /** The nested jar handler. */
    private final NestedJarHandler nestedJarHandler;

    /** The {@link RecyclableInflater} instance wrapping recyclable {@link Inflater} instances. */
    private RecyclableInflater recyclableInflaterInstance;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     * 
     * @param parentLogicalZipFile
     *            The parent logical zipfile containing this entry.
     * @param locHeaderPos
     *            The offset of the LOC header for this entry within the parent logical zipfile.
     * @param entryName
     *            The name of the entry.
     * @param isDeflated
     *            True if the entry is deflated; false if the entry is stored.
     * @param compressedSize
     *            The compressed size of the entry.
     * @param uncompressedSize
     *            The uncompressed size of the entry.
     * @param nestedJarHandler
     *            The {@link NestedJarHandler}.
     * @param lastModifiedTimeMillis
     *            The last modified date/time in millis since the epoch, or 0L if unknown (in which case, the MSDOS
     *            time and date fields will be provided).
     * @param lastModifiedTimeMSDOS
     *            The last modified date, in MSDOS format, if lastModifiedMillis is 0L.
     * @param lastModifiedDateMSDOS
     *            The last modified date, in MSDOS format, if lastModifiedMillis is 0L.
     * @param fileAttributes
     *            The POSIX file attribute bits from the zip entry.
     */
    FastZipEntry(final LogicalZipFile parentLogicalZipFile, final long locHeaderPos, final String entryName,
            final boolean isDeflated, final long compressedSize, final long uncompressedSize,
            final NestedJarHandler nestedJarHandler, final long lastModifiedTimeMillis,
            final int lastModifiedTimeMSDOS, final int lastModifiedDateMSDOS, final int fileAttributes) {
        this.parentLogicalZipFile = parentLogicalZipFile;
        this.locHeaderPos = locHeaderPos;
        this.entryName = entryName;
        this.isDeflated = isDeflated;
        this.compressedSize = compressedSize;
        this.uncompressedSize = !isDeflated && uncompressedSize < 0 ? compressedSize : uncompressedSize;
        this.nestedJarHandler = nestedJarHandler;
        this.lastModifiedTimeMillis = lastModifiedTimeMillis;
        this.lastModifiedTimeMSDOS = lastModifiedTimeMSDOS;
        this.lastModifiedDateMSDOS = lastModifiedDateMSDOS;
        this.fileAttributes = fileAttributes;

        // Get multi-release jar version number, and strip any version prefix
        int entryVersion = 8;
        String entryNameWithoutVersionPrefix = entryName;
        if (entryName.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX)
                && entryName.length() > LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length() + 1) {
            // This is a multi-release jar path
            final int nextSlashIdx = entryName.indexOf('/', LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length());
            if (nextSlashIdx > 0) {
                // Get path after version number, i.e. strip "META-INF/versions/{versionInt}/" prefix
                final String versionStr = entryName.substring(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length(),
                        nextSlashIdx);
                // For multi-release jars, the version number has to be an int >= 9
                // Integer.parseInt() is slow, so this is a custom implementation (this is called many times
                // for large classpaths, and Integer.parseInt() was a bit of a bottleneck, surprisingly)
                int versionInt = 0;
                if (versionStr.length() < 6 && !versionStr.isEmpty()) {
                    for (int i = 0; i < versionStr.length(); i++) {
                        final char c = versionStr.charAt(i);
                        if (c < '0' || c > '9') {
                            versionInt = 0;
                            break;
                        }
                        if (versionInt == 0) {
                            versionInt = c - '0';
                        } else {
                            versionInt = versionInt * 10 + c - '0';
                        }
                    }
                }
                if (versionInt != 0) {
                    entryVersion = versionInt;
                }
                // Set version to 8 for out-of-range version numbers or invalid paths
                if (entryVersion < 9 || entryVersion > VersionFinder.JAVA_MAJOR_VERSION) {
                    entryVersion = 8;
                }
                if (entryVersion > 8) {
                    // Strip version path prefix
                    entryNameWithoutVersionPrefix = entryName.substring(nextSlashIdx + 1);
                    // For META-INF/versions/{versionInt}/META-INF/*, don't strip version prefix:
                    // "The intention is that the META-INF directory cannot be versioned."
                    // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-October/013954.html
                    if (entryNameWithoutVersionPrefix.startsWith(LogicalZipFile.META_INF_PATH_PREFIX)) {
                        entryVersion = 8;
                        entryNameWithoutVersionPrefix = entryName;
                    }
                }
            }
        }
        this.version = entryVersion;
        this.entryNameUnversioned = entryNameWithoutVersionPrefix;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Lazily find zip entry data start offset -- this is deferred until zip entry data needs to be read, in order
     * to avoid randomly seeking within zipfile for every entry as the central directory is read.
     *
     * @return the offset within the physical zip file of the entry's start offset.
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    long getEntryDataStartOffsetWithinPhysicalZipFile() throws IOException, InterruptedException {
        if (entryDataStartOffsetWithinPhysicalZipFile == -1L) {
            // Create zipfile slice reader for zip entry
            try (RecycleOnClose<ZipFileSliceReader, RuntimeException> zipFileSliceReaderRecycleOnClose = //
                    parentLogicalZipFile.zipFileSliceReaderRecycler.acquireRecycleOnClose()) {
                final ZipFileSliceReader headerReader = zipFileSliceReaderRecycleOnClose.get();
                // Check header magic
                if (headerReader.getInt(locHeaderPos) != 0x04034b50) {
                    throw new IOException("Zip entry has bad LOC header: " + entryName);
                }
                final long dataStartPos = locHeaderPos + 30 + headerReader.getShort(locHeaderPos + 26)
                        + headerReader.getShort(locHeaderPos + 28);
                if (dataStartPos > parentLogicalZipFile.len) {
                    throw new IOException("Unexpected EOF when trying to read zip entry data: " + entryName);
                }
                entryDataStartOffsetWithinPhysicalZipFile = parentLogicalZipFile.startOffsetWithinPhysicalZipFile
                        + dataStartPos;
            }
        }
        return entryDataStartOffsetWithinPhysicalZipFile;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * True if the entire zip entry can be opened as a single ByteBuffer slice.
     *
     * @return true if the entire zip entry can be opened as a single ByteBuffer slice -- the entry must be STORED,
     *         and span only one 2GB buffer chunk.
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    public boolean canGetAsSlice() throws IOException, InterruptedException {
        final long dataStartOffsetWithinPhysicalZipFile = getEntryDataStartOffsetWithinPhysicalZipFile();
        return !isDeflated //
                && dataStartOffsetWithinPhysicalZipFile / FileUtils.MAX_BUFFER_SIZE //
                        == (dataStartOffsetWithinPhysicalZipFile + uncompressedSize) / FileUtils.MAX_BUFFER_SIZE;
    }

    /**
     * Open the ZipEntry as a ByteBuffer slice. Only call this method if {@link #canGetAsSlice()} returned true.
     *
     * @return the ZipEntry as a ByteBuffer.
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    public ByteBufferWrapper getAsSlice() throws IOException, InterruptedException {
        // Check the file is STORED and resides in only one chunk
        if (!canGetAsSlice()) {
            throw new IllegalArgumentException("Cannot open zip entry as a slice");
        }
        final int sliceLength = (int) uncompressedSize;

        // Fetch the ByteBuffer for the applicable chunk
        final long dataStartOffsetWithinPhysicalZipFile = getEntryDataStartOffsetWithinPhysicalZipFile();
        final int chunkIdx = (int) (dataStartOffsetWithinPhysicalZipFile / FileUtils.MAX_BUFFER_SIZE);
        final long chunkStart = chunkIdx * (long) FileUtils.MAX_BUFFER_SIZE;
        final int sliceStart = (int) (dataStartOffsetWithinPhysicalZipFile - chunkStart);

        // Duplicate and slice the ByteBuffer
        return parentLogicalZipFile.physicalZipFile.getByteBuffer(chunkIdx).slice(sliceStart, sliceLength);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open the data of the zip entry as an {@link InputStream}, inflating the data if the entry is deflated.
     *
     * @return the input stream
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    public InputStream open() throws IOException, InterruptedException {
        if (recyclableInflaterInstance != null) {
            throw new IOException("Zip entry already open");
        }
        if (isDeflated) {
            recyclableInflaterInstance = nestedJarHandler.inflaterRecycler.acquire();
        }
        return new InputStream() {
            /** The data start offset within the physical zip file. */
            private final long dataStartOffsetWithinPhysicalZipFile = getEntryDataStartOffsetWithinPhysicalZipFile();

            /** A scratch buffer. */
            private final byte[] scratch = new byte[8 * 1024];

            /** The current 2GB chunk of the zip entry. */
            private ByteBufferWrapper currChunkByteBuf;

            /** True if the current 2GB chunk is the last chunk in the zip entry. */
            private boolean isLastChunk;

            /** The index of the current 2GB chunk. */
            private int currChunkIdx;

            /** True if the end of the zip entry has been reached. */
            private boolean eof;

            /** The {@link Inflater} instance, or null if the entry is stored rather than deflated. */
            private final Inflater inflater = isDeflated ? recyclableInflaterInstance.getInflater() : null;

            /** True if this {@link InputStream} has been closed. */
            private final AtomicBoolean closed = new AtomicBoolean(false);

            /** The size of the {@link Inflate} buffer to use. */
            private static final int INFLATE_BUF_SIZE = 8 * 1024;

            // Open the first 2GB chunk.
            {
                // Calculate the chunk index for the first chunk
                currChunkIdx = (int) (dataStartOffsetWithinPhysicalZipFile / FileUtils.MAX_BUFFER_SIZE);

                // Calculate the start position within the first chunk, and set the position of the slice.
                // N.B. the cast to Buffer is necessary, see:
                // https://github.com/plasma-umass/doppio/issues/497#issuecomment-334740243
                // https://github.com/classgraph/classgraph/issues/284#issuecomment-443612800
                final int chunkPos = (int) (dataStartOffsetWithinPhysicalZipFile
                        - (((long) currChunkIdx) * (long) FileUtils.MAX_BUFFER_SIZE));

                // Calculate end pos for the first chunk, and truncate it if it overflows 2GB
                final int chunkLength = (int) Math.min(FileUtils.MAX_BUFFER_SIZE, compressedSize);
                // True if there's only one chunk (first chunk is also last chunk)
                isLastChunk = chunkLength == compressedSize;

                // Get the MappedByteBuffer for the 2GB chunk, duplicate it and slice it
                currChunkByteBuf = parentLogicalZipFile.physicalZipFile.getByteBuffer(currChunkIdx).slice(chunkPos,
                        chunkLength);
            }

            /** Advance to the next 2GB chunk. */
            private boolean readNextChunk() throws IOException, InterruptedException {
                currChunkIdx++;
                isLastChunk = currChunkIdx >= parentLogicalZipFile.physicalZipFile.numChunks() - 1;
                if (currChunkIdx >= parentLogicalZipFile.physicalZipFile.numChunks()) {
                    // Ran out of chunks
                    return false;
                }

                // Get the MappedByteBuffer for the next 2GB chunk, and duplicate it
                currChunkByteBuf = parentLogicalZipFile.physicalZipFile.getByteBuffer(currChunkIdx).duplicate();
                return true;
            }

            /**
             * Inflate deflated data.
             *
             * @param buf
             *            the buffer to inflate into.
             * @param off
             *            the offset within buf to start writing.
             * @param len
             *            the number of bytes of uncompressed data to read.
             * @return the number of bytes read.
             * @throws IOException
             *             if an I/O exception occurred.
             * @throws InterruptedException
             *             if the thread was interrupted.
             */
            private int readDeflated(final byte[] buf, final int off, final int len)
                    throws IOException, InterruptedException {
                try {
                    final byte[] inflateBuf = new byte[INFLATE_BUF_SIZE];
                    int numInflatedBytes;
                    while ((numInflatedBytes = inflater.inflate(buf, off, len)) == 0) {
                        if (inflater.finished() || inflater.needsDictionary()) {
                            eof = true;
                            return -1;
                        }
                        if (inflater.needsInput()) {
                            // Check if there's still data left in the current chunk
                            if (!currChunkByteBuf.hasRemaining()
                                    // No more bytes in current chunk -- get next chunk, and then make sure
                                    // that currChunkByteBuf.hasRemaining() subsequently returns true
                                    && !(readNextChunk() && currChunkByteBuf.hasRemaining())) {
                                // Ran out of data in the current chunk, and could not read a new chunk
                                throw new IOException("Unexpected EOF in deflated data");
                            }
                            // Set inflater input for the current chunk

                            // In JDK11+: simply use the following instead of all the lines below:
                            //     inflater.setInput(currChunkByteBuf);
                            // N.B. the ByteBuffer version of setInput doesn't seem to need the extra
                            // padding byte at the end when using the "nowrap" Inflater option.

                            // Copy from the ByteBuffer into a temporary byte[] array (needed for JDK<11).
                            try {
                                final int remaining = currChunkByteBuf.remaining();
                                if (isLastChunk && remaining < inflateBuf.length) {
                                    // An extra dummy byte is needed at the end of the input stream when
                                    // using the "nowrap" Inflater option.
                                    // See: ZipFile.ZipFileInputStream.fill()
                                    currChunkByteBuf.get(inflateBuf, 0, remaining);
                                    inflateBuf[remaining] = (byte) 0;
                                    inflater.setInput(inflateBuf, 0, remaining + 1);
                                } else if (isLastChunk && remaining == inflateBuf.length) {
                                    // If this is the last chunk to read, and the number of remaining
                                    // bytes is exactly the size of the buffer, read one byte fewer than
                                    // the number of remaining bytes, to cause the last byte to be read
                                    // in an extra pass.
                                    currChunkByteBuf.get(inflateBuf, 0, remaining - 1);
                                    inflater.setInput(inflateBuf, 0, remaining - 1);
                                } else {
                                    // There are more than inflateBuf.length bytes remaining to be read,
                                    // or this is not the last chunk (i.e. read all remaining bytes in
                                    // this chunk, which will trigger the next chunk to be read on the
                                    // next loop iteration)
                                    final int bytesToRead = Math.min(inflateBuf.length, remaining);
                                    currChunkByteBuf.get(inflateBuf, 0, bytesToRead);
                                    inflater.setInput(inflateBuf, 0, bytesToRead);
                                }
                            } catch (final BufferUnderflowException e) {
                                // Should not happen
                                throw new IOException("Unexpected EOF in deflated data");
                            }
                        }
                    }
                    return numInflatedBytes;
                } catch (final DataFormatException e) {
                    throw new ZipException(
                            e.getMessage() != null ? e.getMessage() : "Invalid deflated zip entry data");
                }
            }

            /**
             * Copy stored (non-deflated) data from ByteBuffer to target buffer.
             *
             * @param buf
             *            the buffer to copy the stored entry into.
             * @param off
             *            the offset within buf to start writing.
             * @param len
             *            the number of bytes to read.
             * @return the number of bytes read.
             * @throws IOException
             *             if an I/O exception occurred.
             * @throws InterruptedException
             *             if the thread was interrupted.
             */
            private int readStored(final byte[] buf, final int off, final int len)
                    throws IOException, InterruptedException {
                int read = 0;
                while (read < len) {
                    if (!currChunkByteBuf.hasRemaining() && !readNextChunk()) {
                        return read == 0 ? -1 : read;
                    }
                    final int remainingToRead = len - read;
                    final int remainingInBuf = currChunkByteBuf.remaining();
                    final int numBytesRead = Math.min(remainingToRead, remainingInBuf);
                    currChunkByteBuf.get(buf, off + read, numBytesRead);
                    read += numBytesRead;
                }
                return read;
            }

            /**
             * Skip stored (non-deflated) data in ByteBuffer.
             *
             * @param n
             *            the number of bytes to skip.
             * @throws IOException
             *             if an I/O exception occurred or the thread was interrupted.
             */
            private void skipStored(final long n) throws IOException {
                try {
                    long skipped = 0;
                    while (skipped < n) {
                        if (!currChunkByteBuf.hasRemaining() && !readNextChunk()) {
                            throw new EOFException("Unexpected EOF while skipping (non-deflated) zip entry data");
                        }
                        final long remainingToSkip = n - skipped;
                        final int remainingInBuf = currChunkByteBuf.remaining();
                        final int numBytesToSkip = (int) Math.min(FileUtils.MAX_BUFFER_SIZE,
                                Math.min(remainingToSkip, remainingInBuf));
                        currChunkByteBuf.skip(numBytesToSkip);
                        skipped += numBytesToSkip;
                    }
                } catch (final InterruptedException e) {
                    nestedJarHandler.interruptionChecker.interrupt();
                    throw new IOException("Thread was interrupted");
                }
            }

            @Override
            public int read(final byte[] buf, final int off, final int len) throws IOException {
                if (closed.get()) {
                    throw new IOException("Stream closed");
                }
                if (buf == null) {
                    throw new NullPointerException();
                } else if (off < 0 || len < 0 || len > buf.length - off) {
                    throw new IndexOutOfBoundsException();
                } else if (len == 0) {
                    return 0;
                } else if (parentLogicalZipFile.physicalZipFile.length() == 0) {
                    return -1;
                }
                try {
                    if (isDeflated) {
                        return readDeflated(buf, off, len);
                    } else {
                        return readStored(buf, off, len);
                    }
                } catch (final InterruptedException e) {
                    nestedJarHandler.interruptionChecker.interrupt();
                    throw new IOException("Thread was interrupted");
                }
            }

            @Override
            public int read() throws IOException {
                if (closed.get()) {
                    throw new IOException("Stream closed");
                }
                return read(scratch, 0, 1) == -1 ? -1 : scratch[0] & 0xff;
            }

            @Override
            public int available() throws IOException {
                if (closed.get()) {
                    throw new IOException("Stream closed");
                }
                if (inflater.finished()) {
                    eof = true;
                }
                return eof ? 0 : 1;
            }

            @Override
            public long skip(final long n) throws IOException {
                if (closed.get()) {
                    throw new IOException("Stream closed");
                }
                if (n < 0) {
                    throw new IllegalArgumentException("Invalid skip value");
                }
                if (isDeflated) {
                    long total = 0;
                    while (total < n) {
                        final int bytesToSkip = (int) Math.min(n - total, scratch.length);
                        final int numSkipped = read(scratch, 0, bytesToSkip);
                        if (numSkipped == -1) {
                            eof = true;
                            break;
                        }
                        total += numSkipped;
                    }
                } else {
                    skipStored(n);
                }
                return n;
            }

            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public synchronized void mark(final int readlimit) {
                throw new IllegalArgumentException("Not supported");
            }

            @Override
            public synchronized void reset() throws IOException {
                throw new IllegalArgumentException("Not supported");
            }

            @Override
            public void close() throws IOException {
                if (!closed.getAndSet(true)) {
                    currChunkByteBuf = null;
                    if (recyclableInflaterInstance != null) {
                        // Reset and recycle the Inflater
                        nestedJarHandler.inflaterRecycler.recycle(recyclableInflaterInstance);
                        recyclableInflaterInstance = null;
                    }
                }
            }
        };
    }

    /**
     * Load the content of the zip entry, and return it as a byte array.
     *
     * @return the entry as a byte[] array
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    public byte[] load() throws IOException, InterruptedException {
        try (InputStream is = open()) {
            return FileUtils.readAllBytesAsArray(is, uncompressedSize);
        }
    }

    /**
     * Load the content of the zip entry, and return it as a String (converting from UTF-8 byte format).
     *
     * @return the entry as a String
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    public String loadAsString() throws IOException, InterruptedException {
        try (InputStream is = open()) {
            return FileUtils.readAllBytesAsString(is, uncompressedSize);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the path to this zip entry, using "!/" as a separator between the parent logical zipfile and the entry
     * name.
     *
     * @return the path of the entry
     */
    public String getPath() {
        return parentLogicalZipFile.getPath() + "!/" + entryName;
    }

    /**
     * Get the last modified time in Epoch millis, or 0L if unknown.
     *
     * @return the last modified time in Epoch millis.
     */
    public long getLastModifiedTimeMillis() {
        // If lastModifiedTimeMillis is zero, but there is an MSDOS date and time available
        if (lastModifiedTimeMillis == 0L && (lastModifiedDateMSDOS != 0 || lastModifiedTimeMSDOS != 0)) {
            // Convert from MS-DOS Date & Time Format to Epoch millis
            final int lastModifiedSecond = (lastModifiedTimeMSDOS & 0b11111) * 2;
            final int lastModifiedMinute = lastModifiedTimeMSDOS >> 5 & 0b111111;
            final int lastModifiedHour = lastModifiedTimeMSDOS >> 11;
            final int lastModifiedDay = lastModifiedDateMSDOS & 0b11111;
            final int lastModifiedMonth = (lastModifiedDateMSDOS >> 5 & 0b111) - 1;
            final int lastModifiedYear = (lastModifiedDateMSDOS >> 9) + 1980;

            final Calendar lastModifiedCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            lastModifiedCalendar.set(lastModifiedYear, lastModifiedMonth, lastModifiedDay, lastModifiedHour,
                    lastModifiedMinute, lastModifiedSecond);
            lastModifiedCalendar.set(Calendar.MILLISECOND, 0);

            // Cache converted time by overwriting the zero lastModifiedTimeMillis field
            lastModifiedTimeMillis = lastModifiedCalendar.getTimeInMillis();
        }

        // Return the last modified time, or 0L if it is totally unknown.
        return lastModifiedTimeMillis;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "jar:file:" + getPath();
    }

    /**
     * Sort in decreasing order of version number, then lexicographically increasing order of unversioned entry
     * path.
     *
     * @param o
     *            the object to compare to
     * @return the result of comparison
     */
    @Override
    public int compareTo(final FastZipEntry o) {
        final int diff0 = o.version - this.version;
        if (diff0 != 0) {
            return diff0;
        }
        final int diff1 = entryNameUnversioned.compareTo(o.entryNameUnversioned);
        if (diff1 != 0) {
            return diff1;
        }
        final int diff2 = entryName.compareTo(o.entryName);
        if (diff2 != 0) {
            return diff2;
        }
        // In case of multiple entries with the same entry name, return them in consecutive order of location,
        // so that the earliest entry overrides later entries (this is an arbitrary decision for consistency)
        final long diff3 = locHeaderPos - o.locHeaderPos;
        return diff3 < 0L ? -1 : diff3 > 0L ? 1 : 0;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        } else if (!(obj instanceof FastZipEntry)) {
            return false;
        }
        final FastZipEntry other = (FastZipEntry) obj;
        return this.parentLogicalZipFile.equals(other.parentLogicalZipFile) && this.compareTo(other) == 0;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return parentLogicalZipFile.hashCode() ^ version ^ entryName.hashCode() ^ (int) locHeaderPos;
    }
}
