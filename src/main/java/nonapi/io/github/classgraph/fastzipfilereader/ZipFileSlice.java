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
package nonapi.io.github.classgraph.fastzipfilereader;

import java.io.IOException;

import nonapi.io.github.classgraph.WhiteBlackList.WhiteBlackListLeafname;

/** A zipfile slice (a sub-range of bytes within a PhysicalZipFile. */
public class ZipFileSlice {
    /** The parent slice, or null if this is the toplevel slice (the whole zipfile). */
    final ZipFileSlice parentZipFileSlice;
    /** The underlying physical zipfile. */
    public final PhysicalZipFile physicalZipFile;
    /** The start offset of the slice within the physical zipfile. */
    final long startOffsetWithinPhysicalZipFile;
    /** The compressed or stored size of the zipfile slice or entry. */
    final long len;
    /** For the toplevel zipfile slice, the zipfile path; For nested slices, the name of the zipfile entry. */
    String name;

    /** Create a ZipFileSlice that wraps an entire {@link PhysicalZipFile}. */
    ZipFileSlice(final PhysicalZipFile physicalZipFile) {
        this.parentZipFileSlice = null;
        this.physicalZipFile = physicalZipFile;
        this.startOffsetWithinPhysicalZipFile = 0;
        this.len = physicalZipFile.fileLen;
        this.name = physicalZipFile.getPath();
    }

    /** Create a ZipFileSlice that wraps a {@link PhysicalZipFile} extracted to a ByteBuffer in memory. */
    ZipFileSlice(final PhysicalZipFile physicalZipFileInRam, final FastZipEntry zipEntry) {
        this.parentZipFileSlice = zipEntry.parentLogicalZipFile;
        this.physicalZipFile = physicalZipFileInRam;
        this.startOffsetWithinPhysicalZipFile = 0;
        this.len = physicalZipFile.fileLen;
        this.name = zipEntry.entryName;
    }

    /** Create a ZipFileSlice that wraps a single {@link FastZipEntry}. */
    ZipFileSlice(final FastZipEntry zipEntry) throws IOException {
        this.parentZipFileSlice = zipEntry.parentLogicalZipFile;
        this.physicalZipFile = zipEntry.parentLogicalZipFile.physicalZipFile;
        this.startOffsetWithinPhysicalZipFile = zipEntry.getEntryDataStartOffsetWithinPhysicalZipFile();
        this.len = zipEntry.compressedSize;
        this.name = zipEntry.entryName;
    }

    /** Clone constructor. */
    ZipFileSlice(final ZipFileSlice other) {
        this.parentZipFileSlice = other.parentZipFileSlice;
        this.physicalZipFile = other.physicalZipFile;
        this.startOffsetWithinPhysicalZipFile = other.startOffsetWithinPhysicalZipFile;
        this.len = other.len;
        this.name = other.name;
    }

    /**
     * @return true if this zipfile slice, and all of its parent slices, are whitelisted and not blacklisted in the
     *         jarfile white/blacklist.
     */
    public boolean isWhitelistedAndNotBlacklisted(final WhiteBlackListLeafname jarWhiteBlackList) {
        if (!jarWhiteBlackList.isWhitelistedAndNotBlacklisted(name)) {
            return false;
        }
        if (parentZipFileSlice != null && !parentZipFileSlice.isWhitelistedAndNotBlacklisted(jarWhiteBlackList)) {
            return false;
        }
        return true;
    }

    /** Recursively get path in top down ancestral order. */
    private void getPath(final StringBuilder buf) {
        if (parentZipFileSlice != null) {
            parentZipFileSlice.getPath(buf);
        }
        if (buf.length() > 0) {
            buf.append("!/");
        }
        buf.append(name);
    }

    /** Get the path to this zipfile slice, e.g. "/path/to/jarfile.jar!/nestedjar1.jar!/nestedfile". */
    public String getPath() {
        final StringBuilder buf = new StringBuilder();
        getPath(buf);
        return buf.toString();
    }

    @Override
    public int hashCode() {
        return physicalZipFile.getPath().hashCode() ^ (int) startOffsetWithinPhysicalZipFile ^ (int) len;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ZipFileSlice)) {
            return false;
        }
        final ZipFileSlice o = (ZipFileSlice) obj;
        return startOffsetWithinPhysicalZipFile == o.startOffsetWithinPhysicalZipFile && len == o.len
                && this.physicalZipFile.equals(o.physicalZipFile);
    }

    @Override
    public String toString() {
        return (physicalZipFile.isDeflatedToRam ? "[ByteBuffer deflated to RAM]" : physicalZipFile.getFile())
                + " [byte range " + startOffsetWithinPhysicalZipFile + ".."
                + (startOffsetWithinPhysicalZipFile + len) + " / " + physicalZipFile.fileLen + "]";
    }
}