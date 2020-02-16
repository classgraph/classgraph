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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.util.Objects;

import nonapi.io.github.classgraph.fileslice.ArraySlice;
import nonapi.io.github.classgraph.fileslice.FileSlice;
import nonapi.io.github.classgraph.fileslice.Slice;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A physical zipfile, which is mmap'd using a {@link FileChannel}. */
class PhysicalZipFile {
    /** The {@link File} backing this {@link PhysicalZipFile}, if any. */
    private final File file;

    /** The path to the zipfile. */
    private final String path;

    /** The {@link Slice} for the zipfile. */
    Slice slice;

    /** The nested jar handler. */
    NestedJarHandler nestedJarHandler;

    /** The cached hashCode. */
    private int hashCode;

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
        this.slice = new FileSlice(file, nestedJarHandler, log);
    }

    /**
     * Construct a {@link PhysicalZipFile} from a byte array.
     *
     * @param arr
     *            the array containing the zipfile.
     * @param outermostFile
     *            the outermost file
     * @param path
     *            the path
     * @param nestedJarHandler
     *            the nested jar handler
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final byte[] arr, final File outermostFile, final String path,
            final NestedJarHandler nestedJarHandler) throws IOException {
        this.file = outermostFile;
        this.path = path;
        this.nestedJarHandler = nestedJarHandler;
        this.slice = new ArraySlice(arr, /* isDeflatedZipEntry = */ false, /* inflatedSizeHint = */ 0L,
                nestedJarHandler);
    }

    /**
     * Construct a {@link PhysicalZipFile} by reading from the {@link InputStream} to an array in RAM, or spill to
     * disk if the {@link InputStream} is too long.
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
    PhysicalZipFile(final InputStream inputStream, final long inputStreamLengthHint, final String path,
            final NestedJarHandler nestedJarHandler, final LogNode log) throws IOException {
        this.nestedJarHandler = nestedJarHandler;
        this.path = path;
        // Try downloading the InputStream to a byte array. If this succeeds, this will result in an ArraySlice.
        // If it fails, the InputStream will be spilled to disk, resulting in a FileSlice.
        this.slice = nestedJarHandler.readAllBytesWithSpilloverToDisk(inputStream, /* tempFileBaseName = */ path,
                inputStreamLengthHint, log);
        this.file = this.slice instanceof FileSlice ? ((FileSlice) this.slice).file : null;
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
     *
     * @return the length of the mapped file
     */
    public long length() {
        return slice.sliceLength;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = (file == null ? 0 : file.hashCode());
            if (hashCode == 0) {
                hashCode = 1;
            }
        }
        return hashCode;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof PhysicalZipFile)) {
            return false;
        }
        final PhysicalZipFile other = (PhysicalZipFile) o;
        return Objects.equals(file, other.file);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return path;
    }
}