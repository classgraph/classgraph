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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.classgraph.base.internal.utils.CollectionUtils;
import io.github.classgraph.base.internal.utils.FileUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.base.internal.utils.StringUtils;
import io.github.classgraph.vfs.internal.ScanResources;
import io.github.classgraph.vfs.internal.slice.ArraySlice;
import io.github.classgraph.vfs.internal.slice.reader.RandomAccessReader;
import org.jspecify.annotations.Nullable;

/**
 * A logical zipfile, which represents a zipfile contained within a ZipFileSlice of a PhysicalZipFile.
 */
public class LogicalZipFile extends ZipFileSlice {
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

    /** The zipfile entries. */
    public List<FastZipEntry> entries;

    /** If true, this is a multi-release jar. */
    private boolean isMultiReleaseJar;

    /** A set of classpath roots found in the classpath for this zipfile. */
    Set<String> classpathRoots = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * The value of the "Class-Path" manifest entry, if present in the manifest, else null.
     */
    public @Nullable String classpathManifestEntryValue;

    /**
     * The value of the "Bundle-ClassPath" manifest entry, if present in the manifest, else null.
     */
    public @Nullable String bundleClassPathManifestEntryValue;

    /**
     * The value of the "Add-Exports" manifest entry, if present in the manifest, else null.
     */
    public @Nullable String addExportsManifestEntryValue;

    /**
     * The value of the "Add-Opens" manifest entry, if present in the manifest, else null.
     */
    public @Nullable String addOpensManifestEntryValue;

    /**
     * The value of the "Automatic-Module-Name" manifest entry, if present in the manifest, else null.
     */
    public @Nullable String automaticModuleNameManifestEntryValue;

    /** If true, this is a JRE jar. */
    public boolean isJREJar;

    /** If true, multi-release versions should not be stripped in resource names. */
    private final boolean enableMultiReleaseVersions;

    // -------------------------------------------------------------------------------------------------------------

    /** {@code "META-INF/"}. */
    static final String META_INF_PATH_PREFIX = "META-INF/";

    /** {@code "META-INF/MANIFEST.MF"}. */
    private static final String MANIFEST_PATH = META_INF_PATH_PREFIX + "MANIFEST.MF";

    /** {@code "META-INF/versions/"}. */
    public static final String MULTI_RELEASE_PATH_PREFIX = META_INF_PATH_PREFIX + "versions/";

    /** The {@code "Implementation-Title"} manifest key. */
    private static final byte[] IMPLEMENTATION_TITLE_KEY = manifestKeyToBytes("Implementation-Title");

    /** The {@code "Specification-Title"} manifest key. */
    private static final byte[] SPECIFICATION_TITLE_KEY = manifestKeyToBytes("Specification-Title");

    /** The {@code "Class-Path"} manifest key. */
    private static final byte[] CLASS_PATH_KEY = manifestKeyToBytes("Class-Path");

    /** The {@code "Bundle-ClassPath"} manifest key. */
    private static final byte[] BUNDLE_CLASSPATH_KEY = manifestKeyToBytes("Bundle-ClassPath");

    /** The {@code "Spring-Boot-Classes"} manifest key. */
    private static final byte[] SPRING_BOOT_CLASSES_KEY = manifestKeyToBytes("Spring-Boot-Classes");

    /** The {@code "Spring-Boot-Lib"} manifest key. */
    private static final byte[] SPRING_BOOT_LIB_KEY = manifestKeyToBytes("Spring-Boot-Lib");

    /** The {@code "Multi-Release"} manifest key. */
    private static final byte[] MULTI_RELEASE_KEY = manifestKeyToBytes("Multi-Release");

    /** The {@code "Add-Exports"} manifest key. */
    private static final byte[] ADD_EXPORTS_KEY = manifestKeyToBytes("Add-Exports");

    /** The {@code "Add-Opens"} manifest key. */
    private static final byte[] ADD_OPENS_KEY = manifestKeyToBytes("Add-Opens");

    /** The {@code "Automatic-Module-Name"} manifest key. */
    private static final byte[] AUTOMATIC_MODULE_NAME_KEY = manifestKeyToBytes("Automatic-Module-Name");

    /** For quickly converting ASCII characters to lower case. */
    private static final byte[] toLowerCase = new byte[256];
    static {
        for (var i = 32; i < 127; i++) {
            toLowerCase[i] = (byte) Character.toLowerCase((char) i);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Construct a logical zipfile from a slice of a physical zipfile.
     *
     * @param zipFileSlice
     *            the zipfile slice
     * @param scanResources
     *            the resources owned by the scan
     * @param log
     *            the log node, or null to skip logging
     * @param enableMultiReleaseVersions
     *            if true, multi-release versions should not be stripped from resource names
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    LogicalZipFile(final ZipFileSlice zipFileSlice, final ScanResources scanResources, final @Nullable LogNode log,
            final boolean enableMultiReleaseVersions) throws IOException, InterruptedException {
        super(zipFileSlice);
        this.enableMultiReleaseVersions = enableMultiReleaseVersions;
        readCentralDirectory(scanResources, log);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Extract a value from the manifest, and return the value as a string, along with the index after the
     * terminating newline. Manifest files support three different line terminator types, and entries can be split
     * across lines with a line terminator followed by a space.
     *
     * @param manifest
     *            the manifest bytes
     * @param startIdx
     *            the start index of the manifest value
     * @return the manifest value
     */
    private static Entry<String, Integer> getManifestValue(final byte[] manifest, final int startIdx) {
        // See if manifest entry is split across multiple lines
        var curr = startIdx;
        final var len = manifest.length;
        while (curr < len && manifest[curr] == (byte) ' ') {
            // Skip initial spaces
            curr++;
        }
        final var firstNonSpaceIdx = curr;
        var isMultiLine = false;
        for (; curr < len && !isMultiLine; curr++) {
            final var b = manifest[curr];
            if (b == (byte) '\r' && curr < len - 1 && manifest[curr + 1] == (byte) '\n') {
                if (curr < len - 2 && manifest[curr + 2] == (byte) ' ') {
                    isMultiLine = true;
                }
                break;
            } else if (b == (byte) '\r' || b == (byte) '\n') {
                if (curr < len - 1 && manifest[curr + 1] == (byte) ' ') {
                    isMultiLine = true;
                }
                break;
            }
        }
        String val;
        if (!isMultiLine) {
            // Fast path for single-line value
            val = new String(manifest, firstNonSpaceIdx, curr - firstNonSpaceIdx, StandardCharsets.UTF_8);
        } else {
            // Skip (newline + space) sequences in multi-line values
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            curr = firstNonSpaceIdx;
            for (; curr < len; curr++) {
                final var b = manifest[curr];
                boolean isLineEnd;
                if (b == (byte) '\r' && curr < len - 1 && manifest[curr + 1] == (byte) '\n') {
                    // CRLF
                    curr += 2;
                    isLineEnd = true;
                } else if (b == '\r' || b == '\n') {
                    // CR or LF
                    curr += 1;
                    isLineEnd = true;
                } else {
                    buf.write(b);
                    isLineEnd = false;
                }
                if (isLineEnd && curr < len && manifest[curr] != (byte) ' ') {
                    // Value ends if line break is not followed by a space
                    break;
                }
                // If line break was followed by a space, then the curr++ in the for loop header will skip it
            }
            val = buf.toString(StandardCharsets.UTF_8);
        }
        return new SimpleEntry<>(val.endsWith(" ") ? val.trim() : val, curr);
    }

    /**
     * Manifest key to bytes.
     *
     * @param key
     *            the manifest key
     * @return the manifest key bytes, lowercased.
     */
    private static byte[] manifestKeyToBytes(final String key) {
        final var bytes = new byte[key.length()];
        for (var i = 0; i < key.length(); i++) {
            bytes[i] = (byte) Character.toLowerCase(key.charAt(i));
        }
        return bytes;
    }

    /**
     * Key matches at position.
     *
     * @param manifest
     *            the manifest
     * @param key
     *            the key
     * @param pos
     *            the position to try matching
     * @return true if the key matches at this position
     */
    private static boolean keyMatchesAtPosition(final byte[] manifest, final byte[] key, final int pos) {
        if (pos + key.length + 1 > manifest.length || manifest[pos + key.length] != ':') {
            return false;
        }
        for (var i = 0; i < key.length; i++) {
            // Manifest keys are case insensitive. The manifest byte has to be masked to an unsigned value, since
            // a byte >= 0x80 is negative, and would otherwise index outside the lookup table.
            if (toLowerCase[manifest[i + pos] & 0xff] != key[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parse the manifest entry of a zipfile.
     *
     * @param manifestZipEntry
     *            the manifest zip entry
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    private void parseManifest(final FastZipEntry manifestZipEntry, final @Nullable LogNode log)
            throws IOException, InterruptedException {
        // Load contents of manifest entry as a byte array
        final var manifest = manifestZipEntry.getSlice().load();

        // Find field keys (separated by newlines)
        for (var i = 0; i < manifest.length;) {
            // There cannot be any space after a newline before the manifest key, so key starts immediately.
            // Blank lines have no key to read.
            final var isBlankLine = manifest[i] == (byte) '\n' || manifest[i] == (byte) '\r';
            final var endIdx = isBlankLine ? -1 : parseManifestField(manifest, i, log);
            if (endIdx >= 0) {
                i = endIdx;
                continue;
            }

            // Field key didn't match -- skip to next key (after next newline that is not followed by a space)
            for (; i < manifest.length - 2; i++) {
                if (manifest[i] == (byte) '\r' && manifest[i + 1] == (byte) '\n' && manifest[i + 2] != (byte) ' ') {
                    i += 2;
                    break;
                } else if ((manifest[i] == (byte) '\r' || manifest[i] == (byte) '\n')
                        && manifest[i + 1] != (byte) ' ') {
                    i++;
                    break;
                }
            }
            if (i >= manifest.length - 2) {
                break;
            }
        }
    }

    /**
     * Read one field of the manifest, if its key is one of the keys that ClassGraph looks for, and record the
     * field's value.
     *
     * @param manifest
     *            the manifest contents
     * @param keyStartIdx
     *            the index of the start of the field key
     * @param log
     *            the log node, or null to skip logging
     * @return the index of the character after the field's value, or -1 if the field key was not recognized
     * @throws IOException
     *             if the manifest names a nonstandard Spring Boot layout.
     */
    private int parseManifestField(final byte[] manifest, final int keyStartIdx, final @Nullable LogNode log)
            throws IOException {
        if (keyMatchesAtPosition(manifest, IMPLEMENTATION_TITLE_KEY, keyStartIdx)) {
            final var manifestValueAndEndIdx = getManifestValue(manifest,
                    keyStartIdx + IMPLEMENTATION_TITLE_KEY.length + 1);
            if ("Java Runtime Environment".equalsIgnoreCase(manifestValueAndEndIdx.getKey())) {
                isJREJar = true;
            }
            return manifestValueAndEndIdx.getValue();

        } else if (keyMatchesAtPosition(manifest, SPECIFICATION_TITLE_KEY, keyStartIdx)) {
            final var manifestValueAndEndIdx = getManifestValue(manifest,
                    keyStartIdx + SPECIFICATION_TITLE_KEY.length + 1);
            if ("Java Platform API Specification".equalsIgnoreCase(manifestValueAndEndIdx.getKey())) {
                isJREJar = true;
            }
            return manifestValueAndEndIdx.getValue();

        } else if (keyMatchesAtPosition(manifest, CLASS_PATH_KEY, keyStartIdx)) {
            final var manifestValueAndEndIdx = getManifestValue(manifest, keyStartIdx + CLASS_PATH_KEY.length + 1);
            // Add Class-Path manifest entry values to classpath
            classpathManifestEntryValue = manifestValueAndEndIdx.getKey();
            if (log != null) {
                log.log("Found Class-Path entry in manifest file: " + classpathManifestEntryValue);
            }
            return manifestValueAndEndIdx.getValue();

        } else if (keyMatchesAtPosition(manifest, BUNDLE_CLASSPATH_KEY, keyStartIdx)) {
            final var manifestValueAndEndIdx = getManifestValue(manifest,
                    keyStartIdx + BUNDLE_CLASSPATH_KEY.length + 1);
            // Add Bundle-ClassPath manifest entry values to classpath
            bundleClassPathManifestEntryValue = manifestValueAndEndIdx.getKey();
            if (log != null) {
                log.log("Found Bundle-ClassPath entry in manifest file: " + bundleClassPathManifestEntryValue);
            }
            return manifestValueAndEndIdx.getValue();

        } else if (keyMatchesAtPosition(manifest, SPRING_BOOT_CLASSES_KEY, keyStartIdx)) {
            final var manifestValueAndEndIdx = getManifestValue(manifest,
                    keyStartIdx + SPRING_BOOT_CLASSES_KEY.length + 1);
            final var springBootClassesFieldVal = manifestValueAndEndIdx.getKey();
            if (!"BOOT-INF/classes".equals(springBootClassesFieldVal)
                    && !"BOOT-INF/classes/".equals(springBootClassesFieldVal)
                    && !"WEB-INF/classes".equals(springBootClassesFieldVal)
                    && !"WEB-INF/classes/".equals(springBootClassesFieldVal)) {
                throw new IOException("Spring boot classes are at \"" + springBootClassesFieldVal
                        + "\" rather than the standard location \"BOOT-INF/classes/\" or \"WEB-INF/classes/\" "
                        + "-- please report this at https://github.com/classgraph/classgraph/issues");
            }
            return manifestValueAndEndIdx.getValue();

        } else if (keyMatchesAtPosition(manifest, SPRING_BOOT_LIB_KEY, keyStartIdx)) {
            final var manifestValueAndEndIdx = getManifestValue(manifest,
                    keyStartIdx + SPRING_BOOT_LIB_KEY.length + 1);
            final var springBootLibFieldVal = manifestValueAndEndIdx.getKey();
            if (!"BOOT-INF/lib".equals(springBootLibFieldVal) && !"BOOT-INF/lib/".equals(springBootLibFieldVal)
                    && !"WEB-INF/lib".equals(springBootLibFieldVal)
                    && !"WEB-INF/lib/".equals(springBootLibFieldVal)) {
                throw new IOException("Spring boot lib jars are at \"" + springBootLibFieldVal
                        + "\" rather than the standard location \"BOOT-INF/lib/\" or \"WEB-INF/lib/\" "
                        + "-- please report this at https://github.com/classgraph/classgraph/issues");
            }
            return manifestValueAndEndIdx.getValue();

        } else if (keyMatchesAtPosition(manifest, MULTI_RELEASE_KEY, keyStartIdx)) {
            final var manifestValueAndEndIdx = getManifestValue(manifest,
                    keyStartIdx + MULTI_RELEASE_KEY.length + 1);
            if ("true".equalsIgnoreCase(manifestValueAndEndIdx.getKey())) {
                isMultiReleaseJar = true;
            }
            return manifestValueAndEndIdx.getValue();

        } else if (keyMatchesAtPosition(manifest, ADD_EXPORTS_KEY, keyStartIdx)) {
            final var manifestValueAndEndIdx = getManifestValue(manifest, keyStartIdx + ADD_EXPORTS_KEY.length + 1);
            addExportsManifestEntryValue = manifestValueAndEndIdx.getKey();
            if (log != null) {
                log.log("Found Add-Exports entry in manifest file: " + addExportsManifestEntryValue);
            }
            return manifestValueAndEndIdx.getValue();

        } else if (keyMatchesAtPosition(manifest, ADD_OPENS_KEY, keyStartIdx)) {
            final var manifestValueAndEndIdx = getManifestValue(manifest, keyStartIdx + ADD_OPENS_KEY.length + 1);
            addOpensManifestEntryValue = manifestValueAndEndIdx.getKey();
            if (log != null) {
                log.log("Found Add-Opens entry in manifest file: " + addOpensManifestEntryValue);
            }
            return manifestValueAndEndIdx.getValue();

        } else if (keyMatchesAtPosition(manifest, AUTOMATIC_MODULE_NAME_KEY, keyStartIdx)) {
            final var manifestValueAndEndIdx = getManifestValue(manifest,
                    keyStartIdx + AUTOMATIC_MODULE_NAME_KEY.length + 1);
            automaticModuleNameManifestEntryValue = manifestValueAndEndIdx.getKey();
            if (log != null) {
                log.log("Found Automatic-Module-Name entry in manifest file: "
                        + automaticModuleNameManifestEntryValue);
            }
            return manifestValueAndEndIdx.getValue();

        } else {
            // Key name was unrecognized
            return -1;
        }
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
         */
        EntryFields(final String entryNameSanitized, final long compressedSize, final long uncompressedSize,
                final long pos) {
            this.entryNameSanitized = entryNameSanitized;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.pos = pos;
        }
    }

    /**
     * Find the End Of Central Directory (EOCD) record, which is the last record in the zipfile, apart from the
     * zipfile comment.
     *
     * @param reader
     *            a reader for the whole zipfile slice
     * @param scanResources
     *            the resources owned by the scan
     * @return the position of the End Of Central Directory record within the zipfile slice
     * @throws IOException
     *             If an I/O exception occurs, or the record could not be found.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    @SuppressWarnings("resource")
    private long findEndOfCentralDirectoryPos(final RandomAccessReader reader, final ScanResources scanResources)
            throws IOException, InterruptedException {
        // Scan for End Of Central Directory (EOCD) signature. Final comment can be up to 64kB in length, so need to
        // scan back that far to determine if this is a valid zipfile. However for speed, initially just try reading
        // back a maximum of 32 characters.
        for (long i = slice.sliceLength - 22, iMin = slice.sliceLength - 22 - 32; i >= iMin && i >= 0L; --i) {
            if (reader.readUnsignedInt(i) == 0x06054b50L) {
                return i;
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
                    /* inflatedLengthHint = */ 0L, scanResources)) {
                final var eocdReader = arraySlice.randomAccessReader();
                for (var i = eocdBytes.length - 22L; i >= 0L; --i) {
                    if (eocdReader.readUnsignedInt(i) == 0x06054b50L) {
                        return i + readStartOff;
                    }
                }
            }
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
     * @param scanResources
     *            the resources owned by the scan
     * @return the reader
     * @throws IOException
     *             If an I/O exception occurs.
     */
    @SuppressWarnings("resource")
    private RandomAccessReader openCentralDirectoryReader(final RandomAccessReader reader,
            final CentralDirectory cen, final ScanResources scanResources) throws IOException {
        // Read entries into a byte array, if central directory is smaller than 2GB. If central directory is larger
        // than 2GB, need to read each entry field from the file directly using ZipFileSliceReader.
        if (cen.cenSize() > FileUtils.MAX_BUFFER_SIZE) {
            // Create a slice that covers the central directory (this allows a central directory larger than 2GB to
            // be accessed using the slower FileSlice API, which reads the file directly, but also the slice can be
            // accessed without adding cenPos to each read offset, so that this slice or the slice in the "else"
            // clause below are accessed with the same index, which is the offset from the start of the central
            // directory).
            return slice.slice(cen.cenPos(), cen.cenSize(), /* isDeflatedZipEntry = */ false,
                    /* inflatedSizeHint = */ 0L).randomAccessReader();
        }
        // Read the central directory into RAM for speed, then wrap it in an ArraySlice (random access is faster
        // for ArraySlice than for FileSlice)
        final var entryBytes = new byte[(int) cen.cenSize()];
        if (reader.read(cen.cenPos(), entryBytes, 0, (int) cen.cenSize()) < cen.cenSize()) {
            // Should not happen
            throw new IOException("Zipfile is truncated");
        }
        return new ArraySlice(entryBytes, /* isDeflatedZipEntry = */ false, /* inflatedSizeHint = */ 0L,
                scanResources).randomAccessReader();
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
                throw new IOException("Invalid central directory signature: 0x" + Integer.toString((int) sig, 16)
                        + ": " + getPath());
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
                readUnicodePathExtraField(cenReader, tagOff, size, entryFields);
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
     * @throws IOException
     *             If an I/O exception occurs, or the extra field is of an unknown version, or its entry name is
     *             malformed.
     */
    private static void readUnicodePathExtraField(final RandomAccessReader cenReader, final long tagOff,
            final int size, final EntryFields entryFields) throws IOException {
        final var version = cenReader.readUnsignedByte(tagOff + 4 + 0);
        if (version != 1) {
            throw new IOException("Unknown Unicode entry name format " + version + " in extra field: "
                    + entryFields.entryNameSanitized);
        } else if (size > 5) {
            // Replace non-Unicode entry name with Unicode version. The data area of this extra field is
            // version(1) + nameCRC32(4) + name, so the name starts 5 bytes into the data area (i.e. 9
            // bytes after the tag), and is (size - 5) bytes long.
            // This extra field's name is always UTF-8, whatever the entry's language encoding flag says
            final var unicodeEntryName = ZipEntryNameCodec.readEntryName(cenReader, tagOff + 9, size - 5,
                    /* isUtf8 = */ true);
            // The replacement name has to be sanitized, and tested for naming a directory, exactly as the
            // name it replaces was -- otherwise an entry can carry a path such as "pkg/../../x" or
            // "/abs/x" simply by declaring it here
            entryFields.entryNameSanitized = FileUtils.sanitizeEntryPath(unicodeEntryName,
                    /* removeInitialSlash = */ true, /* removeFinalSlash = */ false);
            entryFields.renamedToDirectoryEntry = entryFields.entryNameSanitized.isEmpty()
                    || unicodeEntryName.endsWith("/");
        }
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
        final var entryName = ZipEntryNameCodec.readEntryName(cenReader, filenameStartOff, filenameLen,
                /* isUtf8 = */ (flags & UTF8_NAME_FLAG_BIT) != 0);
        final var entryNameSanitized = FileUtils.sanitizeEntryPath(entryName, /* removeInitialSlash = */ true,
                /* removeFinalSlash = */ false);
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

        // Get external file attributes
        final var fileAttributes = cenReader.readUnsignedShort(entOff + 40);

        // Read the compressed and uncompressed size, and the offset of the local file header, any of which the
        // extra fields can override
        final var entryFields = new EntryFields(entryNameSanitized, cenReader.readUnsignedInt(entOff + 20),
                cenReader.readUnsignedInt(entOff + 24), cenReader.readUnsignedInt(entOff + 42));
        if (extraFieldLen > 0) {
            readExtraFields(cenReader, filenameStartOff + filenameLen, extraFieldLen, entryFields, log);
        }
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

        return new FastZipEntry(this, locHeaderPos, entryFields.entryNameSanitized, isDeflated,
                entryFields.compressedSize, entryFields.uncompressedSize, entryFields.lastModifiedMillis,
                lastModifiedTimeMSDOS, lastModifiedDateMSDOS, fileAttributes, enableMultiReleaseVersions);
    }

    /**
     * Read the entries of the central directory into {@link #entries}, which must already have been allocated.
     *
     * @param cenReader
     *            a reader for the central directory
     * @param cen
     *            the central directory
     * @param log
     *            the log node, or null to skip logging
     * @return the entry for the manifest file, or null if the zipfile does not have one.
     * @throws IOException
     *             If an I/O exception occurs, or an entry is invalid.
     */
    private @Nullable FastZipEntry readEntries(final RandomAccessReader cenReader, final CentralDirectory cen,
            final @Nullable LogNode log) throws IOException {
        FastZipEntry manifestZipEntry = null;
        var entSize = 0;
        for (var entOff = 0L; entOff + 46 <= cen.cenSize(); entOff += entSize) {
            final var sig = cenReader.readUnsignedInt(entOff);
            if (sig != 0x02014b50L) {
                throw new IOException("Invalid central directory signature: 0x" + Integer.toString((int) sig, 16)
                        + ": " + getPath());
            }
            final var filenameLen = cenReader.readUnsignedShort(entOff + 28);
            final var extraFieldLen = cenReader.readUnsignedShort(entOff + 30);
            final var commentLen = cenReader.readUnsignedShort(entOff + 32);
            entSize = 46 + filenameLen + extraFieldLen + commentLen;

            if (entOff + 46 + filenameLen > cen.cenSize()) {
                if (log != null) {
                    log.log("Filename extends past end of entry -- skipping entry at offset " + entOff);
                }
                break;
            }

            // The extra field area has to be within the central directory too, otherwise reading the extra fields
            // would read beyond the end of it. (The comment is not tested here, because it is never read -- an
            // entry whose comment is the only part of it that does not fit is still readable. Either way, the
            // record after this one starts past the end of the central directory, so the loop ends here.)
            if (entOff + 46 + filenameLen + extraFieldLen > cen.cenSize()) {
                if (log != null) {
                    log.log("Extra field area extends past end of entry -- skipping entry at offset " + entOff);
                }
                break;
            }

            final var entry = readEntry(cenReader, entOff, filenameLen, extraFieldLen, cen.locPos(), log);
            if (entry != null) {
                entries.add(entry);

                // Record manifest entry
                if (MANIFEST_PATH.equals(entry.entryName)) {
                    manifestZipEntry = entry;
                }
            }
        }
        return manifestZipEntry;
    }

    /**
     * For a multi-release jar, drop any older or non-versioned entries that are masked by the most recent
     * version-specific entry.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    private void maskMultiReleaseEntries(final @Nullable LogNode log) {
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

        // Sort in decreasing order of version in preparation for version masking
        CollectionUtils.sortIfNotEmpty(entries);

        // Mask files that appear in multiple version sections, so that there is only one entry for each
        // unversioned path, i.e. the versioned path with the highest version number
        final List<FastZipEntry> unversionedZipEntriesMasked = new ArrayList<>(entries.size());
        final Map<String, String> unversionedPathToVersionedPath = new HashMap<>();
        for (final FastZipEntry versionedZipEntry : entries) {
            final var maskingEntryName = unversionedPathToVersionedPath
                    .putIfAbsent(versionedZipEntry.entryNameUnversioned, versionedZipEntry.entryName);
            if (maskingEntryName == null) {
                // This is the first FastZipEntry for this entry's unversioned path
                unversionedZipEntriesMasked.add(versionedZipEntry);
            } else if (log != null) {
                log.log(maskingEntryName + " masks " + versionedZipEntry.entryName);
            }
        }

        // Override entries with version-masked entries
        entries = unversionedZipEntriesMasked;
    }

    /**
     * Read the central directory of the zipfile.
     *
     * @param scanResources
     *            the resources owned by the scan
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    @SuppressWarnings("resource")
    private void readCentralDirectory(final ScanResources scanResources, final @Nullable LogNode log)
            throws IOException, InterruptedException {
        if (slice.sliceLength < 22) {
            throw new IOException("Zipfile too short to have a central directory");
        }
        final var reader = slice.randomAccessReader();

        // Locate the central directory
        final var eocdPos = findEndOfCentralDirectoryPos(reader, scanResources);
        final var cen = readEndOfCentralDirectory(reader, eocdPos);
        final var cenReader = openCentralDirectoryReader(reader, cen, scanResources);

        // numEnt is -1 if the End Of Central Directory record and its Zip64 counterpart were inconsistent
        final var numEnt = cen.numEnt() == -1L ? countCentralDirectoryEntries(cenReader, cen.cenSize())
                : cen.numEnt();

        // Can't have more than (Integer.MAX_VALUE - 8) entries, since they are stored in an ArrayList
        if (numEnt > FileUtils.MAX_BUFFER_SIZE) {
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
        entries = new ArrayList<>((int) numEnt);
        final var manifestZipEntry = readEntries(cenReader, cen, log);

        // Parse manifest file, if present
        if (manifestZipEntry != null) {
            parseManifest(manifestZipEntry, log);
        }

        if (isMultiReleaseJar) {
            maskMultiReleaseEntries(log);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the paths of the jarfiles stored under any of the given directories within this jarfile.
     *
     * <p>
     * The package root is ignored, since a jarfile can have both a package root and library directories alongside
     * it, e.g. a Spring Boot executable jar stores its classes under {@code BOOT-INF/classes/} and the jarfiles it
     * depends on under {@code BOOT-INF/lib/}.
     *
     * @param dirPrefixes
     *            the directories to look under, each ending with {@code '/'}.
     * @return the paths of the jarfiles, in the order they appear in the central directory.
     */
    public List<String> nestedJarPaths(final String[] dirPrefixes) {
        final List<String> nestedJarPaths = new ArrayList<>();
        for (final var zipEntry : entries) {
            for (final String dirPrefix : dirPrefixes) {
                if (zipEntry.entryNameUnversioned.startsWith(dirPrefix)
                        && zipEntry.entryNameUnversioned.endsWith(".jar")) {
                    nestedJarPaths.add(zipEntry.getPath());
                    break;
                }
            }
        }
        return nestedJarPaths;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public String toString() {
        return getPath();
    }
}
