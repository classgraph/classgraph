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

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NullSingletonException;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A physical zipfile, which is mmap'd using a {@link FileChannel}. */
class PhysicalZipFile implements Closeable {
    /** The {@link File} backing this {@link PhysicalZipFile}. */
    private final File file;

    /** The path to the zipfile. */
    private final String path;

    /** The {@link RandomAccessFile}. */
    private RandomAccessFile raf;

    /** The {@link FileChannel}. */
    private FileChannel fc;

    /** The file length. */
    final long fileLen;

    /** The number of mapped byte buffers. */
    final int numMappedByteBuffers;

    /** The cached mapped byte buffers for each 2GB chunk. */
    private ByteBuffer[] mappedByteBuffersCached;

    /** A singleton map from chunk index to byte buffer, ensuring that any given chunk is only mapped once. */
    private SingletonMap<Integer, ByteBuffer, IOException> chunkIdxToByteBuffer;

    /** The nested jar handler. */
    NestedJarHandler nestedJarHandler;

    /** True if the zipfile was deflated to RAM, rather than mapped from disk. */
    boolean isDeflatedToRam;

    /** Set to true once this {@link PhysicalZipFile} is closed. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Construct a {@link PhysicalZipFile} from a file on disk.
     *
     * @param file
     *            the file
     * @param nestedJarHandler
     *            the nested jar handler
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final File file, final NestedJarHandler nestedJarHandler) throws IOException {
        this.file = file;
        this.nestedJarHandler = nestedJarHandler;

        path = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH, file.getPath());

        if (!file.exists()) {
            throw new IOException("File does not exist: " + file);
        }
        if (!FileUtils.canRead(file)) {
            throw new IOException("Cannot read file: " + file);
        }
        if (!file.isFile()) {
            throw new IOException("Is not a file: " + file);
        }

        try {
            raf = new RandomAccessFile(file, "r");
            fileLen = raf.length();
            if (fileLen == 0L) {
                throw new IOException("Zipfile is empty: " + file);
            }
            fc = raf.getChannel();
        } catch (final IOException | SecurityException e) {
            if (raf != null) {
                raf.close();
                raf = null;
            }
            if (fc != null) {
                fc.close();
                fc = null;
            }
            throw e;
        }

        // Implement an array of MappedByteBuffers to support jarfiles >2GB in size:
        // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6347833
        numMappedByteBuffers = (int) ((fileLen + FileUtils.MAX_BUFFER_SIZE) / FileUtils.MAX_BUFFER_SIZE);
        mappedByteBuffersCached = new MappedByteBuffer[numMappedByteBuffers];
        chunkIdxToByteBuffer = new SingletonMap<Integer, ByteBuffer, IOException>() {
            @Override
            public ByteBuffer newInstance(final Integer chunkIdxI, final LogNode log) throws IOException {
                // Map the indexed 2GB chunk of the file to a MappedByteBuffer
                final long pos = chunkIdxI.longValue() * FileUtils.MAX_BUFFER_SIZE;
                final long chunkSize = Math.min(FileUtils.MAX_BUFFER_SIZE, fileLen - pos);
                MappedByteBuffer buffer = null;
                try {
                    buffer = fc.map(FileChannel.MapMode.READ_ONLY, pos, chunkSize);
                } catch (final FileNotFoundException e) {
                    throw e;
                } catch (IOException | OutOfMemoryError e) {
                    // If map failed, try calling System.gc() to free some allocated MappedByteBuffers
                    // (there is a limit to the number of mapped files -- 64k on Linux)
                    // See: http://www.mapdb.org/blog/mmap_files_alloc_and_jvm_crash/
                    System.gc();
                    // Then try calling map again
                    buffer = fc.map(FileChannel.MapMode.READ_ONLY, pos, chunkSize);
                }
                return buffer;
            }
        };
    }

    /**
     * Construct a {@link PhysicalZipFile} from a ByteBuffer in memory.
     *
     * @param byteBuffer
     *            the byte buffer
     * @param outermostFile
     *            the outermost file
     * @param path
     *            the path
     * @param nestedJarHandler
     *            the nested jar handler
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final ByteBuffer byteBuffer, final File outermostFile, final String path,
            final NestedJarHandler nestedJarHandler) throws IOException {
        this.file = outermostFile;
        this.path = path;
        this.nestedJarHandler = nestedJarHandler;
        this.isDeflatedToRam = true;

        fileLen = byteBuffer.remaining();
        if (fileLen == 0L) {
            throw new IOException("Zipfile is empty: " + path);
        }

        // Implement an array of MappedByteBuffers to support jarfiles >2GB in size:
        // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6347833
        numMappedByteBuffers = 1;
        mappedByteBuffersCached = new ByteBuffer[numMappedByteBuffers];
        mappedByteBuffersCached[0] = byteBuffer;
    }

    /**
     * Get a mmap'd chunk of the file, where chunkIdx denotes which 2GB chunk of the file to return (0 for the first
     * 2GB of the file, or for files smaller than 2GB; 1 for the 2-4GB chunk, etc.).
     * 
     * @param chunkIdx
     *            The index of the 2GB chunk to read
     * @return The {@link MappedByteBuffer} for the requested file chunk, up to 2GB in size.
     * @throws IOException
     *             If the chunk could not be mmap'd.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    ByteBuffer getByteBuffer(final int chunkIdx) throws IOException, InterruptedException {
        if (closed.get()) {
            throw new IOException(getClass().getSimpleName() + " already closed");
        }
        if (chunkIdx < 0 || chunkIdx >= mappedByteBuffersCached.length) {
            throw new IOException("Chunk index out of range");
        }
        // Fast path: only look up singleton map if mappedByteBuffersCached is null 
        if (mappedByteBuffersCached[chunkIdx] == null) {
            // This 2GB chunk has not yet been read -- mmap it (use a singleton map so that the mmap
            // doesn't happen more than once, in case of race condition)
            try {
                mappedByteBuffersCached[chunkIdx] = chunkIdxToByteBuffer.get(chunkIdx, /* log = */ null);
            } catch (final NullSingletonException e) {
                throw new IOException("Cannot get ByteBuffer chunk " + chunkIdx + " : " + e);
            }
        }
        return mappedByteBuffersCached[chunkIdx];
    }

    /**
     * Get the {@link File} for the outermost jar file of this PhysicalZipFile.
     *
     * @return the {@link File} for the outermost jar file of this PhysicalZipFile.
     */
    public File getFile() {
        return file;
    }

    /**
     * Get the path for this PhysicalZipFile, which is the file path, if it is file-backed, or a compound nested jar
     * path, if it is memory-backed.
     *
     * @return the path for this PhysicalZipFile, which is the file path, if it is file-backed, or a compound nested
     *         jar path, if it is memory-backed.
     */
    public String getPath() {
        return path;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return file.hashCode();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PhysicalZipFile)) {
            return false;
        }
        return file.equals(((PhysicalZipFile) obj).file);
    }

    /* (non-Javadoc)
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() {
        if (!closed.getAndSet(true)) {
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
            if (chunkIdxToByteBuffer != null) {
                chunkIdxToByteBuffer.clear();
                chunkIdxToByteBuffer = null;
            }
            if (mappedByteBuffersCached != null) {
                for (int i = 0; i < mappedByteBuffersCached.length; i++) {
                    if (mappedByteBuffersCached[i] != null) {
                        FileUtils.closeDirectByteBuffer(mappedByteBuffersCached[i], /* log = */ null);
                        mappedByteBuffersCached[i] = null;
                    }
                }
                mappedByteBuffersCached = null;
            }
            nestedJarHandler = null;
        }
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return path;
    }
}