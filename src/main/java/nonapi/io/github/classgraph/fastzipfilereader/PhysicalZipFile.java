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
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A physical zipfile, which is mmap'd using a {@link FileChannel}. */
class PhysicalZipFile implements Closeable {
    /** The {@link File} backing this {@link PhysicalZipFile}, if any. */
    private final File file;

    /** The path to the zipfile. */
    private final String path;

    /** The byte buffer resources. */
    private MappedByteBufferResources byteBufferResources;

    /** The nested jar handler. */
    NestedJarHandler nestedJarHandler;

    /** True if the zipfile was deflated to RAM, rather than mapped from disk. */
    boolean isDeflatedToRam;

    /** Set to true once {@link #close()} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Construct a {@link PhysicalZipFile} from a file on disk.
     *
     * @param file
     *            the file
     * @param nestedJarHandler
     *            the nested jar handler
     * @param log
     *            the log
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final File file, final NestedJarHandler nestedJarHandler, final LogNode log)
            throws IOException {
        // Make sure the File is readable and is a regular file
        FileUtils.checkCanReadAndIsFile(file);

        this.file = file;
        this.nestedJarHandler = nestedJarHandler;
        this.path = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH, file.getPath());

        // Map the file to a ByteBuffer
        this.byteBufferResources = new MappedByteBufferResources(file, nestedJarHandler, log);
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

        // Wrap the ByteBuffer
        this.byteBufferResources = new MappedByteBufferResources(byteBuffer, nestedJarHandler);
        if (this.byteBufferResources.length() == 0L) {
            throw new IOException("Zipfile is empty: " + path);
        }
    }

    /**
     * Construct a {@link PhysicalZipFile} from an InputStream, which is downloaded to a {@link ByteBuffer} in RAM,
     * or spilled to disk if the content of the InputStream is too large.
     *
     * @param inputStream
     *            the input stream
     * @param inputStreamLengthHint
     *            The number of bytes to read in inputStream, or -1 if unknown.
     * @param path
     *            the source URL the InputStream was opened from, or the zip entry path of this entry in the parent
     *            zipfile
     * @param nestedJarHandler
     *            the nested jar handler
     * @param log
     *            the log
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final InputStream inputStream, final int inputStreamLengthHint, final String path,
            final NestedJarHandler nestedJarHandler, final LogNode log)
            throws IOException {
        this.nestedJarHandler = nestedJarHandler;
        this.path = path;
        this.isDeflatedToRam = true;

        // Wrap the ByteBuffer
        this.byteBufferResources = new MappedByteBufferResources(inputStream, inputStreamLengthHint, path,
                nestedJarHandler, log);
        if (this.byteBufferResources.length() == 0L) {
            throw new IOException("Zipfile is empty: " + path);
        }
        this.file = byteBufferResources.getMappedFile();
    }

    /**
     * Get a chunk of the file, where chunkIdx denotes which 2GB chunk of the file to return (0 for the first 2GB of
     * the file, or for files smaller than 2GB; 1 for the 2-4GB chunk, etc.).
     * 
     * @param chunkIdx
     *            The index of the 2GB chunk to read
     * @return The {@link MappedByteBuffer} for the requested file chunk, up to 2GB in size.
     * @throws IOException
     *             If the chunk could not be mmap'd.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    ByteBufferWrapper getByteBuffer(final int chunkIdx) throws IOException, InterruptedException {
        return byteBufferResources.getByteBuffer(chunkIdx);
    }

    /**
     * Get the {@link File} for the outermost jar file of this PhysicalZipFile.
     *
     * @return the {@link File} for the outermost jar file of this PhysicalZipFile, or null if this file was
     *         downloaded from a URL directly to RAM.
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

    /**
     * Get the length of the mapped file, or the initial remaining bytes in the wrapped ByteBuffer if a buffer was
     * wrapped.
     */
    public long length() {
        return byteBufferResources.length();
    }

    /**
     * Get the number of 2GB chunks that are available in this PhysicalZipFile.
     */
    public int numChunks() {
        return byteBufferResources.numChunks();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return path;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return file == null ? 0 : file.hashCode();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof PhysicalZipFile)) {
            return false;
        }
        return Objects.equals(file, ((PhysicalZipFile) obj).file);
    }

    /* (non-Javadoc)
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() {
        if (!closed.getAndSet(true)) {
            if (byteBufferResources != null) {
                byteBufferResources.close(/* log = */ null);
            }
            byteBufferResources = null;
            nestedJarHandler = null;
        }
    }
}