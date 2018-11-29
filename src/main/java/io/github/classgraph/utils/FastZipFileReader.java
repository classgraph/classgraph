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
package io.github.classgraph.utils;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import io.github.classgraph.ClassGraph;

public class FastZipFileReader {

    // TODO: move this to a non-static field of NestedJarHandler, and change into a singleton map (then close the singleton map on NestedJarHandler.close())
    private static Queue<PhysicalZipFile> openedPhysicalZipFiles = new ConcurrentLinkedQueue<>();

    public static class PhysicalZipFile implements Closeable {
        private File file;
        private RandomAccessFile raf;
        private long fileLen;
        private FileChannel fc;
        private MappedByteBuffer[] mappedByteBuffersCached;
        private SingletonMap<Integer, MappedByteBuffer> chunkIdxToByteBuffer;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        public PhysicalZipFile(final File file) throws IOException {
            this.file = file;
            raf = new RandomAccessFile(file, "r");
            fileLen = raf.length();
            if (fileLen == 0L) {
                throw new IOException("Zipfile is empty: " + file);
            }
            fc = raf.getChannel();

            // Implement an array of MappedByteBuffers to support jarfiles >2GB in size:
            // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6347833
            final int numMappedByteBuffers = (int) ((fileLen + 0xffffffffL) >> 32);
            mappedByteBuffersCached = new MappedByteBuffer[numMappedByteBuffers];
            chunkIdxToByteBuffer = new SingletonMap<Integer, MappedByteBuffer>() {
                @Override
                public MappedByteBuffer newInstance(final Integer chunkIdxI, final LogNode log) throws Exception {
                    // Map the indexed 2GB chunk of the file to a MappedByteBuffer
                    final long pos = chunkIdxI.longValue() << 32;
                    final long chunkSize = Math.min(Integer.MAX_VALUE, fileLen - pos);
                    return fc.map(FileChannel.MapMode.READ_ONLY, pos, chunkSize);
                }
            };

            // Record all opened physical zipfiles. 
            // TODO: switch to singleton map
            openedPhysicalZipFiles.add(this);
        }

        /**
         * Get a mmap'd chunk of the file, where chunkIdx denotes which 2GB chunk of the file to return (0 for the
         * first 2GB of the file, or for files smaller than 2GB; 1 for the 2-4GB chunk, etc.).
         * 
         * @param chunkIdx
         *            The index of the 2GB chunk to read
         * @return The {@link MappedByteBuffer} for the requested file chunk, up to 2GB in size.
         * @throws IOException
         *             If the chunk could not be mmap'd.
         */
        public ByteBuffer getByteBuffer(final int chunkIdx, final LogNode log) throws IOException {
            if (closed.get()) {
                throw new IOException("Cannot call getByteBuffer(int) after close()");
            }
            if (chunkIdx < 0 || chunkIdx >= mappedByteBuffersCached.length) {
                throw new IOException("Chunk index out of range");
            }
            // Fast path: only look up singleton map if mappedByteBuffersCached is null 
            if (mappedByteBuffersCached[chunkIdx] == null) {
                try {
                    // This 2GB chunk has not yet been read -- mmap it (use a singleton map so that the mmap
                    // doesn't happen more than once, in case of race condition)
                    mappedByteBuffersCached[chunkIdx] = chunkIdxToByteBuffer.getOrCreateSingleton(chunkIdx, log);
                    if (mappedByteBuffersCached[chunkIdx] == null) {
                        throw new IOException("Could not allocate chunk " + chunkIdx);
                    }
                } catch (final Exception e) {
                    throw new IOException(e);
                }
            }
            return mappedByteBuffersCached[chunkIdx];
        }

        public LogicalZipFile getToplevelLogicalZipFile(final LogNode log) throws IOException {
            if (closed.get()) {
                throw new IOException("Cannot call getToplevelLogicalZipFile(LogNode) after close()");
            }
            return new LogicalZipFile(this, 0, fileLen, log);
        }

        @Override
        public void close() {
            if (!closed.getAndSet(true)) {
                Arrays.fill(mappedByteBuffersCached, null);
                mappedByteBuffersCached = null;
                chunkIdxToByteBuffer.clear();
                chunkIdxToByteBuffer = null;
                if (fc != null) {
                    try {
                        fc.close();
                        fc = null;
                    } catch (final IOException e) {
                        // Ignore
                    }
                }
                if (raf != null) {
                    try {
                        raf.close();
                        raf = null;
                    } catch (final IOException e) {
                        // Ignore
                    }
                }
            }
        }
    }

    /** A zipfile slice (a sub-range of bytes within a {@link PhysicalZipFile}). */
    public static class ZipFileSlice {
        /** The underlying physical zipfile. */
        PhysicalZipFile physicalZipFile;
        /** The start offset of the slice within the physical zipfile. */
        long startOffsetWithinPhysicalZipFile;
        /** The compressed or stored size of the zipfile slice or entry. */
        long len;

        public ZipFileSlice(final PhysicalZipFile physicalZipFile, final long startOffsetWithinPhysicalZipFile,
                final long len) {
            this.physicalZipFile = physicalZipFile;
            this.startOffsetWithinPhysicalZipFile = startOffsetWithinPhysicalZipFile;
            this.len = len;
        }

        @Override
        public int hashCode() {
            return physicalZipFile.file.hashCode() ^ (int) startOffsetWithinPhysicalZipFile ^ (int) len;
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof ZipFileSlice)) {
                return false;
            }
            final ZipFileSlice o = (ZipFileSlice) obj;
            return startOffsetWithinPhysicalZipFile == o.startOffsetWithinPhysicalZipFile && len == o.len
                    && this.physicalZipFile.equals(o.physicalZipFile);
        }
    }

    public static class FastZipEntry extends ZipFileSlice implements Comparable<FastZipEntry> {
        private final long locHeaderPos;
        private long entryDataStartOffsetWithinPhysicalZipFile = -1L;
        private final String entryName;
        private final boolean isDeflated;
        private final long uncompressedSize;

        /**
         * @param physicalZipFile
         *            The physical zipfile.
         * @param parentLogicalZipFileStartOffsetWithinPhysicalZipFile
         *            The start offset within the physical zipfile of the parent logical zipfile containing this zip
         *            entry.
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
         */
        public FastZipEntry(final PhysicalZipFile physicalZipFile,
                final long parentLogicalZipFileStartOffsetWithinPhysicalZipFile, final long locHeaderPos,
                final String entryName, final boolean isDeflated, final long compressedSize,
                final long uncompressedSize) {
            super(physicalZipFile,
                    /* super.startOffsetWithinPhysicalZipFile = the offset of the parent logical zipfile */
                    parentLogicalZipFileStartOffsetWithinPhysicalZipFile,
                    /* super.len = the compressed size of the entry */
                    compressedSize);
            this.locHeaderPos = locHeaderPos;
            this.entryName = entryName;
            this.isDeflated = isDeflated;
            this.uncompressedSize = !isDeflated && uncompressedSize < 0 ? compressedSize : uncompressedSize;
        }

        /**
         * Lazily find zip entry data start offset -- this is deferred until zip entry data needs to be read, in
         * order to avoid randomly seeking within zipfile for every entry as the central directory is read.
         */
        private long getEntryDataStartOffsetWithinPhysicalZipFile(final LogNode log) throws IOException {
            if (entryDataStartOffsetWithinPhysicalZipFile == -1L) {
                // Create zipfile slice reader for zip entry
                final ZipFileSliceReader headerReader = new ZipFileSliceReader(
                        new ZipFileSlice(physicalZipFile, startOffsetWithinPhysicalZipFile, locHeaderPos + 30));
                // Check header magic
                if (headerReader.getInt(locHeaderPos, log) != 0x04034b50) {
                    throw new IOException("Zip entryr has bad LOC header: " + entryName);
                }
                entryDataStartOffsetWithinPhysicalZipFile = startOffsetWithinPhysicalZipFile + locHeaderPos + 30
                        + headerReader.getShort(locHeaderPos + 26, log)
                        + headerReader.getShort(locHeaderPos + 28, log);
                if (entryDataStartOffsetWithinPhysicalZipFile + len > physicalZipFile.fileLen) {
                    throw new IOException("Unexpected EOF when trying to read zip entry data: " + entryName);
                }

            }
            return entryDataStartOffsetWithinPhysicalZipFile;
        }

        /**
         * Open the uncompressed data of the zip entry as an {@link InputStream}, inflating the data if the entry is
         * deflated.
         */
        public InputStream open(final LogNode log) throws IOException {
            final Inflater inflater;
            if (isDeflated) {
                // TODO: recycle the Inflater
                inflater = new Inflater(/* nowrap = */ true);
            } else {
                inflater = null;
            }
            return new InputStream() {
                private final long dataStartOffsetWithinPhysicalZipFile = getEntryDataStartOffsetWithinPhysicalZipFile(
                        log);
                private final byte[] skipBuf = new byte[8192];
                private final byte[] oneByteBuf = new byte[1];
                private ByteBuffer currChunkByteBuf;
                private int currChunkIdx;
                private boolean eof = false;
                private final AtomicBoolean closed = new AtomicBoolean(false);

                {
                    // Calculate the chunk index for the first chunk
                    currChunkIdx = (int) (dataStartOffsetWithinPhysicalZipFile >> 32);

                    // Get the MappedByteBuffer for the 2GB chunk, and duplicate it
                    currChunkByteBuf = physicalZipFile.getByteBuffer(currChunkIdx, log).duplicate();

                    // Calculate the start position within the first chunk, and set the position of the slice
                    final int chunkPos = (int) (dataStartOffsetWithinPhysicalZipFile
                            - (((long) currChunkIdx) << 32));
                    currChunkByteBuf.position(chunkPos);

                    // Calculate end pos for the first chunk, and truncate it if it overflows 2GB
                    final long endPos = chunkPos + len;
                    currChunkByteBuf.limit((int) Math.min(Integer.MAX_VALUE, endPos));
                }

                private boolean getNextChunk() throws IOException {
                    // Advance to next 2GB chunk
                    currChunkIdx++;
                    if (currChunkIdx >= physicalZipFile.mappedByteBuffersCached.length) {
                        // Ran out of chunks
                        return false;
                    }

                    // Calculate how many bytes were consumed in previous chunks
                    final long chunkStartOff = ((long) currChunkIdx) << 32;
                    final long priorBytes = chunkStartOff - dataStartOffsetWithinPhysicalZipFile;
                    final long remainingBytes = len - priorBytes;
                    if (remainingBytes <= 0) {
                        return false;
                    }

                    // Get the MappedByteBuffer for the next 2GB chunk, and duplicate it
                    currChunkByteBuf = physicalZipFile.getByteBuffer(currChunkIdx, log).duplicate();

                    // The start position for 2nd and subsequent chunks is 0
                    currChunkByteBuf.position(0);

                    // Calculate end pos for the next chunk, and truncate it if it overflows 2GB
                    currChunkByteBuf.limit((int) Math.min(Integer.MAX_VALUE, remainingBytes));
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
                    } else if (physicalZipFile.fileLen == 0) {
                        return -1;
                    }
                    if (isDeflated) {
                        // Inflate deflated data
                        try {
                            int numInflatedBytes;
                            while ((numInflatedBytes = inflater.inflate(buf, off, len)) == 0) {
                                if (inflater.finished() || inflater.needsDictionary()) {
                                    eof = true;
                                    return -1;
                                }
                                if (inflater.needsInput()) {
                                    if (!currChunkByteBuf.hasRemaining()) {
                                        if (!getNextChunk()) {
                                            throw new IOException("Unexpected EOF in deflated data");
                                        }
                                    }
                                    // Set inflater input for the current chunk
                                    inflater.setInput(currChunkByteBuf);
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
                    if (eof) {
                        return 0;
                    } else if (inflater.finished()) {
                        eof = true;
                        return 0;
                    }
                    return 1;
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
                public void mark(final int readlimit) {
                    throw new IllegalArgumentException("Not supported");
                }

                @Override
                public void reset() throws IOException {
                    throw new IllegalArgumentException("Not supported");
                }

                @Override
                public void close() throws IOException {
                    if (!closed.getAndSet(true)) {
                        if (inflater != null) {
                            inflater.end();
                            // TODO: recycle inflater
                        }
                        currChunkByteBuf = null;
                    }
                }
            };
        }

        public LogicalZipFile getNestedLogicalZipFile(final NestedJarHandler nestedJarHandler, final LogNode log)
                throws IOException {
            // TODO: get nested LogicalZipFile in a singleton map
            if (isDeflated) {
                // Deflated nested jars need to be extracted to temporary files before their directories can be read
                try (InputStream inputStream = open(log)) {
                    // Extract nested jar to a new temporary file
                    final File tempFile = nestedJarHandler.makeTempFile(entryName, /* onlyUseLeafname = */ true);
                    if (log != null) {
                        log.log("Extracting deflated nested jarfile " + entryName + " to temporary file "
                                + tempFile);
                    }
                    Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                    // Wrap temporary file in a new PhysicalZipFile instance
                    @SuppressWarnings("resource")
                    final PhysicalZipFile nestedPhysicalZipFile = new PhysicalZipFile(tempFile);

                    // Get LogicalZipFile instance for toplevel of new PhysicalZipFile
                    return nestedPhysicalZipFile.getToplevelLogicalZipFile(log);
                }
            } else {
                // Create new LogicalZipFile with the same start offset and length as the entry
                final long dataStartOffsetWithinPhysicalZipFile = getEntryDataStartOffsetWithinPhysicalZipFile(log);
                return new LogicalZipFile(physicalZipFile, dataStartOffsetWithinPhysicalZipFile, len, log);
            }
        }

        /** Order entries based on entryName (only use for entries within the same {@link LogicalZipFile}). */
        @Override
        public int compareTo(final FastZipEntry o) {
            return entryName.compareTo(o.entryName);
        }
    }

    public static class ZipFileSliceReader {
        private final ZipFileSlice zipFileSlice;
        private final ByteBuffer[] chunkCache;
        private final byte[] buf = new byte[8];

        public ZipFileSliceReader(final ZipFileSlice zipFileSlice) {
            this.zipFileSlice = zipFileSlice;
            this.chunkCache = new ByteBuffer[zipFileSlice.physicalZipFile.mappedByteBuffersCached.length];
        }

        ByteBuffer getChunk(final int chunkIdx, final LogNode log) throws IOException {
            ByteBuffer chunk = chunkCache[chunkIdx];
            if (chunk == null) {
                final ByteBuffer byteBufferDup = zipFileSlice.physicalZipFile.getByteBuffer(chunkIdx, log)
                        .duplicate();
                chunk = chunkCache[chunkIdx] = byteBufferDup;
            }
            return chunk;
        }

        /**
         * Copy from an offset within the file into a byte[] array (possibly spanning the boundary between two 2GB
         * chunks).
         */
        int read(final long off, final byte[] buf, final int bufStart, final int numBytesToRead, final LogNode log)
                throws IOException {
            if (off < 0 || bufStart < 0 || bufStart + numBytesToRead > buf.length) {
                throw new IndexOutOfBoundsException();
            }
            int currBufStart = bufStart;
            int remainingBytesToRead = numBytesToRead;
            int totBytesRead = 0;
            for (long currOff = off; remainingBytesToRead > 0;) {
                // Find the ByteBuffer chunk to read from
                final long currOffAbsolute = zipFileSlice.startOffsetWithinPhysicalZipFile + currOff;
                final int chunkIdx = (int) (currOffAbsolute >> 32);
                final ByteBuffer chunk = getChunk(chunkIdx, log);
                final long chunkStartAbsolute = ((long) chunkIdx) << 32;
                final int startReadPos = (int) (currOffAbsolute - chunkStartAbsolute);

                // Read from current chunk
                chunk.mark();
                chunk.position(startReadPos);
                final int numBytesRead = Math.min(chunk.remaining(), remainingBytesToRead);
                try {
                    chunk.get(buf, currBufStart, numBytesRead);
                } catch (final BufferUnderflowException e) {
                    // Should not happen
                    throw new EOFException("Unexpected EOF");
                }
                chunk.reset();

                currOff += numBytesRead;
                currBufStart += numBytesRead;
                totBytesRead += numBytesRead;
                remainingBytesToRead -= numBytesRead;
            }
            return totBytesRead == 0 && numBytesToRead > 0 ? -1 : totBytesRead;
        }

        private int getShort(final long off, final LogNode log) throws IOException {
            if (off < 0 || off > zipFileSlice.len - 2) {
                throw new IndexOutOfBoundsException();
            }
            if (read(off, buf, 0, 2, log) < 2) {
                throw new EOFException("Unexpected EOF");
            }
            return ((buf[1] & 0xff) << 8) + (buf[0] & 0xff);
        }

        private int getInt(final long off, final LogNode log) throws IOException {
            if (off < 0 || off > zipFileSlice.len - 2) {
                throw new IndexOutOfBoundsException();
            }
            if (read(off, buf, 0, 4, log) < 4) {
                throw new EOFException("Unexpected EOF");
            }
            return ((buf[3] & 0xff) << 24) + ((buf[2] & 0xff) << 16) + ((buf[1] & 0xff) << 8) + (buf[0] & 0xff);
        }

        private long getLong(final long off, final LogNode log) throws IOException {
            if (off < 0 || off > zipFileSlice.len - 2) {
                throw new IndexOutOfBoundsException();
            }
            if (read(off, buf, 0, 8, log) < 8) {
                throw new EOFException("Unexpected EOF");
            }
            return ((buf[7] & 0xff) << 56) + ((buf[6] & 0xff) << 48) + ((buf[5] & 0xff) << 40)
                    + ((buf[4] & 0xff) << 32) + ((buf[3] & 0xff) << 24) + ((buf[2] & 0xff) << 16)
                    + ((buf[1] & 0xff) << 8) + (buf[0] & 0xff);
        }

        private String getString(final long off, final int numBytes, final LogNode log) throws IOException {
            if (off < 0 || off > zipFileSlice.len - numBytes) {
                throw new IndexOutOfBoundsException();
            }
            final byte[] strBytes = new byte[numBytes];
            if (read(off, strBytes, 0, numBytes, log) < numBytes) {
                throw new EOFException("Unexpected EOF");
            }
            // Assume the entry names are encoded in UTF-8 (should be the case for all jars; the only other
            // valid zipfile charset is CP437, which is the same as ASCII for printable high-bit-clear chars)
            return new String(strBytes, StandardCharsets.UTF_8);
        }
    }

    public static class LogicalZipFile extends ZipFileSlice {
        private final ZipFileSliceReader zipFileSliceReader;
        List<FastZipEntry> entries;

        private static int getShort(final byte[] buf, final long off) throws IOException {
            final int ioff = (int) off;
            if (ioff < 0 || ioff > buf.length - 2) {
                throw new IndexOutOfBoundsException();
            }
            return ((buf[ioff + 1] & 0xff) << 8) + (buf[ioff + 0] & 0xff);
        }

        private static int getInt(final byte[] buf, final long off) throws IOException {
            final int ioff = (int) off;
            if (ioff < 0 || ioff > buf.length - 4) {
                throw new IndexOutOfBoundsException();
            }
            return ((buf[ioff + 3] & 0xff) << 24) + ((buf[ioff + 2] & 0xff) << 16) + ((buf[ioff + 1] & 0xff) << 8)
                    + (buf[ioff + 0] & 0xff);
        }

        private static long getLong(final byte[] buf, final long off) throws IOException {
            final int ioff = (int) off;
            if (ioff < 0 || ioff > buf.length - 8) {
                throw new IndexOutOfBoundsException();
            }
            return ((buf[ioff + 7] & 0xff) << 56) + ((buf[ioff + 6] & 0xff) << 48) + ((buf[ioff + 5] & 0xff) << 40)
                    + ((buf[ioff + 4] & 0xff) << 32) + ((buf[ioff + 3] & 0xff) << 24)
                    + ((buf[ioff + 2] & 0xff) << 16) + ((buf[ioff + 1] & 0xff) << 8) + (buf[ioff + 0] & 0xff);
        }

        private static String getString(final byte[] buf, final long off, final int numBytes) throws IOException {
            final int ioff = (int) off;
            if (ioff < 0 || ioff > buf.length - 8) {
                throw new IndexOutOfBoundsException();
            }
            return new String(buf, ioff, numBytes, StandardCharsets.UTF_8);
        }

        LogicalZipFile(final PhysicalZipFile physicalZipFile, final long startOffsetWithinPhysicalZipFile,
                final long len, final LogNode log) throws IOException {
            super(physicalZipFile, startOffsetWithinPhysicalZipFile, len);
            zipFileSliceReader = new ZipFileSliceReader(this);

            // Scan for End Of Central Directory (EOCD) signature
            long eocdPos = -1;
            for (long i = len - 22; i >= 0; --i) {
                if (zipFileSliceReader.getInt(i, log) == 0x06054b50) {
                    eocdPos = i;
                    break;
                }
            }
            if (eocdPos < 0) {
                throw new IOException("Jarfile central directory signature not found: " + physicalZipFile.file);
            }
            long numEnt = zipFileSliceReader.getShort(eocdPos + 8, log);
            if (zipFileSliceReader.getShort(eocdPos + 4, log) > 0
                    || zipFileSliceReader.getShort(eocdPos + 6, log) > 0
                    || numEnt != zipFileSliceReader.getShort(eocdPos + 10, log)) {
                throw new IOException("Multi-disk jarfiles not supported: " + physicalZipFile.file);
            }
            long cenOff = zipFileSliceReader.getInt(eocdPos + 16, log);
            long cenSize = zipFileSliceReader.getInt(eocdPos + 12, log);
            final long cenPos = eocdPos - cenSize;
            if (cenSize > eocdPos) {
                throw new IOException("Central directory size out of range: " + cenSize + " vs. " + eocdPos + ": "
                        + physicalZipFile.file);
            }

            // Check for Zip64 End Of Central Directory Locator record
            final long zip64cdLocIdx = eocdPos - 20;
            long entriesTotSizeL;
            if (zip64cdLocIdx >= 0 && zipFileSliceReader.getInt(zip64cdLocIdx, log) == 0x07064b50) {
                if (zipFileSliceReader.getInt(zip64cdLocIdx + 4, log) > 0
                        || zipFileSliceReader.getInt(zip64cdLocIdx + 16, log) > 1) {
                    throw new IOException("Multi-disk jarfiles not supported: " + physicalZipFile.file);
                }
                final long eocdPos64 = zipFileSliceReader.getLong(zip64cdLocIdx + 8, log);
                if (zipFileSliceReader.getInt(eocdPos64, log) != 0x06064b50) {
                    throw new IOException("Zip64 central directory at location " + eocdPos64
                            + " does not have Zip64 central directory header: " + physicalZipFile.file);
                }
                final long numEnt64 = zipFileSliceReader.getLong(eocdPos64 + 24, log);
                if (zipFileSliceReader.getInt(eocdPos64 + 16, log) > 0
                        || zipFileSliceReader.getInt(eocdPos64 + 20, log) > 0
                        || numEnt64 != zipFileSliceReader.getLong(eocdPos64 + 32, log)) {
                    throw new IOException("Multi-disk jarfiles not supported: " + physicalZipFile.file);
                }
                if (numEnt != numEnt64 && numEnt != 0xffff) {
                    // Entry size mismatch -- trigger manual counting of entries
                    numEnt = -1L;
                } else {
                    numEnt = numEnt64;
                }

                final long cenSize64 = zipFileSliceReader.getLong(eocdPos64 + 40, log);
                if (cenSize != cenSize64 && cenSize != 0xffffffff) {
                    throw new IOException("Mismatch in central directory size: " + cenSize + " vs. " + cenSize64
                            + ": " + physicalZipFile.file);
                }
                cenSize = cenSize64;

                final long cenOff64 = zipFileSliceReader.getLong(eocdPos64 + 48, log);
                if (cenOff != cenOff64 && cenOff != 0xffffffff) {
                    throw new IOException("Mismatch in central directory offset: " + cenOff + " vs. " + cenOff64
                            + ": " + physicalZipFile.file);
                }
                cenOff = cenOff64;

                // Don't include Zip64 end of central directory header in central directory entries to read
                entriesTotSizeL = cenSize - 20;
            } else {
                // There was no Zip64 end of central directory header
                entriesTotSizeL = cenSize;
            }

            // Get offset of first local file header
            final long locPos = cenPos - cenOff;
            if (locPos < 0) {
                throw new IOException(
                        "Local file header offset out of range: " + locPos + ": " + physicalZipFile.file);
            }

            // Read entries into a byte array, if central directory is smaller than 2GB. If central directory
            // is larger than 2GB, need to read each entry field from the file directly using ZipFileSliceReader.
            final byte[] entryBytes = entriesTotSizeL > Integer.MAX_VALUE ? null : new byte[(int) entriesTotSizeL];
            if (entryBytes != null) {
                zipFileSliceReader.read(cenPos, entryBytes, 0, (int) entriesTotSizeL, log);
            }

            if (numEnt == -1L) {
                // numEnt and numEnt64 were inconsistent -- manually count entries
                numEnt = 0;
                for (long entOff = 0; entOff + 46 <= entriesTotSizeL;) {
                    final int sig = entryBytes != null ? getInt(entryBytes, entOff)
                            : zipFileSliceReader.getInt(cenPos + entOff, log);
                    if (sig != 0x02014b50) {
                        throw new IOException("Invalid central directory signature: 0x" + Integer.toString(sig, 16)
                                + ": " + physicalZipFile.file);
                    }
                    final int filenameLen = entryBytes != null ? getShort(entryBytes, entOff + 28)
                            : zipFileSliceReader.getShort(cenPos + entOff + 28, log);
                    final int extraFieldLen = entryBytes != null ? getShort(entryBytes, entOff + 30)
                            : zipFileSliceReader.getShort(cenPos + entOff + 30, log);
                    final int commentLen = entryBytes != null ? getShort(entryBytes, entOff + 32)
                            : zipFileSliceReader.getShort(cenPos + entOff + 32, log);
                    entOff += 46 + filenameLen + extraFieldLen + commentLen;
                    numEnt++;
                }
            }

            //  Can't have more than Integer.MAX_VALUE entries, since they are stored in an ArrayList
            if (numEnt > Integer.MAX_VALUE) {
                // One alternative in this (impossibly rare) situation would be to return only the first 2B entries
                throw new IOException("Too many zipfile entries: " + numEnt + " > 2B");
            }

            // Make sure there's no DoS attack vector by using a fake number of entries
            if (entryBytes != null && numEnt > entryBytes.length / 46) {
                // The smallest directory entry is 46 bytes in size
                throw new IOException("Too many zipfile entries: " + numEnt + " (expected a max of "
                        + entryBytes.length / 46 + " based on central directory size)");
            }

            // Enumerate entries
            entries = new ArrayList<>((int) numEnt);
            for (long entOff = 0, entSize; entOff + 46 <= entriesTotSizeL; entOff += entSize) {
                final int sig = entryBytes != null ? getInt(entryBytes, entOff)
                        : zipFileSliceReader.getInt(cenPos + entOff, log);
                if (sig != 0x02014b50) {
                    throw new IOException("Invalid central directory signature: 0x" + Integer.toString(sig, 16)
                            + ": " + physicalZipFile.file);
                }
                final int filenameLen = entryBytes != null ? getShort(entryBytes, entOff + 28)
                        : zipFileSliceReader.getShort(cenPos + entOff + 28, log);
                final int extraFieldLen = entryBytes != null ? getShort(entryBytes, entOff + 30)
                        : zipFileSliceReader.getShort(cenPos + entOff + 30, log);
                final int commentLen = entryBytes != null ? getShort(entryBytes, entOff + 32)
                        : zipFileSliceReader.getShort(cenPos + entOff + 32, log);
                entSize = 46 + filenameLen + extraFieldLen + commentLen;

                // Get and sanitize entry name
                final long filenameStartOff = entOff + 46;
                final long filenameEndOff = filenameStartOff + filenameLen;
                if (filenameEndOff > entriesTotSizeL) {
                    if (log != null) {
                        log.log("Filename extends past end of entry -- skipping entry at offset " + entOff);
                    }
                    break;
                }
                String entryName = entryBytes != null ? getString(entryBytes, filenameStartOff, filenameLen)
                        : zipFileSliceReader.getString(cenPos + filenameStartOff, filenameLen, log);
                while (entryName.startsWith("/")) {
                    entryName = entryName.substring(1);
                }
                while (entryName.startsWith("./") || entryName.startsWith("../")) {
                    entryName = entryName.startsWith("./") ? entryName.substring(2) : entryName.substring(3);
                }
                entryName = entryName.replace("/./", "/");
                entryName = entryName.replace("/../", "/");
                if (entryName.endsWith("/")) {
                    // Skip directory entries
                    continue;
                }

                // Check entry flag bits
                final int flags = entryBytes != null ? getShort(entryBytes, entOff + 8)
                        : zipFileSliceReader.getShort(cenPos + entOff + 8, log);
                if ((flags & 1) != 0) {
                    if (log != null) {
                        log.log("Skipping encrypted zip entry: " + entryName);
                    }
                    continue;
                }

                // Check compression method
                final int compressionMethod = entryBytes != null ? getShort(entryBytes, entOff + 10)
                        : zipFileSliceReader.getShort(cenPos + entOff + 10, log);
                if (compressionMethod != /* stored */ 0 && compressionMethod != /* deflated */ 8) {
                    if (log != null) {
                        log.log("Skipping zip entry with invalid compression method " + compressionMethod + ": "
                                + entryName);
                    }
                    continue;
                }
                final boolean isDeflated = compressionMethod == /* deflated */ 8;

                // Get compressed and uncompressed size
                long compressedSize = entryBytes != null ? getInt(entryBytes, entOff + 20)
                        : zipFileSliceReader.getInt(cenPos + entOff + 20, log);
                long uncompressedSize = entryBytes != null ? getInt(entryBytes, entOff + 24)
                        : zipFileSliceReader.getInt(cenPos + entOff + 24, log);
                long pos = entryBytes != null ? getInt(entryBytes, entOff + 42)
                        : zipFileSliceReader.getInt(cenPos + entOff + 42, log);

                // Check for Zip64 header in extra fields
                if (extraFieldLen > 0) {
                    for (int extraFieldOff = 0; extraFieldOff + 4 < extraFieldLen;) {
                        final long tagOff = filenameEndOff + extraFieldOff;
                        final int tag = entryBytes != null ? getShort(entryBytes, tagOff)
                                : zipFileSliceReader.getShort(cenPos + tagOff, log);
                        final int size = entryBytes != null ? getShort(entryBytes, tagOff + 2)
                                : zipFileSliceReader.getShort(cenPos + tagOff + 2, log);
                        if (extraFieldOff + 4 + size > extraFieldLen) {
                            // Invalid size
                            break;
                        }
                        if (tag == /* EXTID_ZIP64 */ 1 && size >= 24) {
                            final long uncompressedSizeL = entryBytes != null ? getLong(entryBytes, tagOff + 4 + 0)
                                    : zipFileSliceReader.getLong(cenPos + tagOff + 4 + 0, log);
                            if (uncompressedSize == 0xffffffff) {
                                uncompressedSize = uncompressedSizeL;
                            }
                            final long compressedSizeL = entryBytes != null ? getLong(entryBytes, tagOff + 4 + 8)
                                    : zipFileSliceReader.getLong(cenPos + tagOff + 4 + 8, log);
                            if (compressedSize == 0xffffffff) {
                                compressedSize = compressedSizeL;
                            }
                            final long posL = entryBytes != null ? getLong(entryBytes, tagOff + 4 + 16)
                                    : zipFileSliceReader.getLong(cenPos + tagOff + 4 + 16, log);
                            if (pos == 0xffffffff) {
                                pos = posL;
                            }
                            break;
                        }
                        extraFieldOff += 4 + size;
                    }
                }
                if (compressedSize < 0 || pos < 0) {
                    continue;
                }

                final long locHeaderPos = locPos + pos;
                if (locHeaderPos < 0) {
                    if (log != null) {
                        log.log("Skipping zip entry with invalid loc header position: " + entryName);
                    }
                    continue;
                }
                if (locHeaderPos + 4 >= len) {
                    if (log != null) {
                        log.log("Unexpected EOF when trying to read LOC header: " + entryName);
                    }
                    continue;
                }

                // Add zip entry
                entries.add(new FastZipEntry(physicalZipFile, startOffsetWithinPhysicalZipFile, locHeaderPos,
                        entryName, isDeflated, compressedSize, uncompressedSize));
            }
        }
    }

    private static void listEntriesRecursive(final LogicalZipFile logicalZipFile, final int depth,
            final NestedJarHandler nestedJarHandler, final boolean print, final int[] counts, final LogNode log)
            throws IOException {
        counts[0]++; // Num jars
        for (final FastZipEntry ent : logicalZipFile.entries) {
            counts[1]++; // Num entries
            if (print) {
                for (int i = 0; i < depth; i++) {
                    System.out.print("  ");
                }
                System.out.print(ent.entryName + " " + ent.startOffsetWithinPhysicalZipFile + " +" + ent.len + "->"
                        + ent.uncompressedSize + " " + (ent.isDeflated ? "deflated" : "stored") + " ");
                //            try (InputStream is = ent.open(log)) {
                //                for (int b; (b = is.read()) != -1;) {
                //                    System.out.print(b < 32 || b > 126 ? '.' : (char) b);
                //                }
                //            }
                System.out.println();
            }
            if (ent.entryName.endsWith(".jar")) {
                final LogicalZipFile nestedLogicalZipFile = ent.getNestedLogicalZipFile(nestedJarHandler, log);
                listEntriesRecursive(nestedLogicalZipFile, depth + 1, nestedJarHandler, print, counts, log);
            }
        }
    }

    public static void main(final String[] args) throws IOException {
        final ClassLoader classLoader = FastZipFileReader.class.getClassLoader();

        LogNode.LOG_IN_REALTIME = true;
        final LogNode log = null; // new LogNode();
        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), log); // TODO

        {
            final File zipFile = new File(classLoader.getResource("nested-jars-level1.zip").getFile());
            final PhysicalZipFile physicalZipFile = new PhysicalZipFile(zipFile);
            final LogicalZipFile logicalZipFile = physicalZipFile.getToplevelLogicalZipFile(log);
            final int[] counts = new int[2];
            listEntriesRecursive(logicalZipFile, 0, nestedJarHandler, /* print = */ true, counts, log);
            System.out.println(counts[0] + " jars; " + counts[1] + " entries");
        }

        {
            final File zipFile = new File(
                    classLoader.getResource("spring-boot-fully-executable-jar.jar").getFile());
            final PhysicalZipFile physicalZipFile = new PhysicalZipFile(zipFile);
            final LogicalZipFile logicalZipFile = physicalZipFile.getToplevelLogicalZipFile(log);
            final long t0 = System.nanoTime();
            final int numIter = 500;
            for (int i = 0; i < numIter; i++) {
                final int[] counts = new int[2];
                listEntriesRecursive(logicalZipFile, 0, nestedJarHandler, /* print = */ false, counts, log);
                if (i == 0) {
                    System.out.println(counts[0] + " jars; " + counts[1] + " entries");
                }
            }
            // Baseline: 0.007586343742
            System.out.println((System.nanoTime() - t0) * 1.0e-9 / numIter);
        }

        while (!openedPhysicalZipFiles.isEmpty()) {
            openedPhysicalZipFiles.remove().close();
        }

        if (log != null) {
            log.flush();
        }

        final long t1 = System.nanoTime();
        new ClassGraph()
                .overrideClasspath(classLoader.getResource("spring-boot-fully-executable-jar.jar").getFile())
                .scan();
        // 0.175894356
        System.out.println((System.nanoTime() - t1) * 1.0e-9);
    }

}
