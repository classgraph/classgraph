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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
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

import io.github.classgraph.ClassGraphException;
import nonapi.io.github.classgraph.fileslice.ArraySlice;
import nonapi.io.github.classgraph.fileslice.reader.RandomAccessReader;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.Join;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;

/**
 * A logical zipfile, which represents a zipfile contained within a ZipFileSlice of a PhysicalZipFile.
 */
public class LogicalZipFile extends ZipFileSlice {
    /** The zipfile entries. */
    public List<FastZipEntry> entries;

    /** If true, this is a multi-release jar. */
    private boolean isMultiReleaseJar;

    /** A set of classpath roots found in the classpath for this zipfile. */
    Set<String> classpathRoots = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** The value of the "Class-Path" manifest entry, if present in the manifest, else null. */
    public String classPathManifestEntryValue;

    /** The value of the "Bundle-ClassPath" manifest entry, if present in the manifest, else null. */
    public String bundleClassPathManifestEntryValue;

    /** The value of the "Add-Exports" manifest entry, if present in the manifest, else null. */
    public String addExportsManifestEntryValue;

    /** The value of the "Add-Opens" manifest entry, if present in the manifest, else null. */
    public String addOpensManifestEntryValue;

    /** The value of the "Automatic-Module-Name" manifest entry, if present in the manifest, else null. */
    public String automaticModuleNameManifestEntryValue;

    /** If true, this is a JRE jar. */
    public boolean isJREJar;

    // -------------------------------------------------------------------------------------------------------------

    /** {@code "META_INF/"}. */
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
    private static byte[] toLowerCase = new byte[256];
    static {
        for (int i = 32; i < 127; i++) {
            toLowerCase[i] = (byte) Character.toLowerCase((char) i);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Construct a logical zipfile from a slice of a physical zipfile.
     *
     * @param zipFileSlice
     *            the zipfile slice
     * @param log
     *            the log
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    LogicalZipFile(final ZipFileSlice zipFileSlice, final LogNode log) throws IOException, InterruptedException {
        super(zipFileSlice);
        readCentralDirectory(log);
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
        int curr = startIdx;
        final int len = manifest.length;
        while (curr < len && manifest[curr] == (byte) ' ') {
            // Skip initial spaces
            curr++;
        }
        final int firstNonSpaceIdx = curr;
        boolean isMultiLine = false;
        for (; curr < len && !isMultiLine; curr++) {
            final byte b = manifest[curr];
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
                final byte b = manifest[curr];
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
            try {
                val = buf.toString("UTF-8");
            } catch (final UnsupportedEncodingException e) {
                // Should not happen
                throw ClassGraphException.newClassGraphException("UTF-8 encoding unsupported", e);
            }
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
        final byte[] bytes = new byte[key.length()];
        for (int i = 0; i < key.length(); i++) {
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
        for (int i = 0; i < key.length; i++) {
            // Manifest keys are case insensitive
            if (toLowerCase[manifest[i + pos]] != key[i]) {
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
     *            the log
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    private void parseManifest(final FastZipEntry manifestZipEntry, final LogNode log)
            throws IOException, InterruptedException {
        // Load contents of manifest entry as a byte array
        final byte[] manifest = manifestZipEntry.getSlice().load();

        // Find field keys (separated by newlines)
        for (int i = 0; i < manifest.length;) {
            // There cannot be any space after a newline before the manifest key, so key starts immediately
            boolean skip = false;
            if (manifest[i] == (byte) '\n' || manifest[i] == (byte) '\r') {
                // Skip blank lines
                skip = true;

            } else if (keyMatchesAtPosition(manifest, IMPLEMENTATION_TITLE_KEY, i)) {
                final Entry<String, Integer> manifestValueAndEndIdx = getManifestValue(manifest,
                        i + IMPLEMENTATION_TITLE_KEY.length + 1);
                if (manifestValueAndEndIdx.getKey().equalsIgnoreCase("Java Runtime Environment")) {
                    isJREJar = true;
                }
                i = manifestValueAndEndIdx.getValue();

            } else if (keyMatchesAtPosition(manifest, SPECIFICATION_TITLE_KEY, i)) {
                final Entry<String, Integer> manifestValueAndEndIdx = getManifestValue(manifest,
                        i + SPECIFICATION_TITLE_KEY.length + 1);
                if (manifestValueAndEndIdx.getKey().equalsIgnoreCase("Java Platform API Specification")) {
                    isJREJar = true;
                }
                i = manifestValueAndEndIdx.getValue();

            } else if (keyMatchesAtPosition(manifest, CLASS_PATH_KEY, i)) {
                final Entry<String, Integer> manifestValueAndEndIdx = getManifestValue(manifest,
                        i + CLASS_PATH_KEY.length + 1);
                // Add Class-Path manifest entry values to classpath
                classPathManifestEntryValue = manifestValueAndEndIdx.getKey();
                if (log != null) {
                    log.log("Found Class-Path entry in manifest file: " + classPathManifestEntryValue);
                }
                i = manifestValueAndEndIdx.getValue();

            } else if (keyMatchesAtPosition(manifest, BUNDLE_CLASSPATH_KEY, i)) {
                final Entry<String, Integer> manifestValueAndEndIdx = getManifestValue(manifest,
                        i + BUNDLE_CLASSPATH_KEY.length + 1);
                // Add Bundle-ClassPath manifest entry values to classpath
                bundleClassPathManifestEntryValue = manifestValueAndEndIdx.getKey();
                if (log != null) {
                    log.log("Found Bundle-ClassPath entry in manifest file: " + bundleClassPathManifestEntryValue);
                }
                i = manifestValueAndEndIdx.getValue();

            } else if (keyMatchesAtPosition(manifest, SPRING_BOOT_CLASSES_KEY, i)) {
                final Entry<String, Integer> manifestValueAndEndIdx = getManifestValue(manifest,
                        i + SPRING_BOOT_CLASSES_KEY.length + 1);
                final String springBootClassesFieldVal = manifestValueAndEndIdx.getKey();
                if (!springBootClassesFieldVal.equals("BOOT-INF/classes")
                        && !springBootClassesFieldVal.equals("BOOT-INF/classes/")
                        && !springBootClassesFieldVal.equals("WEB-INF/classes")
                        && !springBootClassesFieldVal.equals("WEB-INF/classes/")) {
                    throw new IOException("Spring boot classes are at \"" + springBootClassesFieldVal
                            + "\" rather than the standard location \"BOOT-INF/classes/\" or \"WEB-INF/classes/\" "
                            + "-- please report this at https://github.com/classgraph/classgraph/issues");
                }
                i = manifestValueAndEndIdx.getValue();

            } else if (keyMatchesAtPosition(manifest, SPRING_BOOT_LIB_KEY, i)) {
                final Entry<String, Integer> manifestValueAndEndIdx = getManifestValue(manifest,
                        i + SPRING_BOOT_LIB_KEY.length + 1);
                final String springBootLibFieldVal = manifestValueAndEndIdx.getKey();
                if (!springBootLibFieldVal.equals("BOOT-INF/lib") && !springBootLibFieldVal.equals("BOOT-INF/lib/")
                        && !springBootLibFieldVal.equals("WEB-INF/lib")
                        && !springBootLibFieldVal.equals("WEB-INF/lib/")) {
                    throw new IOException("Spring boot lib jars are at \"" + springBootLibFieldVal
                            + "\" rather than the standard location \"BOOT-INF/lib/\" or \"WEB-INF/lib/\" "
                            + "-- please report this at https://github.com/classgraph/classgraph/issues");
                }
                i = manifestValueAndEndIdx.getValue();

            } else if (keyMatchesAtPosition(manifest, MULTI_RELEASE_KEY, i)) {
                final Entry<String, Integer> manifestValueAndEndIdx = getManifestValue(manifest,
                        i + MULTI_RELEASE_KEY.length + 1);
                if (manifestValueAndEndIdx.getKey().equalsIgnoreCase("true")) {
                    isMultiReleaseJar = true;
                }
                i = manifestValueAndEndIdx.getValue();

            } else if (keyMatchesAtPosition(manifest, ADD_EXPORTS_KEY, i)) {
                final Entry<String, Integer> manifestValueAndEndIdx = getManifestValue(manifest,
                        i + ADD_EXPORTS_KEY.length + 1);
                addExportsManifestEntryValue = manifestValueAndEndIdx.getKey();
                if (log != null) {
                    log.log("Found Add-Exports entry in manifest file: " + addExportsManifestEntryValue);
                }
                i = manifestValueAndEndIdx.getValue();

            } else if (keyMatchesAtPosition(manifest, ADD_OPENS_KEY, i)) {
                final Entry<String, Integer> manifestValueAndEndIdx = getManifestValue(manifest,
                        i + ADD_OPENS_KEY.length + 1);
                addExportsManifestEntryValue = manifestValueAndEndIdx.getKey();
                if (log != null) {
                    log.log("Found Add-Opens entry in manifest file: " + addOpensManifestEntryValue);
                }
                i = manifestValueAndEndIdx.getValue();

            } else if (keyMatchesAtPosition(manifest, AUTOMATIC_MODULE_NAME_KEY, i)) {
                final Entry<String, Integer> manifestValueAndEndIdx = getManifestValue(manifest,
                        i + AUTOMATIC_MODULE_NAME_KEY.length + 1);
                automaticModuleNameManifestEntryValue = manifestValueAndEndIdx.getKey();
                if (log != null) {
                    log.log("Found Automatic-Module-Name entry in manifest file: "
                            + automaticModuleNameManifestEntryValue);
                }
                i = manifestValueAndEndIdx.getValue();

            } else {

                // Key name was unrecognized -- skip to next key
                skip = true;
            }

            if (skip) {
                // Field key didn't match -- skip to next key (after next newline that is not followed by a space)
                for (; i < manifest.length - 2; i++) {
                    if (manifest[i] == (byte) '\r' && manifest[i + 1] == (byte) '\n'
                            && manifest[i + 2] != (byte) ' ') {
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
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read the central directory of the zipfile.
     *
     * @param log
     *            the log
     * @throws IOException
     *             If an I/O exception occurs.
     * @throws InterruptedException
     *             if the thread was interrupted.
     */
    private void readCentralDirectory(final LogNode log) throws IOException, InterruptedException {
        final RandomAccessReader reader = slice.randomAccessReader();

        // Scan for End Of Central Directory (EOCD) signature. Final comment can be up to 64kB in length,
        // so need to scan back that far to determine if this is a valid zipfile. However for speed,
        // initially just try reading back a maximum of 32 characters.
        long eocdPos = -1;
        for (long i = slice.sliceLength - 22, iMin = slice.sliceLength - 22 - 32; i >= iMin; --i) {
            if (reader.readInt(i) == 0x06054b50) {
                eocdPos = i;
                break;
            }
        }
        if (eocdPos < 0) {
            // If EOCD signature was not found, read the last 64kB of file to RAM in a single chunk
            // so that we can scan back through it at higher speed to locate the EOCD signature
            final int bytesToRead = (int) Math.min(slice.sliceLength, 22 + (1 << 16));
            final byte[] eocdBytes = new byte[bytesToRead];
            final long readStartOff = slice.sliceLength - bytesToRead;
            if (reader.read(readStartOff, eocdBytes, 0, bytesToRead) < bytesToRead) {
                // Should not happen
                throw new IOException("Zipfile is truncated");
            }
            final RandomAccessReader eocdReader = new ArraySlice(eocdBytes, /* isDeflatedZipEntry = */ false,
                    /* inflatedLengthHint = */ 0L, physicalZipFile.nestedJarHandler).randomAccessReader();
            for (long i = eocdBytes.length - 22; i >= 0; --i) {
                if (eocdReader.readInt(i) == 0x06054b50) {
                    eocdPos = i + readStartOff;
                    break;
                }
            }
        }
        if (eocdPos < 0) {
            throw new IOException("Jarfile central directory signature not found: " + getPath());
        }
        long numEnt = reader.readUnsignedShort(eocdPos + 8);
        if (reader.readUnsignedShort(eocdPos + 4) > 0 || reader.readUnsignedShort(eocdPos + 6) > 0
                || numEnt != reader.readUnsignedShort(eocdPos + 10)) {
            throw new IOException("Multi-disk jarfiles not supported: " + getPath());
        }
        long cenSize = reader.readUnsignedInt(eocdPos + 12);
        if (cenSize > eocdPos) {
            throw new IOException(
                    "Central directory size out of range: " + cenSize + " vs. " + eocdPos + ": " + getPath());
        }
        long cenOff = reader.readUnsignedInt(eocdPos + 16);
        long cenPos = eocdPos - cenSize;

        // Check for Zip64 End Of Central Directory Locator record
        final long zip64cdLocIdx = eocdPos - 20;
        if (zip64cdLocIdx >= 0 && reader.readInt(zip64cdLocIdx) == 0x07064b50) {
            if (reader.readInt(zip64cdLocIdx + 4) > 0 || reader.readInt(zip64cdLocIdx + 16) > 1) {
                throw new IOException("Multi-disk jarfiles not supported: " + getPath());
            }
            final long eocdPos64 = reader.readLong(zip64cdLocIdx + 8);
            if (reader.readInt(eocdPos64) != 0x06064b50) {
                throw new IOException("Zip64 central directory at location " + eocdPos64
                        + " does not have Zip64 central directory header: " + getPath());
            }
            final long numEnt64 = reader.readLong(eocdPos64 + 24);
            if (reader.readInt(eocdPos64 + 16) > 0 || reader.readInt(eocdPos64 + 20) > 0
                    || numEnt64 != reader.readLong(eocdPos64 + 32)) {
                throw new IOException("Multi-disk jarfiles not supported: " + getPath());
            }
            if (numEnt == 0xffff) {
                numEnt = numEnt64;
            } else if (numEnt != numEnt64) {
                // Entry size mismatch -- trigger manual counting of entries
                numEnt = -1L;
            }

            final long cenSize64 = reader.readLong(eocdPos64 + 40);
            if (cenSize == 0xffffffffL) {
                cenSize = cenSize64;
            } else if (cenSize != cenSize64) {
                throw new IOException(
                        "Mismatch in central directory size: " + cenSize + " vs. " + cenSize64 + ": " + getPath());
            }

            // Recalculate the central directory position
            cenPos = eocdPos64 - cenSize;

            final long cenOff64 = reader.readLong(eocdPos64 + 48);
            if (cenOff == 0xffffffffL) {
                cenOff = cenOff64;
            } else if (cenOff != cenOff64) {
                throw new IOException(
                        "Mismatch in central directory offset: " + cenOff + " vs. " + cenOff64 + ": " + getPath());
            }
        }

        // Get offset of first local file header
        final long locPos = cenPos - cenOff;
        if (locPos < 0) {
            throw new IOException("Local file header offset out of range: " + locPos + ": " + getPath());
        }

        // Read entries into a byte array, if central directory is smaller than 2GB. If central directory
        // is larger than 2GB, need to read each entry field from the file directly using ZipFileSliceReader.
        RandomAccessReader cenReader;
        if (cenSize > FileUtils.MAX_BUFFER_SIZE) {
            // Create a slice that covers the central directory (this allows a central directory larger than
            // 2GB to be accessed using the slower FileSlice API, which reads the file directly, but also
            // the slice can be accessed without adding cenPos to each read offset, so that this slice or
            // the slice in the "else" clause below are accessed with the same index, which is the offset
            // from the start of the central directory).
            cenReader = slice.slice(cenPos, cenSize, /* isDeflatedZipEntry = */ false, /* inflatedSizeHint = */ 0L)
                    .randomAccessReader();
        } else {
            // Read the central directory into RAM for speed, then wrap it in an ArraySlice
            // (random access is faster for ArraySlice than for FileSlice)
            final byte[] entryBytes = new byte[(int) cenSize];
            if (reader.read(cenPos, entryBytes, 0, (int) cenSize) < cenSize) {
                // Should not happen
                throw new IOException("Zipfile is truncated");
            }
            cenReader = new ArraySlice(entryBytes, /* isDeflatedZipEntry = */ false, /* inflatedSizeHint = */ 0L,
                    physicalZipFile.nestedJarHandler).randomAccessReader();
        }

        if (numEnt == -1L) {
            // numEnt and numEnt64 were inconsistent -- manually count entries
            numEnt = 0;
            for (long entOff = 0; entOff + 46 <= cenSize;) {
                final int sig = cenReader.readInt(entOff);
                if (sig != 0x02014b50) {
                    throw new IOException("Invalid central directory signature: 0x" + Integer.toString(sig, 16)
                            + ": " + getPath());
                }
                final int filenameLen = cenReader.readUnsignedShort(entOff + 28);
                final int extraFieldLen = cenReader.readUnsignedShort(entOff + 30);
                final int commentLen = cenReader.readUnsignedShort(entOff + 32);
                entOff += 46 + filenameLen + extraFieldLen + commentLen;
                numEnt++;
            }
        }

        //  Can't have more than (Integer.MAX_VALUE - 8) entries, since they are stored in an ArrayList
        if (numEnt > FileUtils.MAX_BUFFER_SIZE) {
            // One alternative in this (impossibly rare) situation would be to return only the first 2B entries
            throw new IOException("Too many zipfile entries: " + numEnt);
        }

        // Make sure there's no DoS attack vector by using a fake number of entries
        if (numEnt > cenSize / 46) {
            // The smallest directory entry is 46 bytes in size
            throw new IOException("Too many zipfile entries: " + numEnt + " (expected a max of " + cenSize / 46
                    + " based on central directory size)");
        }

        // Enumerate entries
        entries = new ArrayList<>((int) numEnt);
        FastZipEntry manifestZipEntry = null;
        try {
            int entSize = 0;
            for (long entOff = 0; entOff + 46 <= cenSize; entOff += entSize) {
                final int sig = cenReader.readInt(entOff);
                if (sig != 0x02014b50) {
                    throw new IOException("Invalid central directory signature: 0x" + Integer.toString(sig, 16)
                            + ": " + getPath());
                }
                final int filenameLen = cenReader.readUnsignedShort(entOff + 28);
                final int extraFieldLen = cenReader.readUnsignedShort(entOff + 30);
                final int commentLen = cenReader.readUnsignedShort(entOff + 32);
                entSize = 46 + filenameLen + extraFieldLen + commentLen;

                // Get and sanitize entry name
                final long filenameStartOff = entOff + 46;
                final long filenameEndOff = filenameStartOff + filenameLen;
                if (filenameEndOff > cenSize) {
                    if (log != null) {
                        log.log("Filename extends past end of entry -- skipping entry at offset " + entOff);
                    }
                    break;
                }
                final String entryName = cenReader.readString(filenameStartOff, filenameLen);
                String entryNameSanitized = FileUtils.sanitizeEntryPath(entryName, /* removeInitialSlash = */ true);
                if (entryNameSanitized.isEmpty() || entryName.endsWith("/")) {
                    // Skip directory entries
                    continue;
                }

                // Check entry flag bits
                final int flags = cenReader.readUnsignedShort(entOff + 8);
                if ((flags & 1) != 0) {
                    if (log != null) {
                        log.log("Skipping encrypted zip entry: " + entryNameSanitized);
                    }
                    continue;
                }

                // Check compression method
                final int compressionMethod = cenReader.readUnsignedShort(entOff + 10);
                if (compressionMethod != /* stored */ 0 && compressionMethod != /* deflated */ 8) {
                    if (log != null) {
                        log.log("Skipping zip entry with invalid compression method " + compressionMethod + ": "
                                + entryNameSanitized);
                    }
                    continue;
                }
                final boolean isDeflated = compressionMethod == /* deflated */ 8;

                // Get compressed and uncompressed size
                long compressedSize = (cenReader.readUnsignedInt(entOff + 20));
                long uncompressedSize = (cenReader.readUnsignedInt(entOff + 24));

                // Get external file attributes
                final int fileAttributes = cenReader.readUnsignedShort(entOff + 40);

                long pos = cenReader.readInt(entOff + 42);

                // Check for Zip64 header in extra fields
                // See:
                // https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
                // https://github.com/LuaDist/zip/blob/master/proginfo/extrafld.txt
                long lastModifiedMillis = 0L;
                if (extraFieldLen > 0) {
                    for (int extraFieldOff = 0; extraFieldOff + 4 < extraFieldLen;) {
                        final long tagOff = filenameEndOff + extraFieldOff;
                        final int tag = cenReader.readUnsignedShort(tagOff);
                        final int size = cenReader.readUnsignedShort(tagOff + 2);
                        if (extraFieldOff + 4 + size > extraFieldLen) {
                            // Invalid size
                            if (log != null) {
                                log.log("Skipping zip entry with invalid extra field size: " + entryNameSanitized);
                            }
                            break;
                        }
                        if (tag == 1 && size >= 20) {
                            // Zip64 extended information extra field
                            final long uncompressedSize64 = cenReader.readLong(tagOff + 4 + 0);
                            if (uncompressedSize == 0xffffffffL) {
                                uncompressedSize = uncompressedSize64;
                            } else if (uncompressedSize != uncompressedSize64) {
                                throw new IOException("Mismatch in uncompressed size: " + uncompressedSize + " vs. "
                                        + uncompressedSize64 + ": " + entryNameSanitized);
                            }
                            final long compressedSize64 = cenReader.readLong(tagOff + 4 + 8);
                            if (compressedSize == 0xffffffffL) {
                                compressedSize = compressedSize64;
                            } else if (compressedSize != compressedSize64) {
                                throw new IOException("Mismatch in compressed size: " + compressedSize + " vs. "
                                        + compressedSize64 + ": " + entryNameSanitized);
                            }
                            // Only compressed size and uncompressed size are required fields
                            if (size >= 28) {
                                final long pos64 = cenReader.readLong(tagOff + 4 + 16);
                                if (pos == 0xffffffffL) {
                                    pos = pos64;
                                } else if (pos != pos64) {
                                    throw new IOException("Mismatch in entry pos: " + pos + " vs. " + pos64 + ": "
                                            + entryNameSanitized);
                                }
                            }
                            break;

                        } else if (tag == 0x5455 && size >= 5) {
                            // Extended Unix timestamp
                            final byte bits = cenReader.readByte(tagOff + 4 + 0);
                            if ((bits & 1) == 1 && size >= 5 + 8) {
                                lastModifiedMillis = cenReader.readLong(tagOff + 4 + 1) * 1000L;
                            }

                        } else if (tag == 0x5855 && size >= 20) {
                            // Unix extra field (deprecated)
                            lastModifiedMillis = cenReader.readLong(tagOff + 4 + 8) * 1000L;
                            // There are also optional UID and GID fields in this extra field (currently ignored)

                        } else if (tag == 0x7855) {
                            // Info-ZIP Unix UID and GID fields (currently ignored)

                        } else if (tag == 0x7075) {
                            // Info-ZIP Unicode path extra field
                            final byte version = cenReader.readByte(tagOff + 4 + 0);
                            if (version != 1) {
                                throw new IOException("Unknown Unicode entry name format " + version
                                        + " in extra field: " + entryNameSanitized);
                            } else if (size > 9) {
                                // Replace non-Unicode entry name with Unicode version
                                try {
                                    entryNameSanitized = cenReader.readString(tagOff + 9, size - 9);
                                } catch (final IllegalArgumentException e) {
                                    throw new IOException("Malformed extended Unicode entry name for entry: "
                                            + entryNameSanitized);
                                }
                            }
                        }
                        extraFieldOff += 4 + size;
                    }
                }

                int lastModifiedTimeMSDOS = 0;
                int lastModifiedDateMSDOS = 0;
                if (lastModifiedMillis == 0L) {
                    // If Unix timestamp was not provided, convert zip entry timestamp from MS-DOS format
                    lastModifiedTimeMSDOS = cenReader.readUnsignedShort(entOff + 12);
                    lastModifiedDateMSDOS = cenReader.readUnsignedShort(entOff + 14);
                }

                if (compressedSize < 0 || pos < 0) {
                    continue;
                }

                final long locHeaderPos = locPos + pos;
                if (locHeaderPos < 0) {
                    if (log != null) {
                        log.log("Skipping zip entry with invalid loc header position: " + entryNameSanitized);
                    }
                    continue;
                }
                if (locHeaderPos + 4 >= slice.sliceLength) {
                    if (log != null) {
                        log.log("Unexpected EOF when trying to read LOC header: " + entryNameSanitized);
                    }
                    continue;
                }

                // Add zip entry
                final FastZipEntry entry = new FastZipEntry(this, locHeaderPos, entryNameSanitized, isDeflated,
                        compressedSize, uncompressedSize, lastModifiedMillis, lastModifiedTimeMSDOS,
                        lastModifiedDateMSDOS, fileAttributes);
                entries.add(entry);

                // Record manifest entry
                if (entry.entryName.equals(MANIFEST_PATH)) {
                    manifestZipEntry = entry;
                }
            }
        } catch (EOFException | IndexOutOfBoundsException e) {
            // Stop reading entries if any entry is not within file
            if (log != null) {
                log.log("Reached premature EOF"
                        + (entries.isEmpty() ? "" : " after reading zip entry " + entries.get(entries.size() - 1)));
            }
        }

        // Parse manifest file, if present
        if (manifestZipEntry != null) {
            parseManifest(manifestZipEntry, log);
        }

        // For multi-release jars, drop any older or non-versioned entries that are masked by the most recent
        // version-specific entry
        if (isMultiReleaseJar) {
            if (VersionFinder.JAVA_MAJOR_VERSION < 9) {
                if (log != null) {
                    log.log("This is a multi-release jar, but JRE version " + VersionFinder.JAVA_MAJOR_VERSION
                            + " does not support multi-release jars");
                }
            } else {
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
                    log.log("This is a multi-release jar, with versions: " + Join.join(", ", versionsFoundSorted));
                }

                // Sort in decreasing order of version in preparation for version masking
                CollectionUtils.sortIfNotEmpty(entries);

                // Mask files that appear in multiple version sections, so that there is only one entry
                // for each unversioned path, i.e. the versioned path with the highest version number
                final List<FastZipEntry> unversionedZipEntriesMasked = new ArrayList<>(entries.size());
                final Map<String, String> unversionedPathToVersionedPath = new HashMap<>();
                for (final FastZipEntry versionedZipEntry : entries) {
                    if (!unversionedPathToVersionedPath.containsKey(versionedZipEntry.entryNameUnversioned)) {
                        // This is the first FastZipEntry for this entry's unversioned path
                        unversionedPathToVersionedPath.put(versionedZipEntry.entryNameUnversioned,
                                versionedZipEntry.entryName);
                        unversionedZipEntriesMasked.add(versionedZipEntry);
                    } else if (log != null) {
                        log.log(unversionedPathToVersionedPath.get(versionedZipEntry.entryNameUnversioned)
                                + " masks " + versionedZipEntry.entryName);
                    }
                }

                // Override entries with version-masked entries
                entries = unversionedZipEntriesMasked;
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see nonapi.io.github.classgraph.fastzipfilereader.ZipFileSlice#toString()
     */
    @Override
    public String toString() {
        return getPath();
    }
}
