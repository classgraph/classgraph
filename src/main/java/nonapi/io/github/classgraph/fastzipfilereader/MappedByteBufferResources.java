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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.NonReadableChannelException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NullSingletonException;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** Resources for a mapped file. */
public class MappedByteBufferResources {
    /** If true, a file was mapped from a {@link FileChannel}. */
    private File mappedFile;

    /** If true, the mapped file was created as a temp file when the InputStream wouldn't fit in RAM. */
    private boolean mappedFileIsTempFile;

    /** The raf. */
    private RandomAccessFile raf;

    /** The file channel. */
    private FileChannel fileChannel;

    /** The total length. */
    private long length;

    /** The cached mapped byte buffers for each 2GB chunk. */
    private AtomicReferenceArray<ByteBuffer> byteBufferChunksCached;

    /** A singleton map from chunk index to byte buffer, ensuring that any given chunk is only mapped once. */
    private SingletonMap<Integer, ByteBuffer, IOException> chunkIdxToByteBufferSingletonMap;

    /** The nested jar handler. */
    private final NestedJarHandler nestedJarHandler;

    /** Set to true once {@link #close()} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * The maximum size of a jar that is downloaded from a {@link URL}'s {@link InputStream} to RAM, before the
     * content is spilled over to a temporary file on disk.
     */
    private static final int MAX_JAR_RAM_SIZE = 64 * 1024 * 1024;

    /**
     * Read all the bytes in an {@link InputStream}, with spillover to a temporary file on disk if a maximum buffer
     * size is exceeded.
     *
     * @param inputStream
     *            The {@link InputStream}.
     * @param tempFileBaseName
     *            the source URL or zip entry that inputStream was opened from (used to name temporary file, if
     *            needed).
     * @param nestedJarHandler
     *            the nested jar handler
     * @param log
     *            the log.
     * @throws IOException
     *             If the contents could not be read.
     */
    public MappedByteBufferResources(final InputStream inputStream, final String tempFileBaseName,
            final NestedJarHandler nestedJarHandler, final LogNode log) throws IOException {
        this.nestedJarHandler = nestedJarHandler;
        final byte[] buf = new byte[MAX_JAR_RAM_SIZE];
        final int bufLength = buf.length;

        int totBytesRead = 0;
        int bytesRead = 0;
        while ((bytesRead = inputStream.read(buf, totBytesRead, bufLength - totBytesRead)) > 0) {
            // Fill buffer until nothing more can be read
            totBytesRead += bytesRead;
        }
        if (bytesRead < 0) {
            // Successfully reached end of stream -- wrap array buffer with ByteBuffer
            wrapByteBuffer(ByteBuffer.wrap(buf, 0, totBytesRead));

        } else {
            // bytesRead == 0 => ran out of buffer space, spill over to disk
            if (log != null) {
                log.log("Could not fit downloaded URL into max RAM buffer size of " + MAX_JAR_RAM_SIZE
                        + " bytes, downloading to temporary file: " + tempFileBaseName + " -> " + this.mappedFile);
            }
            try {
                this.mappedFile = nestedJarHandler.makeTempFile(tempFileBaseName, /* onlyUseLeafname = */ true);
            } catch (final IOException e) {
                if (log != null) {
                    log.log("Could not create temporary file: " + e);
                }
                throw e;
            }
            this.mappedFileIsTempFile = true;

            // Write the full buffer to the temporary file
            Files.write(this.mappedFile.toPath(), buf, StandardOpenOption.WRITE);

            // Copy the rest of the InputStream to the end of the temporary file
            try (OutputStream os = new BufferedOutputStream(
                    new FileOutputStream(this.mappedFile, /* append = */ true))) {
                for (int bytesReadCtd; (bytesReadCtd = inputStream.read(buf, 0, buf.length)) > 0;) {
                    os.write(buf, 0, bytesReadCtd);
                }
            }

            // Map the file to a MappedByteBuffer
            mapFile();
        }
    }

    /**
     * Wrap an existing ByteBuffer.
     *
     * @param byteBuffer
     *            the byte buffer
     * @param nestedJarHandler
     *            the nested jar handler
     * @throws IOException
     *             If the contents could not be read.
     */
    public MappedByteBufferResources(final ByteBuffer byteBuffer, final NestedJarHandler nestedJarHandler)
            throws IOException {
        this.nestedJarHandler = nestedJarHandler;
        // Wrap the existing byte buffer
        wrapByteBuffer(byteBuffer);
    }

    /**
     * Map a {@link File} to a {@link MappedByteBuffer}.
     *
     * @param file
     *            the file
     * @param nestedJarHandler
     *            the nested jar handler
     * @throws IOException
     *             If the contents could not be read.
     */
    public MappedByteBufferResources(final File file, final NestedJarHandler nestedJarHandler) throws IOException {
        this.nestedJarHandler = nestedJarHandler;
        this.mappedFile = file;
        // Map the file to a MappedByteBuffer
        mapFile();
    }

    /**
     * Wrap an existing single-chunk {@link ByteBuffer}.
     *
     * @param byteBuffer
     *            the {@link ByteBuffer}.
     */
    private void wrapByteBuffer(final ByteBuffer byteBuffer) {
        length = byteBuffer.remaining();
        // Put the ByteBuffer into the cache, so that the singleton map code for file mapping is never called
        byteBufferChunksCached = new AtomicReferenceArray<ByteBuffer>(1);
        byteBufferChunksCached.set(0, byteBuffer);
        // Don't set mappedFile, fileChannel or raf, they are unneeded.
    }

    /**
     * Map a {@link File} to a {@link MappedByteBuffer}.
     *
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void mapFile() throws IOException {
        try {
            raf = new RandomAccessFile(mappedFile, "r");
            length = raf.length();
            fileChannel = raf.getChannel();

        } catch (final IOException e) {
            close(/* log = */ null);
            throw e;
        } catch (final IllegalArgumentException | SecurityException e) {
            close(/* log = */ null);
            throw new IOException(e);
        }

        // Implement an array of MappedByteBuffers to support jarfiles >2GB in size:
        // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6347833
        final int numByteBufferChunks = (int) ((length + FileUtils.MAX_BUFFER_SIZE) / FileUtils.MAX_BUFFER_SIZE);
        byteBufferChunksCached = new AtomicReferenceArray<ByteBuffer>(numByteBufferChunks);
        chunkIdxToByteBufferSingletonMap = new SingletonMap<Integer, ByteBuffer, IOException>() {
            @Override
            public ByteBuffer newInstance(final Integer chunkIdxI, final LogNode log) throws IOException {
                // Map the indexed 2GB chunk of the file to a MappedByteBuffer
                final long pos = chunkIdxI.longValue() * FileUtils.MAX_BUFFER_SIZE;
                final long chunkSize = Math.min(FileUtils.MAX_BUFFER_SIZE, length - pos);

                if (fileChannel == null) {
                    // Should not happen
                    throw new IOException("Cannot map a null FileChannel");
                }

                MappedByteBuffer byteBuffer;
                try {
                    // Try mapping the FileChannel
                    byteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, pos, chunkSize);
                } catch (final IOException e) {
                    MappedByteBufferResources.this.close(log);
                    throw e;
                } catch (final NonReadableChannelException | IllegalArgumentException e) {
                    MappedByteBufferResources.this.close(log);
                    throw new IOException(e);
                } catch (final OutOfMemoryError e) {
                    try {
                        // If map failed, try calling System.gc() to free some allocated MappedByteBuffers
                        // (there is a limit to the number of mapped files -- 64k on Linux)
                        // See: http://www.mapdb.org/blog/mmap_files_alloc_and_jvm_crash/
                        System.gc();
                        System.runFinalization();
                        // Then try calling map again
                        byteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, pos, chunkSize);
                    } catch (final OutOfMemoryError e2) {
                        // Out of mappable virtual memory
                        MappedByteBufferResources.this.close(log);
                        throw new IOException(e2);
                    } catch (IOException | IllegalArgumentException e2) {
                        MappedByteBufferResources.this.close(log);
                        throw e2;
                    }
                }

                // Record that the byte buffer has been mapped
                nestedJarHandler.addMappedByteBuffer(byteBuffer);

                return byteBuffer;
            }
        };
    }

    /**
     * Get a mmap'd chunk of the file, where chunkIdx denotes which 2GB chunk of the file to return (0 for the first
     * 2GB of the file, or for files smaller than 2GB; 1 for the 2-4GB chunk, etc.).
     * 
     * @param chunkIdx
     *            The index of the 2GB chunk to read, between 0 and {@link #numChunks()} - 1.
     * @return The {@link MappedByteBuffer} for the requested file chunk, up to 2GB in size.
     * @throws IOException
     *             If the chunk could not be mmap'd.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    public ByteBuffer getByteBuffer(final int chunkIdx) throws IOException, InterruptedException {
        if (closed.get()) {
            throw new IOException(getClass().getSimpleName() + " already closed");
        }
        if (chunkIdx < 0 || chunkIdx >= numChunks()) {
            throw new IOException("Chunk index out of range");
        }
        // Fast path: only look up singleton map if mappedByteBuffersCached is null
        ByteBuffer cachedBuf = byteBufferChunksCached.get(chunkIdx);
        if (cachedBuf == null) {
            // This 2GB chunk has not yet been read -- mmap it and cache it.
            // (Use a singleton map so that the mmap doesn't happen more than once)
            if (chunkIdxToByteBufferSingletonMap == null) {
                // Should not happen
                throw new IOException("chunkIdxToByteBufferSingletonMap is null");
            }
            try {
                cachedBuf = chunkIdxToByteBufferSingletonMap.get(chunkIdx, /* log = */ null);
                byteBufferChunksCached.set(chunkIdx, cachedBuf);
            } catch (final NullSingletonException e) {
                throw new IOException("Cannot get ByteBuffer chunk " + chunkIdx + " : " + e);
            }
        }
        return cachedBuf;
    }

    /**
     * Get the mapped file (or null if an in-memory {@link ByteBuffer} was wrapped instead).
     *
     * @return the mapped file
     */
    public File getMappedFile() {
        return mappedFile;
    }

    /**
     * Get the length of the mapped file, or the initial remaining bytes in the wrapped ByteBuffer if a buffer was
     * wrapped.
     */
    public long length() {
        return length;
    }

    /**
     * Get the number of 2GB chunks that are available in this mapped file or wrapped ByteBuffer.
     */
    public int numChunks() {
        return byteBufferChunksCached == null ? 0 : byteBufferChunksCached.length();
    }

    /**
     * Free resources.
     *
     * @param log
     *            the log
     */
    public void close(final LogNode log) {
        if (!closed.getAndSet(true)) {
            if (chunkIdxToByteBufferSingletonMap != null) {
                chunkIdxToByteBufferSingletonMap.clear();
                chunkIdxToByteBufferSingletonMap = null;
            }
            if (byteBufferChunksCached != null) {
                // Only unmap bytebuffers if they came from a mapped file
                if (mappedFile != null) {
                    for (int i = 0; i < byteBufferChunksCached.length(); i++) {
                        final ByteBuffer mappedByteBuffer = byteBufferChunksCached.get(i);
                        if (mappedByteBuffer != null) {
                            nestedJarHandler.unmapByteBuffer(mappedByteBuffer, /* log = */ null);
                            byteBufferChunksCached.set(i, null);
                        }
                    }
                }
                byteBufferChunksCached = null;
            }
            if (fileChannel != null) {
                try {
                    fileChannel.close();
                } catch (final IOException e) {
                    // Ignore
                }
                fileChannel = null;
            }
            if (raf != null) {
                try {
                    raf.close();
                } catch (final IOException e) {
                    // Ignore
                }
                raf = null;
            }
            if (mappedFile != null) {
                // If mapped file was a temp file, remove it
                if (mappedFileIsTempFile) {
                    try {
                        nestedJarHandler.removeTempFile(mappedFile);
                    } catch (IOException | SecurityException e) {
                        if (log != null) {
                            log.log("Removing temporary file failed: " + mappedFile);
                        }
                    }
                }
                mappedFile = null;
            }
        }
    }
}