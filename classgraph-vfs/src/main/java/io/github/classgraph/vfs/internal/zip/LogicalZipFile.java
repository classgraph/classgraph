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
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.utils.CollectionUtils;
import io.github.classgraph.base.internal.utils.StringUtils;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.internal.ManifestParser;
import io.github.classgraph.vfs.internal.slice.ArraySlice;
import io.github.classgraph.vfs.internal.slice.Slice;
import io.github.classgraph.vfs.reader.RandomAccessReader;
import org.jspecify.annotations.Nullable;

/**
 * A zipfile, read from a byte range of a {@link PhysicalZipFile}: the whole of it, for a toplevel zipfile or a
 * nested zipfile that was inflated, or the bytes of one entry of an enclosing zipfile, for a nested zipfile that
 * was stored rather than deflated.
 */
public final class LogicalZipFile {
    /**
     * The value a 32-bit central directory field holds when its real value did not fit in 32 bits, in which case
     * the real value is held by the Zip64 extended information extra field instead.
     */
    private static final long ZIP64_OVERFLOWED = 0xffffffffL;

    /**
     * Bit 11 of an entry's general purpose bit flag, the "language encoding flag", which declares that the entry's
     * name is encoded in UTF-8 rather than in the zip specification's default of IBM Code Page 437.
     */
    private static final int UTF8_NAME_FLAG_BIT = 1 << 11;

    /** The zipfile that this zipfile is nested within, or null if this is a toplevel zipfile. */
    private final @Nullable LogicalZipFile parent;

    /**
     * The physical zipfile that this zipfile is read through. This zipfile only reads through it and does not own
     * it: it is owned, and released, by the root it was opened for, which for a zipfile stored within another
     * zipfile is the root of an enclosing zipfile -- see {@link JarOpener.OpenedJar}.
     */
    private final PhysicalZipFile physicalZipFile;

    /**
     * For a toplevel zipfile, the zipfile path; for a nested zipfile, the name of the zip entry it was read from.
     */
    private final String pathWithinParent;

    /**
     * The {@link Slice} that holds the bytes of this zipfile: the whole of the physical zipfile, or, for a nested
     * zipfile that was stored rather than deflated, the byte range of the entry it is stored in.
     */
    public final Slice slice;

    /** The zipfile entries, as an unmodifiable list. */
    private final List<FastZipEntry> entries;

    /** The main section of the manifest file, or null if the zipfile has no manifest file. */
    private final @Nullable Map<String, String> manifest;

    /** If true, multi-release version prefixes are stripped from resource names, and mask the base entry. */
    private final boolean multiReleaseVersionsEnabled;

    /** The vfs that owns what is opened to read this zipfile and the jarfiles nested within it. */
    private final Vfs vfs;

    // -------------------------------------------------------------------------------------------------------------

    /** {@code "META-INF/"}. */
    static final String META_INF_PATH_PREFIX = "META-INF/";

    /** {@code "META-INF/MANIFEST.MF"}. */
    private static final String MANIFEST_PATH = META_INF_PATH_PREFIX + "MANIFEST.MF";

    /** {@code "META-INF/versions/"}. */
    public static final String MULTI_RELEASE_PATH_PREFIX = META_INF_PATH_PREFIX + "versions/";

    /** The {@code "Multi-Release"} manifest key. */
    private static final String MULTI_RELEASE_KEY = "Multi-Release";

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Construct a zipfile from a byte range of a physical zipfile, and read its central directory.
     *
     * @param parent
     *            the zipfile that this zipfile is nested within, or null if this is a toplevel zipfile
     * @param physicalZipFile
     *            the physical zipfile to read through, which this zipfile does not own
     * @param pathWithinParent
     *            for a toplevel zipfile, the zipfile path; for a nested zipfile, the name of the entry of the
     *            parent zipfile that it is read from
     * @param slice
     *            the bytes of this zipfile: the slice of {@code physicalZipFile}, or a byte range of it
     * @param vfs
     *            the {@link Vfs} that is opening this jarfile
     * @param log
     *            the log node, or null to skip logging
     * @param multiReleaseVersionsEnabled
     *            if true, multi-release version prefixes are stripped from resource names
     * @throws IOException
     *             If an I/O exception occurs.
     */
    LogicalZipFile(final @Nullable LogicalZipFile parent, final PhysicalZipFile physicalZipFile,
            final String pathWithinParent, final Slice slice, final Vfs vfs, final @Nullable LogNode log,
            final boolean multiReleaseVersionsEnabled) throws IOException {
        this.parent = parent;
        this.physicalZipFile = physicalZipFile;
        this.pathWithinParent = pathWithinParent;
        this.slice = slice;
        this.vfs = vfs;
        this.multiReleaseVersionsEnabled = multiReleaseVersionsEnabled;
        final var contents = readCentralDirectory(log);
        this.entries = contents.entries();
        this.manifest = contents.manifest();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursively append the path in top down ancestral order.
     *
     * @param buf
     *            the buf to append the path to
     */
    private void appendPath(final StringBuilder buf) {
        if (parent != null) {
            parent.appendPath(buf);
            if (!buf.isEmpty()) {
                buf.append("!/");
            }
        }
        buf.append(pathWithinParent);
    }

    /**
     * Get the path of this zipfile, e.g. "/path/to/jarfile.jar!/nestedjar1.jar".
     *
     * @return the path of this zipfile.
     */
    public String getPath() {
        final StringBuilder buf = new StringBuilder();
        appendPath(buf);
        return buf.toString();
    }

    /**
     * Get the physical {@link File} that this zipfile is read from. For a nested jarfile stored uncompressed, this
     * is the file of the jarfile that encloses it, since the nested jarfile is read in place as a byte range of
     * that file. For a jarfile that was read from a stream, downloaded from a URL or inflated from a deflated
     * nested entry, and was too large to hold in RAM, it is the temporary file the jarfile was spilled to.
     *
     * @return the physical {@link File} that this zipfile is read from, or null if the physical zipfile is held in
     *         RAM (read from a stream, downloaded from a URL, or inflated from a nested jar), or is a {@link Path}
     *         in a filesystem that has no {@link File} view of its files.
     */
    public @Nullable File getPhysicalFile() {
        final var path = physicalZipFile.getPath();
        if (path != null) {
            try {
                return path.toFile();
            } catch (final UnsupportedOperationException _) {
                // Filesystem supports the Path API but not the File API
                return null;
            }
        } else {
            return physicalZipFile.getFile();
        }
    }

    /**
     * Get the physical {@link Path} that this zipfile is read from, chosen the same way as
     * {@link #getPhysicalFile()}.
     *
     * @return the physical {@link Path} that this zipfile is read from, or null if the physical zipfile is held in
     *         RAM (read from a stream, downloaded from a URL, or inflated from a nested jar).
     */
    public @Nullable Path getPhysicalPath() {
        final var path = physicalZipFile.getPath();
        if (path != null) {
            return path;
        }
        final var file = physicalZipFile.getFile();
        return file == null ? null : file.toPath();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Open a jarfile nested within this one. Nothing here is cached: the caller decides how the nested jarfile is
     * shared. The nested jarfile is wrapped in place if it is stored, or inflated to RAM or to a temporary file if
     * it is deflated, and its central directory is then read.
     *
     * @param zipEntry
     *            the entry of this zipfile that holds the nested jarfile, found with {@link #findEntry(String)}.
     * @param log
     *            the log node, or null to skip logging
     * @return the nested jarfile, with the physical zipfile it was inflated to if it was deflated, which the caller
     *         owns. A stored nested jarfile is read in place, as a byte range of this zipfile's physical zipfile,
     *         which belongs to whatever owns this jarfile, so nothing is handed over to own for one.
     * @throws IllegalArgumentException
     *             if the entry is not an entry of this zipfile.
     * @throws IOException
     *             if the nested jarfile could not be opened.
     */
    public JarOpener.OpenedJar openNestedJar(final FastZipEntry zipEntry, final @Nullable LogNode log)
            throws IOException {
        if (zipEntry.parentLogicalZipFile != this) {
            throw new IllegalArgumentException("Zip entry " + zipEntry.entryName + " is not in " + getPath());
        }
        if (!zipEntry.isDeflated) {
            // The entry holds a stored nested zipfile, so it can be read in place, as a byte range of this zipfile.
            // Hopefully nested zipfiles are stored, not deflated, as this is the fast path.
            return new JarOpener.OpenedJar(new LogicalZipFile(this, physicalZipFile, zipEntry.entryName,
                    zipEntry.getSlice(), vfs, log, multiReleaseVersionsEnabled), /* ownedPhysicalZipFile = */ null);
        }

        // A deflated nested zipfile has to be inflated before its central directory can be read, since a zipfile
        // cannot be read from a stream (most of the time nested zipfiles are stored, not deflated, so this is rare)
        if (log != null) {
            log.log("Inflating nested zip entry: " + zipEntry + " ; uncompressed size: "
                    + zipEntry.uncompressedSize);
        }

        // Read the InputStream for the entry to a RAM buffer, or spill it to disk if it is too large. Each byte
        // inflated is taken from the allowance shared by every jarfile nested within the same toplevel zipfile,
        // so that a small crafted jarfile cannot inflate to fill the heap or the disk.
        // (The stream is opened here, so it is closed here -- PhysicalZipFile does not close what it reads.)
        final var allowance = physicalZipFile.nestedJarInflationAllowance;
        PhysicalZipFile inflatedZipFile = null;
        final var inflatedInputStream = allowance.limit(zipEntry.getSlice().open(),
                getPath() + "!/" + zipEntry.entryName);
        try (inflatedInputStream) {
            // The declared uncompressed size is only a hint, since the inflated stream is read to its end whatever
            // its length: it decides whether to inflate to RAM or straight to disk, and the initial buffer size
            inflatedZipFile = new PhysicalZipFile(inflatedInputStream, zipEntry.uncompressedSize,
                    zipEntry.entryName, /* inflatedOutOf = */ allowance, vfs, log);
        } catch (final IOException | RuntimeException | Error e) {
            if (inflatedZipFile != null) {
                // Closing the stream failed after the jarfile was inflated: release it, which gives back what it
                // took of the allowance
                inflatedZipFile.releaseUnreachable(e);
            } else {
                // Nothing that was inflated was kept, so give it all back
                allowance.giveBack(inflatedInputStream.getBytesTaken());
            }
            throw e;
        }

        try {
            return new JarOpener.OpenedJar(new LogicalZipFile(this, inflatedZipFile, zipEntry.entryName,
                    inflatedZipFile.slice, vfs, log, multiReleaseVersionsEnabled), inflatedZipFile);
        } catch (final IOException | RuntimeException | Error e) {
            // The caller gets the exception rather than the zipfile, so nothing would ever reach what was just
            // inflated
            inflatedZipFile.releaseUnreachable(e);
            throw e;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parse the manifest file of a zipfile.
     *
     * @param manifestZipEntry
     *            the manifest zip entry
     * @return the main section of the manifest, keyed case-insensitively by attribute name.
     * @throws IOException
     *             if the manifest could not be read.
     */
    private static Map<String, String> parseManifest(final FastZipEntry manifestZipEntry) throws IOException {
        try (final InputStream manifestInputStream = manifestZipEntry.getSlice().open()) {
            return ManifestParser.parse(manifestInputStream);
        }
    }

    /**
     * Get the main section of the manifest file of this zipfile.
     *
     * @return the manifest attributes, keyed case-insensitively by attribute name, or null if this zipfile has no
     *         {@code META-INF/MANIFEST.MF} entry, under that name or one that differs from it only in case.
     */
    public @Nullable Map<String, String> getManifest() {
        return manifest;
    }

    /**
     * Get the entries of this zipfile.
     *
     * @return the entries, in the order of the central directory, without directories, without the earlier of two
     *         entries with the same name, and, for a multi-release jarfile, without the versions that are masked by
     *         a newer one. Unmodifiable.
     */
    public List<FastZipEntry> getEntries() {
        return entries;
    }

    /**
     * Find a non-directory entry of this zipfile with a given name. Directory entries ending with a slash are
     * discarded when the central directory is read, so only file entries can be matched.
     *
     * <p>
     * N.B. this performs an O(N) search, because the number of classpath elements containing {@code '!'} sections
     * is assumed to be small relative to the total number of entries in all jarfiles (i.e. building a map from
     * entry path to entry for every jarfile would generally be more expensive than performing this linear search,
     * and unless the classpath is enormous, the overall time performance will not tend towards O(N^2)).
     *
     * @param entryName
     *            the entry name to search for
     * @return the matching {@link FastZipEntry}, or null if there is no entry with that name
     */
    public @Nullable FastZipEntry findEntry(final String entryName) {
        for (final FastZipEntry entry : entries) {
            // Match the unversioned name, since that is the name the entry is served under -- an entry stored only
            // under "META-INF/versions/N/" is named without that prefix once multi-release versions are resolved.
            // The unversioned name is the same as the stored name for an entry that is not versioned, and for every
            // entry when multi-release versions are not resolved
            if (entry.entryNameUnversioned.equals(entryName)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Test whether any entry of this zipfile is within a given directory, i.e. whether the directory path is a
     * prefix of any entry name.
     *
     * @param dirPath
     *            the directory path, without a trailing slash
     * @return true if at least one entry is within the directory
     */
    public boolean hasEntriesUnderDir(final String dirPath) {
        final var dirPathPrefix = dirPath + "/";
        for (final FastZipEntry entry : entries) {
            // Match the unversioned name, for the same reason as findEntry(String): a package root that exists
            // only under "META-INF/versions/N/" is a package root of the jarfile all the same
            if (entry.entryNameUnversioned.startsWith(dirPathPrefix)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The central directory of the zipfile, as located by the End Of Central Directory record.
     *
     * @param numEnt
     *            the number of entries in the central directory, or -1 if the End Of Central Directory record and
     *            its Zip64 counterpart disagreed on the number, so that the entries have to be counted manually
     * @param cenSize
     *            the size of the central directory, in bytes
     * @param cenPos
     *            the position of the central directory within the zipfile slice
     * @param locPos
     *            the position within the zipfile slice that the local file header offset of an entry is relative to
     */
    private record CentralDirectory(long numEnt, long cenSize, long cenPos, long locPos) {
    }

    /**
     * The fields of a zip entry that the entry's extra field area can override.
     */
    private static final class EntryFields {
        /** The sanitized entry name, which the Info-ZIP Unicode path extra field can replace. */
        String entryNameSanitized;

        /** The compressed size of the entry, which the Zip64 extra field can replace. */
        long compressedSize;

        /** The uncompressed size of the entry, which the Zip64 extra field can replace. */
        long uncompressedSize;

        /** The offset of the entry's local file header, which the Zip64 extra field can replace. */
        long pos;

        /** The last modified time of the entry, in milliseconds, or 0 if no extra field gives a timestamp. */
        long lastModifiedMillis;

        /** True if the Info-ZIP Unicode path extra field renamed the entry to a directory, or to nothing. */
        boolean renamedToDirectoryEntry;

        /**
         * True if the entry has a Zip64 extra field. This is not the same as any of the three fields above having
         * been replaced: a value moved into the Zip64 extra field can itself be {@link #ZIP64_OVERFLOWED}, since a
         * value of exactly that is one of the values that has to be moved there, so whether a field still holds the
         * overflow marker does not say whether it was replaced.
         */
        boolean hasZip64ExtraField;

        /** The offset of the entry's name within the central directory, before it was decoded or sanitized. */
        final long filenameStartOff;

        /** The length in bytes of the entry's name as stored in the central directory. */
        final int filenameLen;

        /**
         * Constructor.
         *
         * @param entryNameSanitized
         *            the sanitized entry name
         * @param compressedSize
         *            the compressed size of the entry
         * @param uncompressedSize
         *            the uncompressed size of the entry
         * @param pos
         *            the offset of the entry's local file header
         * @param filenameStartOff
         *            the offset of the entry's name within the central directory
         * @param filenameLen
         *            the length in bytes of the entry's name as stored in the central directory
         */
        EntryFields(final String entryNameSanitized, final long compressedSize, final long uncompressedSize,
                final long pos, final long filenameStartOff, final int filenameLen) {
            this.entryNameSanitized = entryNameSanitized;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.pos = pos;
            this.filenameStartOff = filenameStartOff;
            this.filenameLen = filenameLen;
        }
    }

    /**
     * Find the End Of Central Directory (EOCD) record, which is the last record in the zipfile, apart from the
     * zipfile comment.
     *
     * @param reader
     *            a reader for the whole zipfile slice
     * @return the position of the End Of Central Directory record within the zipfile slice
     * @throws IOException
     *             If an I/O exception occurs, or the record could not be found.
     */
    @SuppressWarnings("resource")
    private long findEndOfCentralDirectoryPos(final RandomAccessReader reader) throws IOException {
        // The zipfile comment is arbitrary bytes, and the entry data before it is arbitrary bytes too, so a
        // position that merely holds the EOCD signature is not necessarily the EOCD record. The real record is the
        // one whose 22-byte header plus its declared comment length reaches exactly the end of the slice, so
        // require that, and only fall back to the last signature in the slice if no record satisfies it
        var fallbackEocdPos = -1L;

        // Scan for End Of Central Directory (EOCD) signature. Final comment can be up to 64kB in length, so need to
        // scan back that far to determine if this is a valid zipfile. However for speed, initially just try reading
        // back a maximum of 32 characters.
        for (long i = slice.sliceLength - 22, iMin = slice.sliceLength - 22 - 32; i >= iMin && i >= 0L; --i) {
            if (reader.readUnsignedInt(i) == 0x06054b50L) {
                if (i + 22 + reader.readUnsignedShort(i + 20) == slice.sliceLength) {
                    return i;
                }
                if (fallbackEocdPos < 0L) {
                    fallbackEocdPos = i;
                }
            }
        }
        if (slice.sliceLength > 22 + 32) {
            // If EOCD signature was not found, read the last 64kB of file to RAM in a single chunk so that we can
            // scan back through it at higher speed to locate the EOCD signature. (The comment can be up to 65535
            // bytes long, and the EOCD record itself is 22 bytes long, so the record can start as far back as 65557
            // bytes from the end of the zipfile.)
            final var bytesToRead = (int) Math.min(slice.sliceLength, 22 + 65535);
            final var eocdBytes = new byte[bytesToRead];
            final var readStartOff = slice.sliceLength - bytesToRead;
            if (reader.read(readStartOff, eocdBytes, 0, bytesToRead) < bytesToRead) {
                // Should not happen
                throw new IOException("Zipfile is truncated");
            }
            try (final ArraySlice arraySlice = new ArraySlice(eocdBytes, /* isDeflatedZipEntry = */ false,
                    /* inflatedLengthHint = */ 0L, vfs)) {
                final var eocdReader = arraySlice.randomAccessReader();
                for (var i = eocdBytes.length - 22L; i >= 0L; --i) {
                    if (eocdReader.readUnsignedInt(i) == 0x06054b50L) {
                        final var eocdPos = i + readStartOff;
                        if (eocdPos + 22 + eocdReader.readUnsignedShort(i + 20) == slice.sliceLength) {
                            return eocdPos;
                        }
                        if (fallbackEocdPos < 0L) {
                            fallbackEocdPos = eocdPos;
                        }
                    }
                }
            }
        }
        if (fallbackEocdPos >= 0L) {
            // No record's comment length reached the end of the slice. Zipfiles do exist with data appended after
            // the comment (a self-extracting archive, say), or with the wrong comment length recorded, and those
            // still have to be readable, so fall back to the last EOCD signature in the slice
            return fallbackEocdPos;
        }
        throw new IOException("Jarfile central directory signature not found: " + getPath());
    }

    /**
     * Read one of the 64-bit unsigned fields of a Zip64 record.
     *
     * <p>
     * These fields are unsigned, but a Java {@code long} is signed, so a value of 2^63 or greater is read back as a
     * negative number. No real zipfile holds such a value in any of these fields, whereas a corrupted or hostile
     * zipfile can, and a negative value slips past every subsequent range check (which can only test for values
     * that are too large) and then goes on to be truncated to a positive {@code int}. Reject the value at the point
     * it is read instead.
     *
     * @param reader
     *            a reader for the zipfile
     * @param offset
     *            the offset of the field within the zipfile
     * @param fieldName
     *            the name of the field, for the exception message
     * @return the value of the field
     * @throws IOException
     *             If an I/O exception occurs, or the field's value does not fit in a signed 64-bit integer.
     */
    private long readZip64Long(final RandomAccessReader reader, final long offset, final String fieldName)
            throws IOException {
        final var val = reader.readLong(offset);
        if (val < 0) {
            throw new IOException(fieldName + " is out of range: " + Long.toUnsignedString(val) + ": " + getPath());
        }
        return val;
    }

    /**
     * Read the End Of Central Directory record, and the Zip64 End Of Central Directory record, if the zipfile has
     * one, to find the central directory.
     *
     * @param reader
     *            a reader for the whole zipfile slice
     * @param eocdPos
     *            the position of the End Of Central Directory record within the zipfile slice
     * @return the central directory
     * @throws IOException
     *             If an I/O exception occurs, or the records are inconsistent or describe an unsupported zipfile.
     */
    private CentralDirectory readEndOfCentralDirectory(final RandomAccessReader reader, final long eocdPos)
            throws IOException {
        var numEnt = (long) reader.readUnsignedShort(eocdPos + 8);
        if (reader.readUnsignedShort(eocdPos + 4) > 0 || reader.readUnsignedShort(eocdPos + 6) > 0
                || numEnt != reader.readUnsignedShort(eocdPos + 10)) {
            throw new IOException("Multi-disk jarfiles not supported: " + getPath());
        }
        var cenSize = reader.readUnsignedInt(eocdPos + 12);
        var cenOff = reader.readUnsignedInt(eocdPos + 16);
        var cenPos = eocdPos - cenSize;

        // Check for Zip64 End Of Central Directory Locator record
        final var zip64cdLocIdx = eocdPos - 20;
        if (zip64cdLocIdx >= 0 && reader.readUnsignedInt(zip64cdLocIdx) == 0x07064b50L) {
            if (reader.readUnsignedInt(zip64cdLocIdx + 4) > 0 || reader.readUnsignedInt(zip64cdLocIdx + 16) > 1) {
                throw new IOException("Multi-disk jarfiles not supported: " + getPath());
            }
            final var eocdPos64 = readZip64Long(reader, zip64cdLocIdx + 8,
                    "Zip64 end of central directory record offset");
            if (reader.readUnsignedInt(eocdPos64) != 0x06064b50L) {
                throw new IOException("Zip64 central directory at location " + eocdPos64
                        + " does not have Zip64 central directory header: " + getPath());
            }
            final var numEnt64 = readZip64Long(reader, eocdPos64 + 24, "Zip64 number of entries");
            if (reader.readUnsignedInt(eocdPos64 + 16) > 0 || reader.readUnsignedInt(eocdPos64 + 20) > 0
                    || numEnt64 != readZip64Long(reader, eocdPos64 + 32, "Zip64 total number of entries")) {
                throw new IOException("Multi-disk jarfiles not supported: " + getPath());
            }
            if (numEnt == 0xffff) {
                numEnt = numEnt64;
            } else if (numEnt != numEnt64) {
                // Entry size mismatch -- trigger manual counting of entries
                numEnt = -1L;
            }

            final var cenSize64 = readZip64Long(reader, eocdPos64 + 40, "Zip64 central directory size");
            if (cenSize == 0xffffffffL) {
                cenSize = cenSize64;
            } else if (cenSize != cenSize64) {
                throw new IOException(
                        "Mismatch in central directory size: " + cenSize + " vs. " + cenSize64 + ": " + getPath());
            }

            // Recalculate the central directory position
            cenPos = eocdPos64 - cenSize;

            final var cenOff64 = readZip64Long(reader, eocdPos64 + 48, "Zip64 central directory offset");
            if (cenOff == 0xffffffffL) {
                cenOff = cenOff64;
            } else if (cenOff != cenOff64) {
                throw new IOException(
                        "Mismatch in central directory offset: " + cenOff + " vs. " + cenOff64 + ": " + getPath());
            }
        }

        if (cenSize > eocdPos) {
            throw new IOException(
                    "Central directory size out of range: " + cenSize + " vs. " + eocdPos + ": " + getPath());
        }

        // Get offset of first local file header
        final var locPos = cenPos - cenOff;
        if (locPos < 0) {
            throw new IOException("Local file header offset out of range: " + locPos + ": " + getPath());
        }
        return new CentralDirectory(numEnt, cenSize, cenPos, locPos);
    }

    /**
     * Open a reader for the central directory, whose offsets are relative to the start of the central directory.
     *
     * @param reader
     *            a reader for the whole zipfile slice
     * @param cen
     *            the central directory
     * @return the reader
     * @throws IOException
     *             If an I/O exception occurs.
     */
    @SuppressWarnings("resource")
    private RandomAccessReader openCentralDirectoryReader(final RandomAccessReader reader,
            final CentralDirectory cen) throws IOException {
        // Read entries into a byte array, if central directory is smaller than 2GB. If central directory is larger
        // than 2GB, need to read each entry field directly from the zipfile, through a RandomAccessReader over a
        // slice of this zipfile's slice.
        if (cen.cenSize() > Slice.MAX_BUFFER_SIZE) {
            // Create a slice that covers the central directory (this allows a central directory larger than 2GB to
            // be accessed using the slower PathSlice API, which reads the file directly, but also the slice can be
            // accessed without adding cenPos to each read offset, so that this slice or the slice in the "else"
            // clause below are accessed with the same index, which is the offset from the start of the central
            // directory).
            return slice.slice(cen.cenPos(), cen.cenSize(), /* isDeflatedZipEntry = */ false,
                    /* inflatedLengthHint = */ 0L).randomAccessReader();
        }
        // Read the central directory into RAM for speed, then wrap it in an ArraySlice (random access is faster
        // for ArraySlice than for PathSlice)
        final var entryBytes = new byte[(int) cen.cenSize()];
        if (reader.read(cen.cenPos(), entryBytes, 0, (int) cen.cenSize()) < cen.cenSize()) {
            // Should not happen
            throw new IOException("Zipfile is truncated");
        }
        return new ArraySlice(entryBytes, /* isDeflatedZipEntry = */ false, /* inflatedLengthHint = */ 0L, vfs)
                .randomAccessReader();
    }

    /**
     * Count the entries of the central directory, for the rare zipfile whose End Of Central Directory record and
     * Zip64 End Of Central Directory record disagree on the number of entries.
     *
     * @param cenReader
     *            a reader for the central directory
     * @param cenSize
     *            the size of the central directory, in bytes
     * @return the number of entries
     * @throws IOException
     *             If an I/O exception occurs, or an entry does not have a central directory signature.
     */
    private long countCentralDirectoryEntries(final RandomAccessReader cenReader, final long cenSize)
            throws IOException {
        var numEnt = 0L;
        for (var entOff = 0L; entOff + 46 <= cenSize;) {
            final var sig = cenReader.readUnsignedInt(entOff);
            if (sig != 0x02014b50L) {
                throw new IOException(
                        "Invalid central directory signature: 0x" + Long.toHexString(sig) + ": " + getPath());
            }
            final var filenameLen = cenReader.readUnsignedShort(entOff + 28);
            final var extraFieldLen = cenReader.readUnsignedShort(entOff + 30);
            final var commentLen = cenReader.readUnsignedShort(entOff + 32);
            entOff += 46 + filenameLen + extraFieldLen + commentLen;
            numEnt++;
        }
        return numEnt;
    }

    /**
     * Read the extra field area of a central directory entry, overriding the fields of the entry that the extra
     * fields give a different value for. See:
     *
     * <ul>
     * <li>https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
     * <li>https://github.com/LuaDist/zip/blob/master/proginfo/extrafld.txt
     * </ul>
     *
     * @param cenReader
     *            a reader for the central directory
     * @param extraFieldStartOff
     *            the offset of the extra field area within the central directory
     * @param extraFieldLen
     *            the length of the extra field area
     * @param entryFields
     *            the fields of the entry, which are overridden in place
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             If an I/O exception occurs, or an extra field is inconsistent with the entry it belongs to.
     */
    private static void readExtraFields(final RandomAccessReader cenReader, final long extraFieldStartOff,
            final int extraFieldLen, final EntryFields entryFields, final @Nullable LogNode log)
            throws IOException {
        // The loop bound is "<=", not "<", because a field with a zero-length payload is just the 4-byte header,
        // so the last field in the area can end exactly at extraFieldLen
        for (var extraFieldOff = 0; extraFieldOff + 4 <= extraFieldLen;) {
            final var tagOff = extraFieldStartOff + extraFieldOff;
            final var tag = cenReader.readUnsignedShort(tagOff);
            final var size = cenReader.readUnsignedShort(tagOff + 2);
            if (extraFieldOff + 4 + size > extraFieldLen) {
                // An extra field that extends past the end of the extra field area cannot be read, and neither can
                // any extra field after it, since its size is what says where the next one starts. The entry itself
                // is still readable, using the values in its central directory record.
                if (log != null) {
                    log.log("Ignoring the rest of the extra fields of zip entry, which has an extra field that "
                            + "extends past the end of its extra field area: " + entryFields.entryNameSanitized);
                }
                break;
            }
            if (tag == 1) {
                readZip64ExtraField(cenReader, tagOff, size, entryFields);

            } else if (tag == 0x5455 && size >= 1 + 4) {
                // Extended timestamp: a flags byte, then the last modified time as a signed 32-bit Unix time in
                // seconds, which is only present if bit 0 of the flags is set. (In a local file header this can be
                // followed by the access time and the creation time, but in a central directory entry the last
                // modified time is the only time present, whatever the other flag bits say.)
                final var flags = cenReader.readUnsignedByte(tagOff + 4 + 0);
                if ((flags & 1) != 0) {
                    entryFields.lastModifiedMillis = cenReader.readInt(tagOff + 4 + 1) * 1000L;
                }

            } else if (tag == 0x5855 && size >= 4 + 4) {
                // Unix extra field (deprecated): the last access time, then the last modified time, both as signed
                // 32-bit Unix times in seconds.
                // There are also optional UID and GID fields in this extra field (currently ignored)
                entryFields.lastModifiedMillis = cenReader.readInt(tagOff + 4 + 4) * 1000L;

            } else if (tag == 0x7855) {
                // Info-ZIP Unix UID and GID fields (currently ignored)

            } else if (tag == 0x7075 && size >= 1) {
                // (The size test is what stops the version byte from being read out of the next extra field, or out
                // of the next central directory record, when this extra field has an empty data area)
                readUnicodePathExtraField(cenReader, tagOff, size, entryFields, log);
            }
            extraFieldOff += 4 + size;
        }
    }

    /**
     * Read a Zip64 extended information extra field, which gives the true uncompressed size, compressed size and
     * local file header offset of an entry whose value for those fields was too large to store in the 32 bits the
     * central directory record has for each of them.
     *
     * <p>
     * Each of the three values is present in this extra field only if the central directory field it belongs to
     * holds {@link #ZIP64_OVERFLOWED}, and the values that are present appear in this fixed order, so which value
     * each 8 bytes of this field holds depends on which of the central directory fields overflowed. (The three
     * values can be followed by the 4-byte number of the disk the entry starts on, which is of no use here, since
     * multi-disk zipfiles are not supported.)
     *
     * @param cenReader
     *            a reader for the central directory
     * @param tagOff
     *            the offset of the extra field's tag within the central directory
     * @param size
     *            the size of the extra field's data area
     * @param entryFields
     *            the fields of the entry, which are overridden in place
     * @throws IOException
     *             If an I/O exception occurs, or a central directory field overflowed but this extra field does not
     *             hold the value that overflowed it.
     */
    private static void readZip64ExtraField(final RandomAccessReader cenReader, final long tagOff, final int size,
            final EntryFields entryFields) throws IOException {
        entryFields.hasZip64ExtraField = true;
        var valueOff = tagOff + 4;
        final var dataEndOff = valueOff + size;
        if (entryFields.uncompressedSize == ZIP64_OVERFLOWED) {
            if (valueOff + 8 > dataEndOff) {
                throw new IOException(
                        "Zip64 extra field is missing the uncompressed size: " + entryFields.entryNameSanitized);
            }
            entryFields.uncompressedSize = cenReader.readLong(valueOff);
            valueOff += 8;
        }
        if (entryFields.compressedSize == ZIP64_OVERFLOWED) {
            if (valueOff + 8 > dataEndOff) {
                throw new IOException(
                        "Zip64 extra field is missing the compressed size: " + entryFields.entryNameSanitized);
            }
            entryFields.compressedSize = cenReader.readLong(valueOff);
            valueOff += 8;
        }
        if (entryFields.pos == ZIP64_OVERFLOWED) {
            if (valueOff + 8 > dataEndOff) {
                throw new IOException("Zip64 extra field is missing the local file header offset: "
                        + entryFields.entryNameSanitized);
            }
            entryFields.pos = cenReader.readLong(valueOff);
        }
    }

    /**
     * Check that no central directory field of an entry was left holding {@link #ZIP64_OVERFLOWED} by an entry that
     * has no Zip64 extra field at all.
     *
     * <p>
     * The overflow marker says that the real value is in the Zip64 extra field, so an entry that carries the marker
     * without carrying that extra field has no real value for the field at all. The marker must not be mistaken for
     * one: it would be read as a size or an offset of just under 4GB, which is not a value the entry could have had
     * and still have been written this way. An entry whose Zip64 extra field is present but too short to hold a
     * value that overflowed is rejected by {@link #readZip64ExtraField} instead, with the same message; this covers
     * the case where the extra field is missing entirely, which that method never sees.
     *
     * @param entryFields
     *            the fields of the entry, after every extra field has been read
     * @throws IOException
     *             If a central directory field overflowed but the entry has no Zip64 extra field.
     */
    private static void checkNoOverflowMarkerWasLeftBehind(final EntryFields entryFields) throws IOException {
        if (entryFields.hasZip64ExtraField) {
            // Every field that overflowed was either replaced, or reported as missing while the extra field was
            // being read. A field that still holds the marker was replaced by a value that is the marker, which is
            // one of the values that has to be moved into the Zip64 extra field in the first place.
            return;
        }
        if (entryFields.uncompressedSize == ZIP64_OVERFLOWED) {
            throw new IOException(
                    "Zip64 extra field is missing the uncompressed size: " + entryFields.entryNameSanitized);
        }
        if (entryFields.compressedSize == ZIP64_OVERFLOWED) {
            throw new IOException(
                    "Zip64 extra field is missing the compressed size: " + entryFields.entryNameSanitized);
        }
        if (entryFields.pos == ZIP64_OVERFLOWED) {
            throw new IOException(
                    "Zip64 extra field is missing the local file header offset: " + entryFields.entryNameSanitized);
        }
    }

    /**
     * Read an Info-ZIP Unicode path extra field, which gives the entry's name in UTF-8 when the name in the central
     * directory record is in some other encoding.
     *
     * @param cenReader
     *            a reader for the central directory
     * @param tagOff
     *            the offset of the extra field's tag within the central directory
     * @param size
     *            the size of the extra field's data area
     * @param entryFields
     *            the fields of the entry, which are overridden in place
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             If an I/O exception occurs, or the extra field's entry name is malformed.
     */
    private static void readUnicodePathExtraField(final RandomAccessReader cenReader, final long tagOff,
            final int size, final EntryFields entryFields, final @Nullable LogNode log) throws IOException {
        final var version = cenReader.readUnsignedByte(tagOff + 4 + 0);
        if (version != 1) {
            // Only version 1 is defined. The entry is still readable under the name in its central directory
            // record, which is the only name java.util.zip.ZipFile reads, so keep that name rather than rejecting
            // the whole zipfile
            if (log != null) {
                log.log("Ignoring the Unicode entry name of zip entry, since its Unicode path extra field is of "
                        + "unknown version " + version + ": " + entryFields.entryNameSanitized);
            }
        } else if (size > 5) {
            // The data area of this extra field is version(1) + nameCRC32(4) + name, so the CRC starts 1 byte into
            // the data area (i.e. 5 bytes after the tag), and the name starts 5 bytes into the data area (i.e. 9
            // bytes after the tag) and is (size - 5) bytes long.
            // The CRC is of the entry name as stored in the central directory record. A mismatch means some tool
            // renamed the entry and left this extra field behind, so it now describes a name the entry no longer
            // has -- APPNOTE says to ignore the field in that case, and keep the name in the central directory
            // record, rather than rename the entry back to a name its writer moved it away from
            final var storedNameCRC32 = cenReader.readUnsignedInt(tagOff + 5);
            final var actualNameCRC32 = crc32(cenReader, entryFields.filenameStartOff, entryFields.filenameLen);
            if (storedNameCRC32 != actualNameCRC32) {
                if (log != null) {
                    log.log("Ignoring the Unicode entry name of zip entry, since the name CRC in its Unicode path "
                            + "extra field does not match the entry name: " + entryFields.entryNameSanitized);
                }
                return;
            }
            // Replace non-Unicode entry name with Unicode version.
            // This extra field's name is always UTF-8, whatever the entry's language encoding flag says
            final var unicodeEntryName = ZipEntryNameDecoder.readEntryName(cenReader, tagOff + 9, size - 5,
                    /* isUtf8 = */ true);
            // The replacement name has to be sanitized, and tested for naming a directory, exactly as the
            // name it replaces was -- otherwise an entry can carry a path such as "pkg/../../x" or
            // "/abs/x" simply by declaring it here
            entryFields.entryNameSanitized = PathSyntax.sanitizeRelativePath(unicodeEntryName,
                    /* removeFinalSlash = */ false);
            entryFields.renamedToDirectoryEntry = entryFields.entryNameSanitized.isEmpty()
                    || unicodeEntryName.endsWith("/");
        }
    }

    /**
     * Compute the CRC-32 of a range of bytes of the central directory.
     *
     * @param cenReader
     *            a reader for the central directory
     * @param offset
     *            the offset of the range within the central directory
     * @param numBytes
     *            the length of the range in bytes
     * @return the CRC-32 of the range, as an unsigned 32-bit value
     * @throws IOException
     *             If an I/O exception occurs, or the range extends beyond the end of the central directory.
     */
    private static long crc32(final RandomAccessReader cenReader, final long offset, final int numBytes)
            throws IOException {
        final var bytes = new byte[numBytes];
        if (cenReader.read(offset, bytes, 0, numBytes) < numBytes) {
            throw new IOException("Zip entry name extends beyond the end of the central directory");
        }
        final var crc32 = new CRC32();
        crc32.update(bytes);
        return crc32.getValue();
    }

    /**
     * Read one entry of the central directory.
     *
     * @param cenReader
     *            a reader for the central directory
     * @param entOff
     *            the offset of the entry within the central directory
     * @param filenameLen
     *            the length of the entry's filename
     * @param extraFieldLen
     *            the length of the entry's extra field area
     * @param locPos
     *            the position within the zipfile slice that the local file header offset of an entry is relative to
     * @param log
     *            the log node, or null to skip logging
     * @return the entry, or null if the entry is a directory entry, or is unreadable and was skipped.
     * @throws IOException
     *             If an I/O exception occurs, or an extra field is inconsistent with the entry it belongs to.
     */
    private @Nullable FastZipEntry readEntry(final RandomAccessReader cenReader, final long entOff,
            final int filenameLen, final int extraFieldLen, final long locPos, final @Nullable LogNode log)
            throws IOException {
        // Read the entry flag bits before the entry name, since bit 11 gives the name's character encoding
        final var flags = cenReader.readUnsignedShort(entOff + 8);

        // Get and sanitize entry name
        final var filenameStartOff = entOff + 46;
        final var entryName = ZipEntryNameDecoder.readEntryName(cenReader, filenameStartOff, filenameLen,
                /* isUtf8 = */ (flags & UTF8_NAME_FLAG_BIT) != 0);
        // A '!' in an entry name is an ordinary character, since nested jarfiles are reached by their entries, not
        // by a separator in an entry name, so the filesystem is not consulted
        final var entryNameSanitized = PathSyntax.sanitizeRelativePath(entryName, /* removeFinalSlash = */ false);
        if (entryNameSanitized.isEmpty() || entryName.endsWith("/")) {
            // Skip directory entries
            return null;
        }

        if ((flags & 1) != 0) {
            if (log != null) {
                log.log("Skipping encrypted zip entry: " + entryNameSanitized);
            }
            return null;
        }

        // Check compression method
        final var compressionMethod = cenReader.readUnsignedShort(entOff + 10);
        if (compressionMethod != /* stored */ 0 && compressionMethod != /* deflated */ 8) {
            if (log != null) {
                log.log("Skipping zip entry with invalid compression method " + compressionMethod + ": "
                        + entryNameSanitized);
            }
            return null;
        }
        final var isDeflated = compressionMethod == /* deflated */ 8;

        // Get the high 16 bits of the 4-byte external file attributes field, which starts at offset 38. Those are
        // the Unix mode of the entry, if the zipfile was written on a Unix-like system; the low 16 bits hold the
        // MS-DOS attributes, which are of no use here
        final var fileAttributes = cenReader.readUnsignedShort(entOff + 40);

        // Read the compressed and uncompressed size, and the offset of the local file header, any of which the
        // extra fields can override
        final var entryFields = new EntryFields(entryNameSanitized, cenReader.readUnsignedInt(entOff + 20),
                cenReader.readUnsignedInt(entOff + 24), cenReader.readUnsignedInt(entOff + 42), filenameStartOff,
                filenameLen);
        if (extraFieldLen > 0) {
            readExtraFields(cenReader, filenameStartOff + filenameLen, extraFieldLen, entryFields, log);
        }
        checkNoOverflowMarkerWasLeftBehind(entryFields);
        if (entryFields.renamedToDirectoryEntry) {
            // Skip directory entries, as above -- the Unicode path extra field can rename an entry into one
            return null;
        }

        var lastModifiedTimeMSDOS = 0;
        var lastModifiedDateMSDOS = 0;
        if (entryFields.lastModifiedMillis == 0L) {
            // If Unix timestamp was not provided, convert zip entry timestamp from MS-DOS format
            lastModifiedTimeMSDOS = cenReader.readUnsignedShort(entOff + 12);
            lastModifiedDateMSDOS = cenReader.readUnsignedShort(entOff + 14);
        }

        if (entryFields.compressedSize < 0) {
            if (log != null) {
                log.log("Skipping zip entry with invalid compressed size (" + entryFields.compressedSize + "): "
                        + entryFields.entryNameSanitized);
            }
            return null;
        }
        if (entryFields.uncompressedSize < 0) {
            if (log != null) {
                log.log("Skipping zip entry with invalid uncompressed size (" + entryFields.uncompressedSize + "): "
                        + entryFields.entryNameSanitized);
            }
            return null;
        }
        if (entryFields.pos < 0) {
            if (log != null) {
                log.log("Skipping zip entry with invalid pos (" + entryFields.pos + "): "
                        + entryFields.entryNameSanitized);
            }
            return null;
        }

        final var locHeaderPos = locPos + entryFields.pos;
        if (locHeaderPos < 0) {
            if (log != null) {
                log.log("Skipping zip entry with invalid loc header position (" + locHeaderPos + "): "
                        + entryFields.entryNameSanitized);
            }
            return null;
        }
        // Compared by subtraction rather than by adding to locHeaderPos, which a corrupt zipfile can push close
        // enough to Long.MAX_VALUE for the sum to wrap negative and pass a test it should fail
        if (locHeaderPos >= slice.sliceLength - 4) {
            if (log != null) {
                log.log("Unexpected EOF when trying to read LOC header: " + entryFields.entryNameSanitized);
            }
            return null;
        }

        // JarFile looks an entry up by the name stored in the central directory, and ignores the Unicode path
        // extra field, so it finds this entry under its name only if neither sanitizing nor that field changed it.
        // Info-ZIP writes that field even when it holds the stored name, which leaves the name unchanged
        final var nameIsVerbatim = entryFields.entryNameSanitized.equals(entryName);
        return new FastZipEntry(this, locHeaderPos, entryFields.entryNameSanitized, nameIsVerbatim, isDeflated,
                entryFields.compressedSize, entryFields.uncompressedSize, entryFields.lastModifiedMillis,
                lastModifiedTimeMSDOS, lastModifiedDateMSDOS, fileAttributes, multiReleaseVersionsEnabled);
    }

    /**
     * Read the entries of the central directory, and append them to a list.
     *
     * @param cenReader
     *            a reader for the central directory
     * @param cen
     *            the central directory
     * @param entries
     *            the list to append the entries to
     * @param log
     *            the log node, or null to skip logging
     * @return the entry for the manifest file, or null if the zipfile does not have one.
     * @throws IOException
     *             If an I/O exception occurs, or an entry is invalid.
     */
    private @Nullable FastZipEntry readEntries(final RandomAccessReader cenReader, final CentralDirectory cen,
            final List<FastZipEntry> entries, final @Nullable LogNode log) throws IOException {
        FastZipEntry manifestZipEntry = null;
        FastZipEntry caseFoldedManifestZipEntry = null;
        var entSize = 0;
        for (var entOff = 0L; entOff + 46 <= cen.cenSize(); entOff += entSize) {
            final var sig = cenReader.readUnsignedInt(entOff);
            if (sig != 0x02014b50L) {
                throw new IOException(
                        "Invalid central directory signature: 0x" + Long.toHexString(sig) + ": " + getPath());
            }
            final var filenameLen = cenReader.readUnsignedShort(entOff + 28);
            final var extraFieldLen = cenReader.readUnsignedShort(entOff + 30);
            final var commentLen = cenReader.readUnsignedShort(entOff + 32);
            entSize = 46 + filenameLen + extraFieldLen + commentLen;

            // The filename has to be within the central directory, otherwise reading it would read beyond the end
            // of it. The record after this one starts past the end of the central directory too, so this entry and
            // every entry after it is dropped.
            if (entOff + 46 + filenameLen > cen.cenSize()) {
                if (log != null) {
                    log.log("Filename extends past the end of the central directory -- dropping the entry at "
                            + "offset " + entOff + " and any entry after it");
                }
                break;
            }

            // The extra field area has to be within the central directory too, for the same reason. (The comment is
            // not tested here, because it is never read -- an entry whose comment is the only part of it that does
            // not fit is still readable.)
            if (entOff + 46 + filenameLen + extraFieldLen > cen.cenSize()) {
                if (log != null) {
                    log.log("Extra field area extends past the end of the central directory -- dropping the entry "
                            + "at offset " + entOff + " and any entry after it");
                }
                break;
            }

            final var entry = readEntry(cenReader, entOff, filenameLen, extraFieldLen, cen.locPos(), log);
            if (entry != null) {
                entries.add(entry);

                // Record the manifest entry. The manifest is looked for under its canonical name first, since that
                // is the name it is stored under in all but a handful of zipfiles. A zipfile written by a tool that
                // lower-cased its entry names still has a manifest, and java.util.zip.ZipFile finds that one too
                // (it matches both "META-INF/" and "MANIFEST.MF" a character at a time with the case bit masked
                // off), so a differently-cased name is remembered as a fallback rather than the zipfile being
                // reported as having no manifest at all. The last entry with either name wins, the same way the
                // last of two entries with the same name is the one JarFile reads. JarFile matches the name an
                // entry is stored under, so an entry whose name only becomes the manifest's name once it is
                // sanitized, or once a Unicode path extra field replaces it, is not the manifest
                if (entry.nameIsVerbatim && MANIFEST_PATH.equalsIgnoreCase(entry.entryName)) {
                    if (MANIFEST_PATH.equals(entry.entryName)) {
                        manifestZipEntry = entry;
                    } else {
                        caseFoldedManifestZipEntry = entry;
                    }
                }
            }
        }
        return manifestZipEntry != null ? manifestZipEntry : caseFoldedManifestZipEntry;
    }

    /**
     * Of two or more entries with the same name, keep only the one that JarFile, and so a classloader, finds under
     * that name. The entry that is kept stays at its own position in the central directory.
     *
     * <p>
     * JarFile finds the last entry stored under the name. An entry whose name is not the name it is stored under
     * (because sanitizing changed it, as with {@code "./a.txt"} or {@code "/a.txt"}, or because a Unicode path
     * extra field supplied it) is never found under its name by JarFile, so it does not replace an entry stored
     * under that exact name, wherever it is in the central directory. Of entries none of which is stored under the
     * name, the last one is kept, so that a name that exists only after sanitizing still names an entry.
     *
     * @param entries
     *            the entries, in the order of the central directory
     * @param log
     *            the log node, or null to skip logging
     * @return the entries with only the kept entry of each name, or {@code entries} itself if no two have the same
     *         name
     */
    private static List<FastZipEntry> dropDuplicateEntries(final List<FastZipEntry> entries,
            final @Nullable LogNode log) {
        // The names under which some entry is stored verbatim, which is only needed if some entry is not
        Set<String> verbatimNames = null;
        for (final FastZipEntry entry : entries) {
            if (!entry.nameIsVerbatim) {
                verbatimNames = new HashSet<>(entries.size() * 2);
                for (final FastZipEntry otherEntry : entries) {
                    if (otherEntry.nameIsVerbatim) {
                        verbatimNames.add(otherEntry.entryName);
                    }
                }
                break;
            }
        }
        final Set<String> namesSeen = new HashSet<>(entries.size() * 2);
        final List<FastZipEntry> lastOfEachName = new ArrayList<>(entries.size());
        for (var i = entries.size() - 1; i >= 0; i--) {
            final var entry = entries.get(i);
            if (verbatimNames != null && !entry.nameIsVerbatim && verbatimNames.contains(entry.entryName)) {
                if (log != null) {
                    log.log("Ignoring an entry whose name, once sanitized or replaced by its Unicode path extra "
                            + "field, is the name another entry is stored under: " + entry.entryName);
                }
            } else if (namesSeen.add(entry.entryName)) {
                lastOfEachName.add(entry);
            } else if (log != null) {
                log.log("Ignoring an earlier entry with the same name as a later one: " + entry.entryName);
            }
        }
        if (lastOfEachName.size() == entries.size()) {
            return entries;
        }
        Collections.reverse(lastOfEachName);
        return lastOfEachName;
    }

    /**
     * For a multi-release jar, drop any older or non-versioned entries that are masked by the most recent
     * version-specific entry.
     *
     * @param entries
     *            the entries, in the order of the central directory
     * @param log
     *            the log node, or null to skip logging
     * @return the entries that are not masked, in the order of the central directory
     */
    private static List<FastZipEntry> maskMultiReleaseEntries(final List<FastZipEntry> entries,
            final @Nullable LogNode log) {
        if (log != null) {
            // Find all the unique multirelease versions within the jar
            final Set<Integer> versionsFound = new HashSet<>();
            for (final FastZipEntry entry : entries) {
                if (entry.version > 8) {
                    versionsFound.add(entry.version);
                }
            }
            final List<Integer> versionsFoundSorted = new ArrayList<>(versionsFound);
            CollectionUtils.sortIfNotEmpty(versionsFoundSorted);
            log.log("This is a multi-release jar, with versions: " + StringUtils.join(", ", versionsFoundSorted));
        }

        // Sort a copy in decreasing order of version, so that the first entry for each unversioned path is the one
        // with the highest version number, which masks the others
        final List<FastZipEntry> entriesByVersion = new ArrayList<>(entries);
        CollectionUtils.sortIfNotEmpty(entriesByVersion);
        final Map<String, FastZipEntry> unversionedPathToMaskingEntry = new HashMap<>();
        for (final FastZipEntry versionedZipEntry : entriesByVersion) {
            final var maskingEntry = unversionedPathToMaskingEntry
                    .putIfAbsent(versionedZipEntry.entryNameUnversioned, versionedZipEntry);
            if (maskingEntry != null && log != null) {
                log.log(maskingEntry.entryName + " masks " + versionedZipEntry.entryName);
            }
        }

        // Keep only the masking entries, in the order of the central directory
        final List<FastZipEntry> unversionedZipEntriesMasked = new ArrayList<>(
                unversionedPathToMaskingEntry.size());
        for (final FastZipEntry entry : entries) {
            if (unversionedPathToMaskingEntry.get(entry.entryNameUnversioned) == entry) {
                unversionedZipEntriesMasked.add(entry);
            }
        }
        return unversionedZipEntriesMasked;
    }

    /**
     * For a jarfile whose manifest does not have the {@code Multi-Release} key, put back the version prefix of any
     * entry stored beneath {@code META-INF/versions/}.
     *
     * <p>
     * A jarfile is only a multi-release jarfile if its manifest says so, and the version prefixes of one that does
     * not say so name no version of anything: the JVM reads such an entry from the path it is stored under, and
     * loads the base entry for the path the prefix would have named. Whether the manifest has the key is only known
     * once the manifest has been parsed, which cannot happen until the central directory has been read, so the
     * version prefix is resolved for every entry as it is read and put back here.
     *
     * @param entries
     *            the entries, each of which is replaced in place if its version prefix is put back
     * @param log
     *            the log node, or null to skip logging
     */
    private static void unresolveVersionPrefixes(final List<FastZipEntry> entries, final @Nullable LogNode log) {
        for (var i = 0; i < entries.size(); i++) {
            final var entry = entries.get(i);
            if (!entry.entryNameUnversioned.equals(entry.entryName)) {
                if (log != null) {
                    log.log("Found a versioned entry in a jarfile whose manifest does not have the \""
                            + MULTI_RELEASE_KEY + "\" key, so it overrides nothing: " + entry.entryName);
                }
                entries.set(i, entry.withVersionPrefixUnresolved());
            }
        }
    }

    /**
     * What is read from the central directory of a zipfile.
     *
     * @param entries
     *            the entries, as an unmodifiable list, in the order of the central directory
     * @param manifest
     *            the main section of the manifest file, or null if the zipfile has no manifest file
     */
    private record CentralDirectoryContents(List<FastZipEntry> entries, @Nullable Map<String, String> manifest) {
    }

    /**
     * Read the central directory of the zipfile.
     *
     * @param log
     *            the log node, or null to skip logging
     * @return the entries and the manifest of the zipfile
     * @throws IOException
     *             If an I/O exception occurs.
     */
    @SuppressWarnings("resource")
    private CentralDirectoryContents readCentralDirectory(final @Nullable LogNode log) throws IOException {
        if (slice.sliceLength < 22) {
            throw new IOException("Zipfile too short to have a central directory");
        }
        final var reader = slice.randomAccessReader();

        // Locate the central directory
        final var eocdPos = findEndOfCentralDirectoryPos(reader);
        final var cen = readEndOfCentralDirectory(reader, eocdPos);
        final var cenReader = openCentralDirectoryReader(reader, cen);

        // numEnt is -1 if the End Of Central Directory record and its Zip64 counterpart were inconsistent
        final var numEnt = cen.numEnt() == -1L ? countCentralDirectoryEntries(cenReader, cen.cenSize())
                : cen.numEnt();

        // Can't have more than (Integer.MAX_VALUE - 8) entries, since they are stored in an ArrayList
        if (numEnt > Slice.MAX_BUFFER_SIZE) {
            // One alternative in this (impossibly rare) situation would be to return only the first 2B entries
            throw new IOException("Too many zipfile entries: " + numEnt);
        }

        // Make sure there's no DoS attack vector by using a fake number of entries
        if (numEnt > cen.cenSize() / 46) {
            // The smallest directory entry is 46 bytes in size
            throw new IOException("Too many zipfile entries: " + numEnt + " (expected a max of "
                    + cen.cenSize() / 46 + " based on central directory size)");
        }

        // Enumerate entries
        final List<FastZipEntry> rawEntries = new ArrayList<>((int) numEnt);
        final var manifestZipEntry = readEntries(cenReader, cen, rawEntries, log);
        var entries = dropDuplicateEntries(rawEntries, log);

        // Parse manifest file, if present
        final var manifest = manifestZipEntry == null ? null : parseManifest(manifestZipEntry);

        if (multiReleaseVersionsEnabled) {
            // A multi-release jar holds version-specific entries under "META-INF/versions/<version>/", and those
            // entries mask their unversioned counterparts, so this has to be read while the zipfile is being parsed
            // rather than left to the caller: it determines the name of every entry of the zipfile.
            final var isMultiReleaseJar = manifest != null
                    && "true".equalsIgnoreCase(manifest.get(MULTI_RELEASE_KEY));
            if (isMultiReleaseJar) {
                entries = maskMultiReleaseEntries(entries, log);
            } else {
                unresolveVersionPrefixes(entries, log);
            }
        }
        return new CentralDirectoryContents(Collections.unmodifiableList(entries), manifest);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public String toString() {
        return getPath();
    }
}
