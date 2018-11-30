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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.utils.FileUtils;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.SingletonMap;

/** A physical zipfile, which is mmap'd using a {@link FileChannel}. */
public class PhysicalZipFile implements Closeable {
    final File file;
    private RandomAccessFile raf;
    final long fileLen;
    private FileChannel fc;
    final int numMappedByteBuffers;
    private MappedByteBuffer[] mappedByteBuffersCached;
    private SingletonMap<Integer, MappedByteBuffer> chunkIdxToByteBuffer;
    final NestedJarHandler nestedJarHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    PhysicalZipFile(final File file, final NestedJarHandler nestedJarHandler) throws IOException {
        this.file = file;
        this.nestedJarHandler = nestedJarHandler;

        if (!file.exists()) {
            throw new IOException("File does not exist: " + file);
        }
        if (!FileUtils.canRead(file)) {
            throw new IOException("Cannot read file: " + file);
        }
        if (!file.isFile()) {
            throw new IOException("Is not a file: " + file);
        }

        raf = new RandomAccessFile(file, "r");
        fileLen = raf.length();
        if (fileLen == 0L) {
            throw new IOException("Zipfile is empty: " + file);
        }
        fc = raf.getChannel();

        // Implement an array of MappedByteBuffers to support jarfiles >2GB in size:
        // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6347833
        numMappedByteBuffers = (int) ((fileLen + 0xffffffffL) >> 32);
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
     */
    ByteBuffer getByteBuffer(final int chunkIdx) throws IOException {
        if (closed.get()) {
            throw new IOException(getClass().getSimpleName() + " already closed");
        }
        if (chunkIdx < 0 || chunkIdx >= mappedByteBuffersCached.length) {
            throw new IOException("Chunk index out of range");
        }
        // Fast path: only look up singleton map if mappedByteBuffersCached is null 
        if (mappedByteBuffersCached[chunkIdx] == null) {
            try {
                // This 2GB chunk has not yet been read -- mmap it (use a singleton map so that the mmap
                // doesn't happen more than once, in case of race condition)
                mappedByteBuffersCached[chunkIdx] = chunkIdxToByteBuffer.getOrCreateSingleton(chunkIdx, null);
                if (mappedByteBuffersCached[chunkIdx] == null) {
                    throw new IOException("Could not allocate chunk " + chunkIdx);
                }
            } catch (final Exception e) {
                throw new IOException(e);
            }
        }
        return mappedByteBuffersCached[chunkIdx];
    }

    /** The {@link File} for this zipfile. */
    public File getFile() {
        return file;
    }

    @Override
    public int hashCode() {
        return file.hashCode();
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof PhysicalZipFile)) {
            return false;
        }
        return file.equals(((PhysicalZipFile) obj).file);
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