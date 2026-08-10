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
import java.nio.file.Path;
import java.util.Objects;

import nonapi.io.github.classgraph.fileslice.FileSlice;
import nonapi.io.github.classgraph.fileslice.PathSlice;
import nonapi.io.github.classgraph.fileslice.ScanResources;
import nonapi.io.github.classgraph.fileslice.Slice;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * A physical zipfile, backed by a {@link File} (which may be mmap'd using a {@link FileChannel}), a {@link Path},
 * or a byte array in RAM.
 */
class PhysicalZipFile {
    /** The {@link Path} backing this {@link PhysicalZipFile}, if any. */
    private @Nullable Path path;

    /** The {@link File} backing this {@link PhysicalZipFile}, if any. */
    private @Nullable File file;

    /** The path to the zipfile. */
    private final String pathStr;

    /** The {@link Slice} for the zipfile. */
    Slice slice;

    /**
     * Construct a {@link PhysicalZipFile} from a file on disk.
     *
     * @param file
     *            the file
     * @param scanResources
     *            the resources owned by the scan
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final File file, final ScanResources scanResources, final @Nullable LogNode log)
            throws IOException {
        this.file = file;
        this.pathStr = FastPathResolver.resolve(FileUtils.currDirPath(), file.getPath());
        this.slice = new FileSlice(file, scanResources, log);
    }

    /**
     * Construct a {@link PhysicalZipFile} from a {@link Path}.
     *
     * @param path
     *            the path
     * @param scanResources
     *            the resources owned by the scan
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final Path path, final ScanResources scanResources, final @Nullable LogNode log)
            throws IOException {
        this.path = path;
        this.pathStr = FastPathResolver.resolve(FileUtils.currDirPath(), path.toString());
        this.slice = new PathSlice(path, scanResources);
    }

    /**
     * Construct a {@link PhysicalZipFile} by reading from the {@link InputStream} to an array in RAM, or spill to
     * disk if the {@link InputStream} is too long.
     *
     * @param inputStream
     *            the input stream
     * @param inputStreamLengthHint
     *            The number of bytes to read in inputStream, or -1 if unknown.
     * @param pathStr
     *            the source URL the InputStream was opened from, or the zip entry path of this entry in the parent
     *            zipfile
     * @param scanResources
     *            the resources owned by the scan
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if an I/O exception occurs.
     */
    PhysicalZipFile(final InputStream inputStream, final long inputStreamLengthHint, final String pathStr,
            final ScanResources scanResources, final @Nullable LogNode log) throws IOException {
        this.pathStr = pathStr;
        // Try downloading the InputStream to a byte array. If this succeeds, this will result in an ArraySlice. If
        // it fails, the InputStream will be spilled to disk, resulting in a FileSlice.
        this.slice = Slice.fromInputStream(inputStream, /* tempFileBaseName = */ pathStr, inputStreamLengthHint,
                scanResources, log);
        this.file = this.slice instanceof final FileSlice fileSlice ? fileSlice.file : null;
    }

    /**
     * Get the {@link Path} for the outermost jar file of this PhysicalZipFile.
     *
     * @return the {@link Path} for the outermost jar file of this PhysicalZipFile, or null if this file was
     *         downloaded from a URL directly to RAM, or is backed by a {@link File}.
     */
    public @Nullable Path getPath() {
        return path;
    }

    /**
     * Get the {@link File} for the outermost jar file of this PhysicalZipFile.
     *
     * @return the {@link File} for the outermost jar file of this PhysicalZipFile, or null if this file was
     *         downloaded from a URL directly to RAM, or is backed by a {@link Path}.
     */
    public @Nullable File getFile() {
        return file;
    }

    /**
     * Get the path for this PhysicalZipFile, which is the file path, if it is file-backed, or a compound nested jar
     * path, if it is memory-backed.
     *
     * @return the path for this PhysicalZipFile, which is the file path, if it is file-backed, or a compound nested
     *         jar path, if it is memory-backed.
     */
    public String getPathString() {
        return pathStr;
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

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        // (Use pathStr for identity, not file -- file is null for Path-backed zipfiles, and is the outermost file,
        // shared between all nested jars extracted to RAM from the same outer zipfile)
        return Objects.hashCode(pathStr);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final @Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof final PhysicalZipFile other)) {
            return false;
        }
        return Objects.equals(pathStr, other.pathStr);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return pathStr;
    }
}