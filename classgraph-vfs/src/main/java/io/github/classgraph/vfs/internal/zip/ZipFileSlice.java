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
package io.github.classgraph.vfs.internal.zip;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import io.github.classgraph.vfs.internal.slice.Slice;
import org.jspecify.annotations.Nullable;

/** A zipfile slice (a sub-range of bytes within a {@link PhysicalZipFile}). */
public class ZipFileSlice {
    /**
     * The parent slice, or null if this is the toplevel slice (the whole zipfile).
     */
    private final @Nullable ZipFileSlice parentZipFileSlice;
    /** The underlying physical zipfile. */
    protected final PhysicalZipFile physicalZipFile;

    /**
     * For the toplevel zipfile slice, the zipfile path; For nested slices, the name/path of the zipfile entry.
     */
    private final String pathWithinParentZipFileSlice;

    /** The {@link Slice} containing the zipfile. */
    public final Slice slice;

    /**
     * Create a ZipFileSlice that wraps a toplevel {@link PhysicalZipFile}.
     *
     * @param physicalZipFile
     *            the physical zipfile
     */
    ZipFileSlice(final PhysicalZipFile physicalZipFile) {
        this.parentZipFileSlice = null;
        this.physicalZipFile = physicalZipFile;
        this.slice = physicalZipFile.slice;
        this.pathWithinParentZipFileSlice = physicalZipFile.getPathString();
    }

    /**
     * Create a ZipFileSlice that wraps a {@link PhysicalZipFile} that was extracted or inflated from a nested jar
     * to memory or disk.
     *
     * @param physicalZipFile
     *            a physical zipfile that has been extracted to RAM
     * @param zipEntry
     *            the zip entry
     */
    ZipFileSlice(final PhysicalZipFile physicalZipFile, final FastZipEntry zipEntry) {
        this.parentZipFileSlice = zipEntry.parentLogicalZipFile;
        this.physicalZipFile = physicalZipFile;
        this.slice = physicalZipFile.slice;
        this.pathWithinParentZipFileSlice = zipEntry.entryName;
    }

    /**
     * Create a ZipFileSlice that wraps a single stored (not deflated) {@link FastZipEntry}.
     *
     * @param zipEntry
     *            the zip entry
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    ZipFileSlice(final FastZipEntry zipEntry) throws IOException, InterruptedException {
        this.parentZipFileSlice = zipEntry.parentLogicalZipFile;
        this.physicalZipFile = zipEntry.parentLogicalZipFile.physicalZipFile;
        this.slice = zipEntry.getSlice();
        this.pathWithinParentZipFileSlice = zipEntry.entryName;
    }

    /**
     * Clone constructor.
     *
     * @param other
     *            the {@link ZipFileSlice} to clone.
     */
    ZipFileSlice(final ZipFileSlice other) {
        this.parentZipFileSlice = other.parentZipFileSlice;
        this.physicalZipFile = other.physicalZipFile;
        this.slice = other.slice;
        this.pathWithinParentZipFileSlice = other.pathWithinParentZipFileSlice;
    }

    /**
     * Recursively append the path in top down ancestral order.
     *
     * @param buf
     *            the buf to append the path to
     */
    private void appendPath(final StringBuilder buf) {
        if (parentZipFileSlice != null) {
            parentZipFileSlice.appendPath(buf);
            if (!buf.isEmpty()) {
                buf.append("!/");
            }
        }
        buf.append(pathWithinParentZipFileSlice);
    }

    /**
     * Get the path of this zipfile slice, e.g. "/path/to/jarfile.jar!/nestedjar1.jar".
     *
     * @return the path of this zipfile slice.
     */
    public String getPath() {
        final StringBuilder buf = new StringBuilder();
        appendPath(buf);
        return buf.toString();
    }

    /**
     * Get the physical {@link File} that this ZipFileSlice is a slice of.
     *
     * @return the physical {@link File} that this ZipFileSlice is a slice of, or null if this file was downloaded
     *         from a URL directly to RAM, or if it is a {@link Path} in a filesystem that has no {@link File} view
     *         of its files.
     */
    public @Nullable File getPhysicalFile() {
        final var path = physicalZipFile.getPath();
        if (path != null) {
            try {
                return path.toFile();
            } catch (final UnsupportedOperationException e) {
                // Filesystem supports the Path API but not the File API
                return null;
            }
        } else {
            return physicalZipFile.getFile();
        }
    }

    /**
     * Get the physical {@link Path} that this ZipFileSlice is a slice of.
     *
     * @return the physical {@link Path} that this ZipFileSlice is a slice of, or null if this file was downloaded
     *         from a URL directly to RAM.
     */
    public @Nullable Path getPhysicalPath() {
        final var path = physicalZipFile.getPath();
        if (path != null) {
            return path;
        }
        final var file = physicalZipFile.getFile();
        return file == null ? null : file.toPath();
    }

    @Override
    public boolean equals(final @Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof final ZipFileSlice other)) {
            return false;
        }
        return Objects.equals(physicalZipFile, other.physicalZipFile) && Objects.equals(slice, other.slice)
                && Objects.equals(pathWithinParentZipFileSlice, other.pathWithinParentZipFileSlice);
    }

    @Override
    public int hashCode() {
        return Objects.hash(physicalZipFile, slice, pathWithinParentZipFileSlice);
    }

    @Override
    public String toString() {
        final var path = getPath();
        final var physicalPath = physicalZipFile.getPath();
        var fileStr = physicalPath == null ? null : physicalPath.toString();
        if (fileStr == null) {
            final var physicalFile = physicalZipFile.getFile();
            fileStr = physicalFile == null ? null : physicalFile.toString();
        }
        return "[" + (fileStr != null && !fileStr.equals(path) ? path + " -> " + fileStr : path) + " ; byte range: "
                + slice.sliceStartPos + ".." + (slice.sliceStartPos + slice.sliceLength) + " / "
                + physicalZipFile.length() + "]";
    }
}
