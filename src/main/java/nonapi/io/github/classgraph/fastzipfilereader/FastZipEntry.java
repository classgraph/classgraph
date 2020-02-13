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

import java.io.IOException;
import java.util.Calendar;
import java.util.TimeZone;

import nonapi.io.github.classgraph.fileslice.Slice;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;
import nonapi.io.github.classgraph.utils.VersionFinder;

/** A zip entry within a {@link LogicalZipFile}. */
public class FastZipEntry implements Comparable<FastZipEntry> {
    /** The parent logical zipfile. */
    final LogicalZipFile parentLogicalZipFile;

    /** The offset of the entry's local header, as an offset relative to the parent logical zipfile. */
    private final long locHeaderPos;

    /** The zip entry path. */
    public final String entryName;

    /** True if the zip entry is deflated; false if the zip entry is stored. */
    final boolean isDeflated;

    /** The compressed size of the zip entry, in bytes. */
    public final long compressedSize;

    /** The uncompressed size of the zip entry, in bytes. */
    public final long uncompressedSize;

    /** The last modified millis since the epoch, or 0L if it is unknown */
    private long lastModifiedTimeMillis;

    /** The last modified time in MSDOS format, if {@link FastZipEntry#lastModifiedTimeMillis} is 0L. */
    private final int lastModifiedTimeMSDOS;

    /** The last modified date in MSDOS format, if {@link FastZipEntry#lastModifiedTimeMillis} is 0L. */
    private final int lastModifiedDateMSDOS;

    /** The file attributes for this resource, or 0 if unknown */
    public final int fileAttributes;

    /** The {@link Slice} for the zip entry's raw data (which can be either stored or deflated). */
    private Slice slice;

    /**
     * The version code (&gt;= 9), or 8 for the base layer or a non-versioned jar (whether JDK 7 or 8 compatible).
     */
    final int version;

    /**
     * The unversioned entry name (i.e. entryName with "META_INF/versions/{versionInt}/" stripped)
     */
    public final String entryNameUnversioned;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     * 
     * @param parentLogicalZipFile
     *            The parent logical zipfile containing this entry.
     * @param locHeaderPos
     *            The offset of the LOC header for this entry within the parent logical zipfile.
     * @param entryName
     *            The name of the entry.
     * @param isDeflated
     *            True if the entry is deflated; false if the entry is stored.
     * @param compressedSize
     *            The compressed size of the entry.
     * @param uncompressedSize
     *            The uncompressed size of the entry.
     * @param lastModifiedTimeMillis
     *            The last modified date/time in millis since the epoch, or 0L if unknown (in which case, the MSDOS
     *            time and date fields will be provided).
     * @param lastModifiedTimeMSDOS
     *            The last modified date, in MSDOS format, if lastModifiedMillis is 0L.
     * @param lastModifiedDateMSDOS
     *            The last modified date, in MSDOS format, if lastModifiedMillis is 0L.
     * @param fileAttributes
     *            The POSIX file attribute bits from the zip entry.
     */
    FastZipEntry(final LogicalZipFile parentLogicalZipFile, final long locHeaderPos, final String entryName,
            final boolean isDeflated, final long compressedSize, final long uncompressedSize,
            final long lastModifiedTimeMillis, final int lastModifiedTimeMSDOS, final int lastModifiedDateMSDOS,
            final int fileAttributes) {
        this.parentLogicalZipFile = parentLogicalZipFile;
        this.locHeaderPos = locHeaderPos;
        this.entryName = entryName;
        this.isDeflated = isDeflated;
        this.compressedSize = compressedSize;
        this.uncompressedSize = !isDeflated && uncompressedSize < 0 ? compressedSize : uncompressedSize;
        this.lastModifiedTimeMillis = lastModifiedTimeMillis;
        this.lastModifiedTimeMSDOS = lastModifiedTimeMSDOS;
        this.lastModifiedDateMSDOS = lastModifiedDateMSDOS;
        this.fileAttributes = fileAttributes;

        // Get multi-release jar version number, and strip any version prefix
        int entryVersion = 8;
        String entryNameWithoutVersionPrefix = entryName;
        if (entryName.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX)
                && entryName.length() > LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length() + 1) {
            // This is a multi-release jar path
            final int nextSlashIdx = entryName.indexOf('/', LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length());
            if (nextSlashIdx > 0) {
                // Get path after version number, i.e. strip "META-INF/versions/{versionInt}/" prefix
                final String versionStr = entryName.substring(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length(),
                        nextSlashIdx);
                // For multi-release jars, the version number has to be an int >= 9
                // Integer.parseInt() is slow, so this is a custom implementation (this is called many times
                // for large classpaths, and Integer.parseInt() was a bit of a bottleneck, surprisingly)
                int versionInt = 0;
                if (versionStr.length() < 6 && !versionStr.isEmpty()) {
                    for (int i = 0; i < versionStr.length(); i++) {
                        final char c = versionStr.charAt(i);
                        if (c < '0' || c > '9') {
                            versionInt = 0;
                            break;
                        }
                        if (versionInt == 0) {
                            versionInt = c - '0';
                        } else {
                            versionInt = versionInt * 10 + c - '0';
                        }
                    }
                }
                if (versionInt != 0) {
                    entryVersion = versionInt;
                }
                // Set version to 8 for out-of-range version numbers or invalid paths
                if (entryVersion < 9 || entryVersion > VersionFinder.JAVA_MAJOR_VERSION) {
                    entryVersion = 8;
                }
                if (entryVersion > 8) {
                    // Strip version path prefix
                    entryNameWithoutVersionPrefix = entryName.substring(nextSlashIdx + 1);
                    // For META-INF/versions/{versionInt}/META-INF/*, don't strip version prefix:
                    // "The intention is that the META-INF directory cannot be versioned."
                    // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-October/013954.html
                    if (entryNameWithoutVersionPrefix.startsWith(LogicalZipFile.META_INF_PATH_PREFIX)) {
                        entryVersion = 8;
                        entryNameWithoutVersionPrefix = entryName;
                    }
                }
            }
        }
        this.version = entryVersion;
        this.entryNameUnversioned = entryNameWithoutVersionPrefix;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Lazily get zip entry slice -- this is deferred until zip entry data needs to be read, in order to avoid
     * randomly seeking within zipfile for every entry as the central directory is read.
     *
     * @return the offset within the physical zip file of the entry's start offset.
     * @throws IOException
     *             If an I/O exception occurs.
     */
    public Slice getSlice() throws IOException {
        if (slice == null) {
            final RandomAccessReader randomAccessReader = parentLogicalZipFile.slice.randomAccessReader();

            // Check header magic
            if (randomAccessReader.readInt(locHeaderPos) != 0x04034b50) {
                throw new IOException("Zip entry has bad LOC header: " + entryName);
            }
            final long dataStartPos = locHeaderPos + 30 + randomAccessReader.readShort(locHeaderPos + 26)
                    + randomAccessReader.readShort(locHeaderPos + 28);
            if (dataStartPos > parentLogicalZipFile.slice.sliceLength) {
                throw new IOException("Unexpected EOF when trying to read zip entry data: " + entryName);
            }

            // Create a new Slice that wraps just the data of the zip entry, and mark whether it is deflated
            slice = parentLogicalZipFile.slice.slice(dataStartPos, compressedSize, isDeflated, uncompressedSize);
        }
        return slice;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the path to this zip entry, using "!/" as a separator between the parent logical zipfile and the entry
     * name.
     *
     * @return the path of the entry
     */
    public String getPath() {
        return parentLogicalZipFile.getPath() + "!/" + entryName;
    }

    /**
     * Get the last modified time in Epoch millis, or 0L if unknown.
     *
     * @return the last modified time in Epoch millis.
     */
    public long getLastModifiedTimeMillis() {
        // If lastModifiedTimeMillis is zero, but there is an MSDOS date and time available
        if (lastModifiedTimeMillis == 0L && (lastModifiedDateMSDOS != 0 || lastModifiedTimeMSDOS != 0)) {
            // Convert from MS-DOS Date & Time Format to Epoch millis
            final int lastModifiedSecond = (lastModifiedTimeMSDOS & 0b11111) * 2;
            final int lastModifiedMinute = lastModifiedTimeMSDOS >> 5 & 0b111111;
            final int lastModifiedHour = lastModifiedTimeMSDOS >> 11;
            final int lastModifiedDay = lastModifiedDateMSDOS & 0b11111;
            final int lastModifiedMonth = (lastModifiedDateMSDOS >> 5 & 0b111) - 1;
            final int lastModifiedYear = (lastModifiedDateMSDOS >> 9) + 1980;

            final Calendar lastModifiedCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            lastModifiedCalendar.set(lastModifiedYear, lastModifiedMonth, lastModifiedDay, lastModifiedHour,
                    lastModifiedMinute, lastModifiedSecond);
            lastModifiedCalendar.set(Calendar.MILLISECOND, 0);

            // Cache converted time by overwriting the zero lastModifiedTimeMillis field
            lastModifiedTimeMillis = lastModifiedCalendar.getTimeInMillis();
        }

        // Return the last modified time, or 0L if it is totally unknown.
        return lastModifiedTimeMillis;
    }

    /**
     * Sort in decreasing order of version number, then lexicographically increasing order of unversioned entry
     * path.
     *
     * @param o
     *            the object to compare to
     * @return the result of comparison
     */
    @Override
    public int compareTo(final FastZipEntry o) {
        final int diff0 = o.version - this.version;
        if (diff0 != 0) {
            return diff0;
        }
        final int diff1 = entryNameUnversioned.compareTo(o.entryNameUnversioned);
        if (diff1 != 0) {
            return diff1;
        }
        final int diff2 = entryName.compareTo(o.entryName);
        if (diff2 != 0) {
            return diff2;
        }
        // In case of multiple entries with the same entry name, return them in consecutive order of location,
        // so that the earliest entry overrides later entries (this is an arbitrary decision for consistency)
        final long diff3 = locHeaderPos - o.locHeaderPos;
        return diff3 < 0L ? -1 : diff3 > 0L ? 1 : 0;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return parentLogicalZipFile.hashCode() ^ version ^ entryName.hashCode() ^ (int) locHeaderPos;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        } else if (!(obj instanceof FastZipEntry)) {
            return false;
        }
        final FastZipEntry other = (FastZipEntry) obj;
        return this.parentLogicalZipFile.equals(other.parentLogicalZipFile) && this.compareTo(other) == 0;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "jar:file:" + getPath();
    }
}
