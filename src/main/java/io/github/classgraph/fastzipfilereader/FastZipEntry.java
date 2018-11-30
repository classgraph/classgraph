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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.fastzipfilereader;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import io.github.classgraph.fastzipfilereader.NestedJarHandler.RecyclableInflater;
import io.github.classgraph.utils.FileUtils;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.Recycler;
import io.github.classgraph.utils.VersionFinder;

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
    public final boolean isDeflated;

    /** The compressed size of the zip entry, in bytes. */
    public final long compressedSize;

    /** The uncompressed size of the zip entry, in bytes. */
    public final long uncompressedSize;

    /**
     * The version code (&gt;= 9), or 8 for the base layer or a non-versioned jar (whether JDK 7 or 8 compatible).
     */
    public final int version;

    /**
     * The unversioned entry name (i.e. entryName with "META_INF/versions/{versionInt}/" stripped)
     */
    public final String entryNameUnversioned;

    /** The {@link Inflater} recycler. */
    private final Recycler<RecyclableInflater, RuntimeException> inflaterRecycler;

    /** The {@link Recycler.Recyclable} instance wrapping recyclable {@link Inflater} instances. */
    private Recycler<RecyclableInflater, RuntimeException>.Recyclable inflaterRecyclable;

    // -------------------------------------------------------------------------------------------------------------

    /**
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
     */
    public FastZipEntry(final LogicalZipFile parentLogicalZipFile, final long locHeaderPos, final String entryName,
            final boolean isDeflated, final long compressedSize, final long uncompressedSize,
            final NestedJarHandler nestedJarHandler) {
        this.parentLogicalZipFile = parentLogicalZipFile;
        this.locHeaderPos = locHeaderPos;
        this.entryName = entryName;
        this.isDeflated = isDeflated;
        this.compressedSize = compressedSize;
        this.uncompressedSize = !isDeflated && uncompressedSize < 0 ? compressedSize : uncompressedSize;
        this.inflaterRecycler = nestedJarHandler.inflaterRecycler;

        // Get multi-release jar version number, and strip any version prefix
        int version = 8;
        String entryNameUnversioned = entryName;
        if (entryName.length() > LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length() + 1) {
            // This is a multi-release jar path
            final int nextSlashIdx = entryName.indexOf('/', LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length());
            if (nextSlashIdx > 0) {
                // Get path after version number, i.e. strip "META-INF/versions/{versionInt}/" prefix
                final String versionStr = entryName.substring(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length(),
                        nextSlashIdx);
                try {
                    // For multi-release jars, the version number has to be an int >= 9
                    version = Integer.parseInt(versionStr);
                } catch (final NumberFormatException e) {
                    // Ignore
                }
                // Set version to 8 for out-of-range version numbers or invalid paths
                if (version < 9 || version > VersionFinder.JAVA_MAJOR_VERSION) {
                    version = 8;
                }
                if (version > 8) {
                    // Strip version path prefix
                    entryNameUnversioned = entryName.substring(nextSlashIdx + 1);
                    // For META-INF/versions/{versionInt}/META-INF/*, don't strip version prefix:
                    // "The intention is that the META-INF directory cannot be versioned."
                    // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-October/013954.html
                    if (entryNameUnversioned.startsWith(LogicalZipFile.META_INF_PATH_PREFIX)) {
                        version = 8;
                        entryNameUnversioned = entryName;
                    }
                }
            }
        }
        this.version = version;
        this.entryNameUnversioned = entryNameUnversioned;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the path to this zip entry, using "!/" as a separator between the parent logical zipfile and the entry
     * name.
     */
    public String getPath() {
        return parentLogicalZipFile.getPath() + "!/" + entryName;
    }

    /**
     * Lazily find zip entry data start offset -- this is deferred until zip entry data needs to be read, in order
     * to avoid randomly seeking within zipfile for every entry as the central directory is read.
     */
    long getEntryDataStartOffsetWithinPhysicalZipFile() throws IOException {
        if (entryDataStartOffsetWithinPhysicalZipFile == -1L) {
            // Create zipfile slice reader for zip entry
            final ZipFileSliceReader headerReader = new ZipFileSliceReader(parentLogicalZipFile);
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
        return entryDataStartOffsetWithinPhysicalZipFile;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Open the data of the zip entry as an {@link InputStream}, inflating the data if the entry is deflated. */
    public InputStream open() throws IOException {
        if (inflaterRecyclable != null) {
            throw new IOException("Zip entry already open");
        }
        if (isDeflated) {
            inflaterRecyclable = inflaterRecycler.acquire();
            if (inflaterRecyclable == null) {
                // Should not happen
                throw new RuntimeException("Could not get Inflater instance");
            }
        }
        return new InputStream() {
            private final long dataStartOffsetWithinPhysicalZipFile = getEntryDataStartOffsetWithinPhysicalZipFile();
            private final byte[] skipBuf = new byte[8192];
            private final byte[] oneByteBuf = new byte[1];
            private ByteBuffer currChunkByteBuf;
            private boolean isLastChunk;
            private int currChunkIdx;
            private boolean eof = false;
            private final Inflater inflater = isDeflated ? inflaterRecyclable.get().getInflater() : null;
            private final AtomicBoolean closed = new AtomicBoolean(false);

            private static final int INFLATE_BUF_SIZE = 1024;

            {
                // Calculate the chunk index for the first chunk
                currChunkIdx = (int) (dataStartOffsetWithinPhysicalZipFile >> 32);

                // Get the MappedByteBuffer for the 2GB chunk, and duplicate it
                currChunkByteBuf = parentLogicalZipFile.physicalZipFile.getByteBuffer(currChunkIdx).duplicate();

                // Calculate the start position within the first chunk, and set the position of the slice
                final int chunkPos = (int) (dataStartOffsetWithinPhysicalZipFile - (((long) currChunkIdx) << 32));
                currChunkByteBuf.position(chunkPos);

                // Calculate end pos for the first chunk, and truncate it if it overflows 2GB
                final long endPos = chunkPos + compressedSize;
                currChunkByteBuf.limit((int) Math.min(Integer.MAX_VALUE, endPos));
                isLastChunk = endPos <= Integer.MAX_VALUE;
            }

            private boolean getNextChunk() throws IOException {
                // Advance to next 2GB chunk
                currChunkIdx++;
                if (currChunkIdx >= parentLogicalZipFile.physicalZipFile.numMappedByteBuffers) {
                    // Ran out of chunks
                    return false;
                }

                // Calculate how many bytes were consumed in previous chunks
                final long chunkStartOff = ((long) currChunkIdx) << 32;
                final long priorBytes = chunkStartOff - dataStartOffsetWithinPhysicalZipFile;
                final long remainingBytes = compressedSize - priorBytes;
                if (remainingBytes <= 0) {
                    return false;
                }

                // Get the MappedByteBuffer for the next 2GB chunk, and duplicate it
                currChunkByteBuf = parentLogicalZipFile.physicalZipFile.getByteBuffer(currChunkIdx).duplicate();

                // The start position for 2nd and subsequent chunks is 0
                currChunkByteBuf.position(0);

                // Calculate end pos for the next chunk, and truncate it if it overflows 2GB
                currChunkByteBuf.limit((int) Math.min(Integer.MAX_VALUE, remainingBytes));
                isLastChunk = remainingBytes <= Integer.MAX_VALUE;
                return true;
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
                } else if (parentLogicalZipFile.physicalZipFile.fileLen == 0) {
                    return -1;
                }
                if (isDeflated) {
                    // Inflate deflated data
                    try {
                        final byte[] inflateBuf = new byte[INFLATE_BUF_SIZE];
                        int numInflatedBytes;
                        while ((numInflatedBytes = inflater.inflate(buf, off, len)) == 0) {
                            if (inflater.finished() || inflater.needsDictionary()) {
                                eof = true;
                                return -1;
                            }
                            if (inflater.needsInput()) {
                                if (!currChunkByteBuf.hasRemaining()) {
                                    // No more bytes in current chunk -- get next chunk
                                    if (!getNextChunk() || !currChunkByteBuf.hasRemaining()) {
                                        throw new IOException("Unexpected EOF in deflated data");
                                    }
                                }
                                // Set inflater input for the current chunk

                                // TODO (JDK11+): simply use the following instead of all the lines below:
                                // inflater.setInput(currChunkByteBuf);
                                // N.B. the ByteBuffer version of setInput doesn't seem to need the extra
                                // padding byte at the end when using the "nowrap" Inflater option.

                                // Copy from the ByteBuffer into a temporary byte[] array (needed for JDK<11). 
                                try {
                                    final int remaining = currChunkByteBuf.remaining();
                                    final int maxBytesToRead = remaining == inflateBuf.length && isLastChunk
                                            ? remaining - 1
                                            : remaining;
                                    final int bytesToRead = Math.min(inflateBuf.length, maxBytesToRead);
                                    currChunkByteBuf.get(inflateBuf, 0, bytesToRead);
                                    if (bytesToRead == remaining && isLastChunk) {
                                        // An extra dummy byte is needed at the end of the input stream when
                                        // using the "nowrap" Inflater option.
                                        // See: ZipFile.ZipFileInputStream.fill()
                                        inflateBuf[bytesToRead] = (byte) 0;
                                    }
                                } catch (final BufferUnderflowException e) {
                                    // Should not happen
                                    throw new IOException("Unexpected EOF in deflated data");
                                }
                                inflater.setInput(inflateBuf);
                            }
                        }
                        return numInflatedBytes;
                    } catch (final DataFormatException e) {
                        throw new ZipException(
                                e.getMessage() != null ? e.getMessage() : "Invalid deflated zip entry data");
                    }
                } else {
                    // Copy stored (non-deflated) data from ByteBuffer to target buffer
                    int read = 0;
                    while (read < len) {
                        if (!currChunkByteBuf.hasRemaining()) {
                            if (!getNextChunk()) {
                                return read == 0 ? -1 : read;
                            }
                        }
                        final int remainingToRead = len - read;
                        final int remainingInBuf = currChunkByteBuf.remaining();
                        final int numBytesRead = Math.min(remainingToRead, remainingInBuf);
                        try {
                            currChunkByteBuf.get(buf, read, numBytesRead);
                        } catch (final BufferUnderflowException e) {
                            // Should not happen
                            throw new EOFException("Unexpected EOF in stored (non-deflated) zip entry data");
                        }
                        read += numBytesRead;
                    }
                    return read;
                }
            }

            @Override
            public int read() throws IOException {
                if (closed.get()) {
                    throw new IOException("Stream closed");
                }
                return read(oneByteBuf, 0, 1) == -1 ? -1 : oneByteBuf[0] & 0xff;
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
                long total = 0;
                while (total < n) {
                    final int numSkipped = read(skipBuf, 0, (int) Math.min(n - total, skipBuf.length));
                    if (numSkipped == -1) {
                        eof = true;
                        break;
                    }
                    total += numSkipped;
                }
                return total;
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
                    if (inflaterRecyclable != null) {
                        // Reset and recycle the Inflater
                        inflaterRecyclable.close();
                        inflaterRecyclable = null;
                    }
                }
            }
        };
    }

    /** Load the content of the zip entry, and return it as a byte array. */
    public byte[] load(final LogNode log) throws IOException {
        try (InputStream is = open()) {
            return FileUtils.readAllBytesAsArray(is, uncompressedSize);
        }
    }

    /** Load the content of the zip entry, and return it as a String (converting from UTF-8 byte format). */
    public String loadAsString(final LogNode log) throws IOException {
        try (InputStream is = open()) {
            return FileUtils.readAllBytesAsString(is, uncompressedSize);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Sort in decreasing order of version number, then lexicographically increasing order of unversioned entry
     * path.
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
}