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

import nonapi.io.github.classgraph.recycler.Recycler;
import nonapi.io.github.classgraph.scanspec.WhiteBlackList.WhiteBlackListLeafname;

/** A zipfile slice (a sub-range of bytes within a PhysicalZipFile. */
public class ZipFileSlice {
    /** The parent slice, or null if this is the toplevel slice (the whole zipfile). */
    private final ZipFileSlice parentZipFileSlice;
    /** The underlying physical zipfile. */
    public final PhysicalZipFile physicalZipFile;
    /** The start offset of the slice within the physical zipfile. */
    final long startOffsetWithinPhysicalZipFile;
    /** The compressed or stored size of the zipfile slice or entry. */
    final long len;
    /** For the toplevel zipfile slice, the zipfile path; For nested slices, the name/path of the zipfile entry. */
    private final String pathWithinParentZipFileSlice;
    /** A {@link Recycler} for {@link ZipFileSliceReader} instances. */
    final Recycler<ZipFileSliceReader, RuntimeException> zipFileSliceReaderRecycler;
    // N.B. if any fields are added, make sure the clone constructor below is updated

    /**
     * Create a new {@link Recycler} for {@link ZipFileSliceReader} instances.
     *
     * @return A new {@link Recycler} for {@link ZipFileSliceReader} instances.
     */
    private Recycler<ZipFileSliceReader, RuntimeException> newZipFileSliceReaderRecycler() {
        return new Recycler<ZipFileSliceReader, RuntimeException>() {
            /* (non-Javadoc)
             * @see nonapi.io.github.classgraph.concurrency.LazyReference#newInstance()
             */
            @Override
            public ZipFileSliceReader newInstance() throws RuntimeException {
                return new ZipFileSliceReader(ZipFileSlice.this);
            }
        };
    }

    /**
     * Create a ZipFileSlice that wraps a toplevel {@link PhysicalZipFile}.
     *
     * @param physicalZipFile
     *            the physical zipfile
     */
    ZipFileSlice(final PhysicalZipFile physicalZipFile) {
        this.parentZipFileSlice = null;
        this.physicalZipFile = physicalZipFile;
        this.startOffsetWithinPhysicalZipFile = 0;
        this.len = physicalZipFile.length();
        this.pathWithinParentZipFileSlice = physicalZipFile.getPath();
        this.zipFileSliceReaderRecycler = newZipFileSliceReaderRecycler();
    }

    /**
     * Create a ZipFileSlice that wraps a {@link PhysicalZipFile} extracted to a ByteBuffer in memory.
     *
     * @param physicalZipFile
     *            a physical zipfile that has been extracted to RAM
     * @param zipEntry
     *            the zip entry
     */
    ZipFileSlice(final PhysicalZipFile physicalZipFile, final FastZipEntry zipEntry) {
        this.parentZipFileSlice = zipEntry.parentLogicalZipFile;
        this.physicalZipFile = physicalZipFile;
        this.startOffsetWithinPhysicalZipFile = 0;
        this.len = physicalZipFile.length();
        this.pathWithinParentZipFileSlice = zipEntry.entryName;
        this.zipFileSliceReaderRecycler = newZipFileSliceReaderRecycler();
    }

    /**
     * Create a ZipFileSlice that wraps a single {@link FastZipEntry}.
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
        this.startOffsetWithinPhysicalZipFile = zipEntry.getEntryDataStartOffsetWithinPhysicalZipFile();
        this.len = zipEntry.compressedSize;
        this.pathWithinParentZipFileSlice = zipEntry.entryName;
        this.zipFileSliceReaderRecycler = newZipFileSliceReaderRecycler();
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
        this.startOffsetWithinPhysicalZipFile = other.startOffsetWithinPhysicalZipFile;
        this.len = other.len;
        this.pathWithinParentZipFileSlice = other.pathWithinParentZipFileSlice;
        // Reuse the recycler for clones
        this.zipFileSliceReaderRecycler = other.zipFileSliceReaderRecycler;
    }

    /**
     * Check whether this zipfile slice and all of its parent slices are whitelisted and not blacklisted in the
     * jarfile white/blacklist.
     *
     * @param jarWhiteBlackList
     *            the jar white black list
     * @return true if this zipfile slice and all of its parent slices are whitelisted and not blacklisted in the
     *         jarfile white/blacklist.
     */
    public boolean isWhitelistedAndNotBlacklisted(final WhiteBlackListLeafname jarWhiteBlackList) {
        return jarWhiteBlackList.isWhitelistedAndNotBlacklisted(pathWithinParentZipFileSlice) //
                && (parentZipFileSlice == null
                        || parentZipFileSlice.isWhitelistedAndNotBlacklisted(jarWhiteBlackList));
    }

    /**
     * Get the parent ZipFileslice, or return null if this is a toplevel slice (i.e. if this slice wraps an entire
     * physical zipfile).
     * 
     * @return the parent ZipFileslice, or null if this is a toplevel slice.
     */
    public ZipFileSlice getParentZipFileSlice() {
        return parentZipFileSlice;
    }

    /**
     * Get the name of the slice (either the entry name/path within the parent zipfile slice, or the path of the
     * physical zipfile if this slice is a toplevel slice (i.e. if this slice wraps an entire physical zipfile).
     * 
     * @return the name of the slice.
     */
    public String getPathWithinParentZipFileSlice() {
        return pathWithinParentZipFileSlice;
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
            if (buf.length() > 0) {
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
     *         from a URL directly to RAM.
     */
    public File getPhysicalFile() {
        return physicalZipFile.getFile();
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return physicalZipFile.getPath().hashCode() ^ (int) startOffsetWithinPhysicalZipFile ^ (int) len;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ZipFileSlice)) {
            return false;
        }
        final ZipFileSlice other = (ZipFileSlice) obj;
        return startOffsetWithinPhysicalZipFile == other.startOffsetWithinPhysicalZipFile && len == other.len
                && this.physicalZipFile.equals(other.physicalZipFile);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "["
                + (physicalZipFile.isDeflatedToRam ? "ByteBuffer deflated to RAM from " + getPath()
                        : physicalZipFile.getFile() == null ? "ByteBuffer downloaded to RAM from " + getPath()
                                : physicalZipFile.getFile())
                + " ; byte range: " + startOffsetWithinPhysicalZipFile + ".."
                + (startOffsetWithinPhysicalZipFile + len) + " / " + physicalZipFile.length() + "]";
    }
}