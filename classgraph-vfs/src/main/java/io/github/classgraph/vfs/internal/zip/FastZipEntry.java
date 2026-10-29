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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.internal.utils.VersionFinder;
import io.github.classgraph.vfs.internal.recycler.RecycledInflaterInputStream;
import io.github.classgraph.vfs.internal.slice.ArraySlice;
import io.github.classgraph.vfs.internal.slice.Slice;
import io.github.classgraph.vfs.reader.RandomAccessArrayReader;

/** A zip entry within a {@link LogicalZipFile}. */
public final class FastZipEntry implements Comparable<FastZipEntry> {
    /** The theoretical maximum deflate expansion ratio (1032:1). */
    private static final long MAX_DEFLATE_RATIO = 1032L;

    /**
     * The largest deflated size of an entry that {@link #load()} inflates in one go, rather than through a stream.
     * Above this size, reading all of the deflated bytes into an array before inflating them costs more than it
     * saves, because the deflated bytes no longer stay in the CPU cache between being read and being inflated (with
     * 1 MB entries, inflating in one go was 40% slower at 8 threads). {@link java.util.zip.ZipFile} sizes its
     * inflater input buffer with the same limit.
     */
    private static final long MAX_WHOLE_INFLATE_DEFLATED_SIZE = 64 * 1024;

    /**
     * The largest inflated size of an entry that {@link #load()} inflates in one go, which bounds the array that an
     * inflated size given by the central directory can make it allocate up front.
     */
    private static final long MAX_WHOLE_INFLATE_SIZE = 16 * 1024 * 1024;

    /** The parent logical zipfile. */
    final LogicalZipFile parentLogicalZipFile;

    /** The offset of the entry's local header relative to the parent logical zipfile. */
    private final long locHeaderPos;

    /** The zip entry path. */
    public final String entryName;

    /**
     * True if {@link #entryName} matches the verbatim name stored in the central directory (neither modified by
     * path sanitization nor replaced by an Info-ZIP Unicode path extra field).
     */
    final boolean nameIsVerbatim;

    /** True if the zip entry is deflated; false if the zip entry is stored. */
    public final boolean isDeflated;

    /** The non-negative compressed size of the zip entry, in bytes. */
    public final long compressedSize;

    /** The non-negative uncompressed size of the zip entry, in bytes. */
    public final long uncompressedSize;

    /**
     * The last modified time in epoch milliseconds, or 0L if unknown or not yet converted from the MS-DOS date and
     * time fields.
     */
    private volatile long lastModifiedTimeMillis;

    /** The last modified time in MS-DOS format, or 0 if provided by an extra field. */
    private final int lastModifiedTimeMSDOS;

    /** The last modified date in MS-DOS format, or 0 if provided by an extra field. */
    private final int lastModifiedDateMSDOS;

    /**
     * The high 16 bits of the entry's external file attributes (the Unix mode if written on a Unix-like system, or
     * 0 otherwise).
     */
    public final int fileAttributes;

    /** Lazily initialized {@link Slice} for the zip entry's raw (stored or deflated) data. */
    private volatile @Nullable Slice slice;

    /**
     * The multi-release version number (&gt;= 9) for a versioned entry applicable to this JVM, or 8 for a base or
     * unversioned entry.
     */
    final int version;

    /**
     * The entry name with any applicable {@code "META-INF/versions/{versionInt}/"} prefix stripped, or
     * {@link #entryName} if unversioned or multi-release resolution is disabled.
     */
    public final String entryNameUnversioned;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param parentLogicalZipFile
     *            the parent logical zipfile containing this entry.
     * @param locHeaderPos
     *            the offset of the LOC header for this entry within the parent logical zipfile.
     * @param entryName
     *            the name of the entry.
     * @param nameIsVerbatim
     *            true if {@code entryName} is the verbatim name stored in the central directory.
     * @param isDeflated
     *            true if the entry is deflated; false if stored.
     * @param compressedSize
     *            the compressed size of the entry.
     * @param uncompressedSize
     *            the uncompressed size of the entry.
     * @param lastModifiedTimeMillis
     *            the last modified timestamp in epoch milliseconds from an extra field, or 0L if none.
     * @param lastModifiedTimeMSDOS
     *            the last modified time in MS-DOS format, or 0 if {@code lastModifiedTimeMillis} was provided.
     * @param lastModifiedDateMSDOS
     *            the last modified date in MS-DOS format, or 0 if {@code lastModifiedTimeMillis} was provided.
     * @param fileAttributes
     *            the high 16 bits of the external file attributes (Unix mode, or 0 if none).
     * @param multiReleaseVersionsEnabled
     *            if true and {@code nameIsVerbatim} is true, strip any applicable
     *            {@code "META-INF/versions/{versionInt}/"} prefix to populate {@link #entryNameUnversioned}.
     */
    FastZipEntry(final LogicalZipFile parentLogicalZipFile, final long locHeaderPos, final String entryName,
            final boolean nameIsVerbatim, final boolean isDeflated, final long compressedSize,
            final long uncompressedSize, final long lastModifiedTimeMillis, final int lastModifiedTimeMSDOS,
            final int lastModifiedDateMSDOS, final int fileAttributes, final boolean multiReleaseVersionsEnabled) {
        this.parentLogicalZipFile = parentLogicalZipFile;
        this.locHeaderPos = locHeaderPos;
        this.entryName = entryName;
        this.nameIsVerbatim = nameIsVerbatim;
        this.isDeflated = isDeflated;
        this.compressedSize = compressedSize;
        this.uncompressedSize = uncompressedSize;
        this.lastModifiedTimeMillis = lastModifiedTimeMillis;
        this.lastModifiedTimeMSDOS = lastModifiedTimeMSDOS;
        this.lastModifiedDateMSDOS = lastModifiedDateMSDOS;
        this.fileAttributes = fileAttributes;

        // Get multi-release jar version number, and strip any version prefix. An entry whose name is not the name
        // it is stored under has no version: the JVM looks a versioned entry up under the exact name
        // "META-INF/versions/{versionInt}/<name>", so it never finds one stored as, for example,
        // "META-INF/versions/{versionInt}/./<name>", and never lets it mask the base entry
        var entryVersion = 8;
        var entryNameWithoutVersionPrefix = entryName;
        if (nameIsVerbatim && entryName.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX)
                && entryName.length() > LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length() + 1) {
            // This is a multi-release jar path
            final var nextSlashIdx = entryName.indexOf('/', LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length());
            if (nextSlashIdx > 0) {
                // Get path after version number, i.e. strip "META-INF/versions/{versionInt}/" prefix
                final var versionStr = entryName.substring(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX.length(),
                        nextSlashIdx);
                // For multi-release jars, the version number has to be an int >= 9. Integer.parseInt() is slow, so
                // this is a custom implementation (this is called many times for large classpaths, and
                // Integer.parseInt() was a bit of a bottleneck, surprisingly). As in the JDK's own parser, a
                // number with a leading zero is not a version number: the JVM looks a versioned entry up by the
                // version written without one, so it never reads "META-INF/versions/09/"
                var versionInt = 0;
                if (versionStr.length() < 6 && !versionStr.isEmpty() && versionStr.charAt(0) != '0') {
                    for (var i = 0; i < versionStr.length(); i++) {
                        final var c = versionStr.charAt(i);
                        if (c < '0' || c > '9') {
                            versionInt = 0;
                            break;
                        }
                        versionInt = versionInt * 10 + c - '0';
                    }
                }
                if (versionInt != 0) {
                    entryVersion = versionInt;
                }
                // A version number that this JVM would not select -- one below 9, one above the version of the
                // running JVM, or a path segment that is not a version number at all -- names no version of
                // anything, so the entry keeps the name it is stored under, and is left for the caller to hide
                if (entryVersion < 9 || entryVersion > VersionFinder.JAVA_MAJOR_VERSION) {
                    entryVersion = 8;
                }
                if (multiReleaseVersionsEnabled && entryVersion > 8) {
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

    /**
     * Return a copy of this entry with its multi-release version prefix left unresolved (for jarfiles whose
     * manifest does not declare {@code Multi-Release: true}).
     *
     * @return a copy of this entry with {@link #entryNameUnversioned} equal to {@link #entryName}.
     */
    FastZipEntry withVersionPrefixUnresolved() {
        return new FastZipEntry(parentLogicalZipFile, locHeaderPos, entryName, nameIsVerbatim, isDeflated,
                compressedSize, uncompressedSize, lastModifiedTimeMillis, lastModifiedTimeMSDOS,
                lastModifiedDateMSDOS, fileAttributes, /* multiReleaseVersionsEnabled = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the compressed size of the entry, capped at the number of bytes from the entry's local header to the end
     * of the zipfile.
     *
     * @return the compressed size bounded by the remaining zipfile length.
     */
    public long getCompressedSizeWithinZipfile() {
        // Compared by subtraction, which cannot overflow: locHeaderPos lies within the zipfile
        return Math.min(compressedSize, parentLogicalZipFile.slice.sliceLength - locHeaderPos);
    }

    /**
     * Lazily create and return the {@link Slice} covering the entry's raw (stored or compressed) bytes. Use
     * {@link #open()}, {@link #load()}, or {@link #read()} to read the uncompressed content of the entry.
     *
     * @return the {@link Slice} covering the entry's stored bytes.
     * @throws IOException
     *             if the entry's local header is invalid or out of bounds, or if a stored entry extends past the
     *             end of the zipfile.
     */
    public Slice getRawSlice() throws IOException {
        var slice = this.slice;
        if (slice == null) {
            // The fixed-size part of the LOC header is read in one go, since each typed read of a file-backed
            // reader is a separate read of the file
            final var locHeader = new byte[30];
            if (locHeaderPos > parentLogicalZipFile.slice.sliceLength - locHeader.length
                    || parentLogicalZipFile.slice.randomAccessReader().read(locHeaderPos, locHeader, 0,
                            locHeader.length) < locHeader.length) {
                throw new IOException("Unexpected EOF when trying to read zip entry LOC header: " + entryName);
            }
            final var locHeaderReader = new RandomAccessArrayReader(locHeader, 0, locHeader.length);

            // Check header magic
            if (locHeaderReader.readInt(0) != 0x04034b50) {
                throw new IOException("Zip entry has bad LOC header: " + entryName);
            }
            // (The filename length and extra field length in the LOC header are unsigned 16-bit values, so they
            // must be read with readUnsignedShort -- reading them as signed shorts makes any length of 32768 or
            // more negative, which moves dataStartPos back before the LOC header)
            final var dataStartPos = locHeaderPos + 30 + locHeaderReader.readUnsignedShort(26)
                    + locHeaderReader.readUnsignedShort(28);
            if (dataStartPos > parentLogicalZipFile.slice.sliceLength) {
                throw new IOException("Unexpected EOF when trying to read zip entry data: " + entryName);
            }
            // (Compare the compressed size with the space left in the zipfile by subtraction, since adding it to
            // dataStartPos could overflow. Both operands are non-negative -- a negative compressed size is rejected
            // when the central directory is read.)
            final var spaceLeft = parentLogicalZipFile.slice.sliceLength - dataStartPos;
            var storedSize = compressedSize;
            if (storedSize > spaceLeft) {
                if (!isDeflated) {
                    // The compressed size of a stored entry is the only thing that marks where its content ends, so
                    // the content of an entry that runs past the end of the zipfile is not all there, and reading
                    // it must not return whatever bytes follow it instead
                    throw new IOException("Unexpected EOF when trying to read zip entry data: " + entryName);
                }
                // A deflate stream marks its own end, so the inflater stops there, and never reads the bytes that
                // follow it. The compressed size of a deflated entry is not needed to read it, so a size that runs
                // past the end of the zipfile is cut short there, as java.util.zip.ZipFile reads such an entry. If
                // the stream itself is cut short, inflating it fails at the end of the zipfile.
                storedSize = spaceLeft;
            }

            // Create a new Slice that wraps the data of the zip entry
            this.slice = slice = parentLogicalZipFile.slice.slice(dataStartPos, storedSize);
        }
        return slice;
    }

    /**
     * Open the content of the entry as an {@link InputStream}, inflating it if the entry is deflated.
     *
     * @return the input stream, which the caller must close.
     * @throws IOException
     *             if the entry could not be opened, or if the {@link io.github.classgraph.vfs.Vfs} that opened its
     *             zipfile has been closed.
     */
    public InputStream open() throws IOException {
        final var rawSlice = getRawSlice();
        final var rawInputStream = rawSlice.open();
        if (!isDeflated) {
            return rawInputStream;
        }
        final var vfs = parentLogicalZipFile.vfs;
        try {
            if (vfs.isClosed()) {
                throw new IOException("Cannot read from a jarfile after the Vfs backing it has been closed. "
                        + "This happens if the object that owns the jarfile was closed (e.g. by leaving the "
                        + "try-with-resources block it was opened in) before the entry was read or the class was "
                        + "loaded");
            }
            return new RecycledInflaterInputStream(rawInputStream, rawSlice.sliceLength, vfs.inflaterRecycler());
        } catch (final IOException | RuntimeException | Error e) {
            // The caller never sees a stream if this throws, so the raw stream has to be closed here
            try {
                rawInputStream.close();
            } catch (final IOException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
    }

    /**
     * Load the content of the entry as a byte array, inflating it if the entry is deflated.
     *
     * @return the content of the entry.
     * @throws IOException
     *             if the entry could not be read.
     */
    public byte[] load() throws IOException {
        if (!isDeflated) {
            return getRawSlice().load();
        }
        var inflatedSizeHint = inflatedSizeHint();
        final var rawSlice = getRawSlice();
        // An entry of a zipfile that is in memory is inflated in one go whatever its deflated size, since its
        // deflated bytes are inflated where they are, without being read into an array first
        if (inflatedSizeHint <= MAX_WHOLE_INFLATE_SIZE
                && (rawSlice instanceof ArraySlice || rawSlice.sliceLength <= MAX_WHOLE_INFLATE_DEFLATED_SIZE)) {
            final var content = inflateWhole((int) inflatedSizeHint);
            if (content != null) {
                return content;
            }
            // The deflated data inflates to more than the central directory says, so the inflated size is unknown
            inflatedSizeHint = -1L;
        }
        // Slice.readAllBytesAsArray closes the stream
        return Slice.readAllBytesAsArray(open(), inflatedSizeHint);
    }

    /**
     * Inflate the whole of a deflated entry in one go: the deflated bytes are read with a single read (or used in
     * place, if the zipfile is in memory), and inflated straight into an array of the inflated size given by the
     * central directory. This avoids the per-chunk reads and copies of inflating through {@link #open()}.
     *
     * @param inflatedSize
     *            the inflated size given by the central directory.
     * @return the inflated content, or null if the deflated data inflates to more than {@code inflatedSize} bytes,
     *         in which case the caller has to inflate it through a stream instead.
     * @throws IOException
     *             if the entry could not be read or inflated, or if the {@link io.github.classgraph.vfs.Vfs} that
     *             opened its zipfile has been closed.
     */
    private byte @Nullable [] inflateWhole(final int inflatedSize) throws IOException {
        final var rawSlice = getRawSlice();
        final var storedSize = (int) rawSlice.sliceLength;
        final byte[] input;
        final int inputStart;
        if (rawSlice instanceof final ArraySlice arraySlice) {
            input = arraySlice.arr;
            inputStart = (int) arraySlice.sliceStartPos;
        } else {
            input = new byte[storedSize];
            inputStart = 0;
            if (storedSize > 0 && rawSlice.randomAccessReader().read(0, input, 0, storedSize) < storedSize) {
                throw new IOException("Unexpected EOF when trying to read zip entry data: " + entryName);
            }
        }
        final var vfs = parentLogicalZipFile.vfs;
        if (vfs.isClosed()) {
            throw new IOException("Cannot read from a jarfile after the Vfs backing it has been closed");
        }
        final var inflaterRecycler = vfs.inflaterRecycler();
        final var inflater = inflaterRecycler.acquire();
        try {
            inflater.setInput(input, inputStart, storedSize);
            final var output = new byte[inflatedSize];
            final var numInflatedBytes = inflate(inflater, storedSize, output);
            if (!inflater.finished() && inflate(inflater, storedSize, new byte[1]) > 0) {
                // The data inflates to more than the central directory says
                return null;
            }
            return numInflatedBytes == inflatedSize ? output : Arrays.copyOf(output, numInflatedBytes);
        } catch (final IllegalStateException e) {
            // Closing the Vfs ends the Inflater, which can happen while it is in use
            throw new IOException("Cannot read a file after the Vfs has been closed", e);
        } catch (final DataFormatException e) {
            // ZipException has no constructor that takes a cause
            final var zipException = new ZipException(
                    e.getMessage() != null ? e.getMessage() : "Invalid deflated zip entry data");
            zipException.initCause(e);
            throw zipException;
        } finally {
            inflaterRecycler.recycle(inflater);
        }
    }

    /**
     * Inflate into {@code output} until it is full or the deflated data ends, supplying the dummy byte that the
     * "nowrap" Inflater option can need at the end of its input (see ZipFile.ZipFileInflaterInputStream.fill()).
     *
     * @param inflater
     *            the {@link Inflater}, whose input has been set to the whole of the deflated data.
     * @param storedSize
     *            the length of the deflated data.
     * @param output
     *            the array to inflate into.
     * @return the number of bytes inflated.
     * @throws DataFormatException
     *             if the deflated data is invalid.
     * @throws IOException
     *             if the deflated data is cut short or needs a preset dictionary.
     */
    private static int inflate(final Inflater inflater, final int storedSize, final byte[] output)
            throws DataFormatException, IOException {
        var numInflatedBytes = 0;
        while (!inflater.finished() && numInflatedBytes < output.length) {
            final var n = inflater.inflate(output, numInflatedBytes, output.length - numInflatedBytes);
            if (n > 0) {
                numInflatedBytes += n;
            } else if (inflater.needsDictionary()) {
                // Should not happen for jarfiles
                throw new IOException("Inflater needs preset dictionary");
            } else if (inflater.needsInput()) {
                // The Inflater counts the dummy byte among the bytes it has read, so once it has read more than the
                // deflated data, the dummy byte has been used up, and the deflated data was cut short
                if (inflater.getBytesRead() > storedSize) {
                    throw new EOFException("Unexpected end of deflated zip entry data");
                }
                inflater.setInput(new byte[1]);
            }
        }
        return numInflatedBytes;
    }

    /**
     * Return the expected uncompressed size of a deflated entry for buffer sizing, capped at the maximum size that
     * the entry's stored bytes can inflate to.
     *
     * @return the expected inflated byte length hint.
     * @throws IOException
     *             if the entry's raw slice could not be read.
     */
    long inflatedSizeHint() throws IOException {
        final var storedSize = getRawSlice().sliceLength;
        return storedSize > Long.MAX_VALUE / MAX_DEFLATE_RATIO ? uncompressedSize
                : Math.min(uncompressedSize, storedSize * MAX_DEFLATE_RATIO);
    }

    /**
     * Read the content of the entry into a read-only {@link ByteBuffer}, inflating it if the entry is deflated.
     *
     * @return the content of the entry.
     * @throws IOException
     *             if the entry could not be read.
     */
    public ByteBuffer read() throws IOException {
        // There is no ByteBuffer that inflates its content on demand, so a deflated entry is inflated into an array
        return isDeflated ? ByteBuffer.wrap(load()).asReadOnlyBuffer() : getRawSlice().read();
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
        // If lastModifiedTimeMillis is zero, but there is an MSDOS date and time available (which is only the case
        // if no extra field gave the time)
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
     * Compare entries of the same zipfile in decreasing order of {@link #version}, then lexicographically by
     * {@link #entryNameUnversioned}, then by {@link #entryName}, and finally in decreasing order of
     * {@link #locHeaderPos}. Note: this ordering is inconsistent with {@link Object#equals(Object)}.
     *
     * @param o
     *            the entry to compare to
     * @return the comparison result
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
        // Of two entries with the same name, the later one in the zipfile sorts first, so that it masks the earlier
        // one: JarFile, and so a classloader, finds the last of them
        return Long.compare(o.locHeaderPos, locHeaderPos);
    }

    @Override
    public String toString() {
        // Just the path, not a URL: the zipfile this entry belongs to is not necessarily a file (it can be a
        // jarfile downloaded into memory, or a jarfile nested inside another one), and a nested jarfile's path
        // holds more than one "!/" separator, which no jar URL is allowed to hold. VfsEntry#getURI(), through
        // ArchiveRoot#resolveURI(String), is what forms the URI of an entry.
        return getPath();
    }
}
