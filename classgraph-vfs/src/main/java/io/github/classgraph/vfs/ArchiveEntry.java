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
 * Copyright (c) 2026 Luke Hutchison
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
package io.github.classgraph.vfs;

import java.io.IOException;
import java.io.InputStream;

import io.github.classgraph.vfs.internal.zip.FastZipEntry;

/** One entry of a jarfile that was opened by an {@link ArchiveReader}. */
public class ArchiveEntry {
    /** The archive this entry was read from. */
    private final Archive archive;

    /** The zip entry. */
    private final FastZipEntry zipEntry;

    /** The name of this entry, relative to the archive's package root. */
    private final String name;

    /**
     * Constructor.
     *
     * @param archive
     *            the archive this entry was read from.
     * @param zipEntry
     *            the zip entry.
     * @param name
     *            the name of this entry, relative to the archive's package root.
     */
    ArchiveEntry(final Archive archive, final FastZipEntry zipEntry, final String name) {
        this.archive = archive;
        this.zipEntry = zipEntry;
        this.name = name;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the name of this entry, relative to the archive's package root, with {@code '/'} as the separator and
     * no leading {@code '/'}, e.g. {@code "com/xyz/Widget.class"}.
     *
     * <p>
     * For an entry of a multi-release jarfile that is only present for some JDK versions, this is the name without
     * the {@code "META-INF/versions/<version>/"} prefix, so that the same entry has the same name whichever version
     * of it was selected.
     *
     * @return the name of the entry.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the archive this entry was read from.
     *
     * @return the archive.
     */
    public Archive getArchive() {
        return archive;
    }

    /**
     * Returns the full path of this entry: the path of the archive, then {@code "!/"}, then the entry's name within
     * the archive, including the package root and any multi-release version prefix.
     *
     * @return the path of the entry.
     */
    public String getPath() {
        return zipEntry.getPath();
    }

    /**
     * Returns the number of bytes this entry occupies in the archive, i.e. its size after compression.
     *
     * @return the compressed size in bytes.
     */
    public long getCompressedSize() {
        return zipEntry.compressedSize;
    }

    /**
     * Returns the number of bytes this entry's content occupies once decompressed.
     *
     * @return the uncompressed size in bytes.
     */
    public long getUncompressedSize() {
        return zipEntry.uncompressedSize;
    }

    /**
     * Returns the time this entry was last modified, in milliseconds since the epoch, or 0 if the archive does not
     * record it.
     *
     * @return the last modified time in milliseconds since the epoch, or 0 if unknown.
     */
    public long getLastModifiedTimeMillis() {
        return zipEntry.getLastModifiedTimeMillis();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open this entry's content as an {@link InputStream}, decompressing it if it is stored deflated. The caller
     * owns the returned stream and must close it.
     *
     * @return the content of the entry, as a stream.
     * @throws IOException
     *             if the entry could not be read, or if the {@link ArchiveReader} that opened the archive has been
     *             closed.
     */
    public InputStream open() throws IOException {
        return zipEntry.getSlice().open();
    }

    /**
     * Read this entry's whole content into a byte array, decompressing it if it is stored deflated.
     *
     * @return the content of the entry.
     * @throws IOException
     *             if the entry could not be read, or if the {@link ArchiveReader} that opened the archive has been
     *             closed.
     * @throws OutOfMemoryError
     *             if the entry is larger than the largest possible array.
     */
    public byte[] readAllBytes() throws IOException {
        try (var inputStream = open()) {
            return inputStream.readAllBytes();
        }
    }

    /**
     * Returns the full path of this entry.
     *
     * @return the entry, as a string.
     */
    @Override
    public String toString() {
        return getPath();
    }
}
