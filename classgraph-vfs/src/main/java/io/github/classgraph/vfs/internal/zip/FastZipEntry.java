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

import java.io.IOException;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.vfs.internal.slice.Slice;
import org.jspecify.annotations.Nullable;

/** A zip entry within a {@link LogicalZipFile}. */
public class FastZipEntry implements Comparable<FastZipEntry> {
    /** The parent logical zipfile. */
    final LogicalZipFile parentLogicalZipFile;

    /**
     * The offset of the entry's local header, as an offset relative to the parent logical zipfile.
     */
    private final long locHeaderPos;

    /** The zip entry path. */
    public final String entryName;

    /** True if the zip entry is deflated; false if the zip entry is stored. */
    final boolean isDeflated;

    /** The compressed size of the zip entry, in bytes. */
    public final long compressedSize;

    /** The uncompressed size of the zip entry, in bytes. */
    public final long uncompressedSize;

    /**
     * The last modified millis since the epoch, or 0L if it is unknown. Volatile, since it caches the conversion of
     * the MSDOS date and time, and the entries of a zipfile are shared between the threads reading it: a long field
     * that is not volatile may be written in two halves, so a thread that read a value another thread was caching
     * could read half of one value and half of another.
     */
    private volatile long lastModifiedTimeMillis;

    /**
     * The last modified time in MSDOS format, if {@link FastZipEntry#lastModifiedTimeMillis} is 0L.
     */
    private final int lastModifiedTimeMSDOS;

    /**
     * The last modified date in MSDOS format, if {@link FastZipEntry#lastModifiedTimeMillis} is 0L.
     */
    private final int lastModifiedDateMSDOS;

    /** The file attributes for this resource, or 0 if unknown. */
    public final int fileAttributes;

    /**
     * The {@link Slice} for the zip entry's raw data (which can be either stored or deflated), or null until
     * {@link #getSlice()} is first called. Volatile, so that a {@link Slice} created by one thread is safely
     * published to any other thread that reads this field (two threads racing to initialize this field each end up
     * with an equivalent {@link Slice}, which is harmless, since a sub-slice does not own any resources).
     */
    private volatile @Nullable Slice slice;

    /**
     * The version code (&gt;= 9), or 8 for the base layer or a non-versioned jar (whether JDK 7 or 8 compatible).
     */
    final int version;

    /**
     * The unversioned entry name (i.e. entryName with "META-INF/versions/{versionInt}/" stripped)
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
     * @param enableMultiReleaseVersions
     *            If true, leave multi-release entry names unchanged, so that every version of an entry is reported
     *            separately; if false, strip any "META-INF/versions/{versionInt}/" prefix from the entry name, so
     *            that the versioned entries can mask the entries they override.
     */
    FastZipEntry(final LogicalZipFile parentLogicalZipFile, final long locHeaderPos, final String entryName,
            final boolean isDeflated, final long compressedSize, final long uncompressedSize,
            final long lastModifiedTimeMillis, final int lastModifiedTimeMSDOS, final int lastModifiedDateMSDOS,
            final int fileAttributes, final boolean enableMultiReleaseVersions) {
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
        var entryVersion = 8;
        var entryNameWithoutVersionPrefix = entryName;
        if (entryName.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX)
                && entryName.length() > LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length() + 1) {
            // This is a multi-release jar path
            final var nextSlashIdx = entryName.indexOf('/', LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length());
            if (nextSlashIdx > 0) {
                // Get path after version number, i.e. strip "META-INF/versions/{versionInt}/" prefix
                final var versionStr = entryName.substring(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length(),
                        nextSlashIdx);
                // For multi-release jars, the version number has to be an int >= 9. Integer.parseInt() is slow, so
                // this is a custom implementation (this is called many times for large classpaths, and
                // Integer.parseInt() was a bit of a bottleneck, surprisingly)
                var versionInt = 0;
                if (versionStr.length() < 6 && !versionStr.isEmpty()) {
                    for (var i = 0; i < versionStr.length(); i++) {
                        final var c = versionStr.charAt(i);
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
                if (!enableMultiReleaseVersions && entryVersion > 8) {
                    // Strip version path prefix
                    entryNameWithoutVersionPrefix = entryName.substring(nextSlashIdx + 1);
                    // For META-INF/versions/{versionInt}/META-INF/*, don't strip version prefix: "The intention is
                    // that the META-INF directory cannot be versioned."
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
     * @return the {@link Slice} covering the entry's raw data, which is still deflated if the entry is deflated.
     * @throws IOException
     *             If an I/O exception occurs.
     */
    public Slice getSlice() throws IOException {
        var slice = this.slice;
        if (slice == null) {
            final var randomAccessReader = parentLogicalZipFile.slice.randomAccessReader();

            // Check header magic
            if (randomAccessReader.readInt(locHeaderPos) != 0x04034b50) {
                throw new IOException("Zip entry has bad LOC header: " + entryName);
            }
            // (The filename length and extra field length in the LOC header are unsigned 16-bit values, so they
            // must be read with readUnsignedShort -- reading them as signed shorts makes any length of 32768 or
            // more negative, which moves dataStartPos back before the LOC header)
            final var dataStartPos = locHeaderPos + 30 + randomAccessReader.readUnsignedShort(locHeaderPos + 26)
                    + randomAccessReader.readUnsignedShort(locHeaderPos + 28);
            // The entry's data has to lie entirely within the zipfile. (Test the compressed size against the space
            // left in the zipfile by subtraction, since adding it to dataStartPos could overflow. Both operands are
            // non-negative -- a negative compressed size is rejected when the central directory is read.)
            if (dataStartPos > parentLogicalZipFile.slice.sliceLength
                    || compressedSize > parentLogicalZipFile.slice.sliceLength - dataStartPos) {
                throw new IOException("Unexpected EOF when trying to read zip entry data: " + entryName);
            }

            // Create a new Slice that wraps just the data of the zip entry, and mark whether it is deflated
            this.slice = slice = parentLogicalZipFile.slice.slice(dataStartPos, compressedSize, isDeflated,
                    uncompressedSize);
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
    public long getLastModifiedMillis() {
        var lastModifiedMillis = lastModifiedTimeMillis;
        // If lastModifiedTimeMillis is zero, but there is an MSDOS date and time available
        if (lastModifiedMillis == 0L && (lastModifiedDateMSDOS != 0 || lastModifiedTimeMSDOS != 0)) {
            // Convert from MS-DOS Date & Time Format to Epoch millis
            final var lastModifiedSecond = (lastModifiedTimeMSDOS & 0b11111) * 2;
            final var lastModifiedMinute = lastModifiedTimeMSDOS >> 5 & 0b111111;
            final var lastModifiedHour = lastModifiedTimeMSDOS >> 11;
            final var lastModifiedDay = lastModifiedDateMSDOS & 0b11111;
            final var lastModifiedMonth = (lastModifiedDateMSDOS >> 5 & 0b1111) - 1;
            final var lastModifiedYear = (lastModifiedDateMSDOS >> 9) + 1980;

            // The year, month and day of an MS-DOS date are Gregorian, so the calendar has to be a Gregorian one.
            // Locale.ROOT is what asks for that: the default locale can be one whose calendar system is not
            // Gregorian, such as th-TH-u-ca-buddhist or ja-JP-u-ca-japanese, and such a calendar would read the
            // same year as a year of a different era.
            final var lastModifiedCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
            lastModifiedCalendar.set(lastModifiedYear, lastModifiedMonth, lastModifiedDay, lastModifiedHour,
                    lastModifiedMinute, lastModifiedSecond);
            lastModifiedCalendar.set(Calendar.MILLISECOND, 0);

            // Cache converted time by overwriting the zero lastModifiedTimeMillis field
            lastModifiedTimeMillis = lastModifiedMillis = lastModifiedCalendar.getTimeInMillis();
        }

        // Return the last modified time, or 0L if it is totally unknown.
        return lastModifiedMillis;
    }

    /**
     * Sort in decreasing order of version number, then lexicographically increasing order of unversioned entry
     * path.
     *
     * <p>
     * This orders the entries of a single zipfile, which is the only way entries are ever sorted. It ignores which
     * zipfile an entry belongs to, whereas {@link #equals(Object)} does not, so a sorted set or map must not be
     * given the entries of more than one zipfile: identically named entries of two zipfiles compare equal, and the
     * second of them would be dropped as a duplicate.
     *
     * @param o
     *            the object to compare to
     * @return the result of comparison
     */
    @Override
    public int compareTo(final FastZipEntry o) {
        final var diff0 = o.version - this.version;
        if (diff0 != 0) {
            return diff0;
        }
        final var diff1 = entryNameUnversioned.compareTo(o.entryNameUnversioned);
        if (diff1 != 0) {
            return diff1;
        }
        final var diff2 = entryName.compareTo(o.entryName);
        if (diff2 != 0) {
            return diff2;
        }
        // In case of multiple entries with the same entry name, return them in consecutive order of location, so
        // that the earliest entry overrides later entries (this is an arbitrary decision for consistency)
        return Long.compare(locHeaderPos, o.locHeaderPos);
    }

    @Override
    public int hashCode() {
        return parentLogicalZipFile.hashCode() ^ version ^ entryName.hashCode() ^ (int) locHeaderPos;
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof final FastZipEntry other)) {
            return false;
        }
        return this.parentLogicalZipFile.equals(other.parentLogicalZipFile) && this.compareTo(other) == 0;
    }

    @Override
    public String toString() {
        // Just the path, not a URL: the zipfile this entry belongs to is not necessarily a file (it can be a jarfile
        // downloaded into memory, or a jarfile nested inside another one), and a nested jarfile's path holds more
        // than one "!/" separator, which no jar URL is allowed to hold. ArchiveEntry#getURI() is what forms the URL
        // of an entry.
        return getPath();
    }
}
