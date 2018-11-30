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
package io.github.classgraph.fastzipfilereader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.ScanSpec;

/**
 * A logical zipfile, which represents a zipfile contained within a {@link ZipFileSlice} of a
 * {@link PhysicalZipFile}.
 */
public class LogicalZipFile extends ZipFileSlice {
    private final ZipFileSliceReader zipFileSliceReader;
    private List<FastZipEntry> entries;

    /** If true, this is a JRE jar. */
    public boolean isJREJar;

    /** If true, this is a multi-release jar. */
    public boolean isMultiReleaseJar;

    /**
     * "Class-Path" entries encountered in the manifest file. Also includes any "BOOT-INF/classes" or
     * "WEB-INF/classes" package roots. Will also include classpath entries for any jars found in one of the lib
     * directories of the jar ("lib/", "BOOT-INF/lib", "WEB-INF/lib", or "WEB-INF/lib-provided"), if
     * {@code ClassGraph#addNestedLibJarsToClasspath(true)} is called before scanning.
     */
    public List<String> additionalClassPathEntriesToScan;

    /** A set of classpath roots found in the classpath for this zipfile. */
    Set<String> classpathRoots = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // -------------------------------------------------------------------------------------------------------------

    static final String META_INF_PATH_PREFIX = "META-INF/";

    static final String MANIFEST_PATH = META_INF_PATH_PREFIX + "MANIFEST.MF";

    static final String MULTI_RELEASE_PATH_PREFIX = META_INF_PATH_PREFIX + "versions/";

    // -------------------------------------------------------------------------------------------------------------

    private void addAdditionalClassPathEntryToScan(final String classPathEntryPath) {
        if (this.additionalClassPathEntriesToScan == null) {
            this.additionalClassPathEntriesToScan = new ArrayList<>();
        }
        this.additionalClassPathEntriesToScan.add(classPathEntryPath);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Get the zipfile entries. */
    public List<FastZipEntry> getEntries() {
        return entries;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Construct a logical zipfile from a slice of a physical zipfile. */
    LogicalZipFile(final ZipFileSlice zipFileSlice, final ScanSpec scanSpec, final LogNode log) throws IOException {
        super(zipFileSlice);
        zipFileSliceReader = new ZipFileSliceReader(this);
        readCentralDirectory(scanSpec, log);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Extract a value from the manifest, if the given key is found in the manifest, otherwise return null. */
    private String extractManifestField(final String keyLower, final String manifestLower, final String manifest) {
        final int keyIdx = manifestLower.indexOf(keyLower);
        if (keyIdx < 0) {
            return null;
        }

        // Manifest files support three different line terminator types, and entries can be split across
        // lines with a line terminator followed by a space.
        final int len = manifest.length();
        final StringBuilder buf = new StringBuilder();
        int curr = keyIdx + keyLower.length();
        if (curr < len && manifest.charAt(curr) == ' ') {
            curr++;
        }
        for (; curr < len; curr++) {
            final char c = manifest.charAt(curr);
            if (c == '\r' && (curr < len - 1 ? manifest.charAt(curr + 1) : '\n') == '\n') {
                if ((curr < len - 2 ? manifest.charAt(curr + 2) : '\n') == ' ') {
                    curr += 2;
                } else {
                    break;
                }
            } else if (c == '\r') {
                if ((curr < len - 1 ? manifest.charAt(curr + 1) : '\n') == ' ') {
                    curr += 1;
                } else {
                    break;
                }
            } else if (c == '\n') {
                if ((curr < len - 1 ? manifest.charAt(curr + 1) : '\n') == ' ') {
                    curr += 1;
                } else {
                    break;
                }
            } else {
                buf.append(c);
            }
        }
        return buf.toString().trim();
    }

    /** Parse the manifest entry of a zipfile. */
    private void parseManifest(final FastZipEntry manifestZipEntry, final LogNode log) throws IOException {
        // Load contents of manifest entry
        final String manifest = manifestZipEntry.loadAsString(log);

        // Attribute names are case insensitive in manifest files, so convert to lowercase
        final String manifestLower = manifest.toLowerCase();

        // Check if this is a JRE jar
        isJREJar = manifestLower.contains("\nimplementation-title: java runtime environment")
                || manifestLower.contains("\nspecification-title: java platform api specification")
                || manifestLower.contains("\rimplementation-title: java runtime environment")
                || manifestLower.contains("\rspecification-title: java platform api specification");

        // Check for "Class-Path:" manifest line
        String classPathField = extractManifestField("\nclass-path:", manifestLower, manifest);
        if (classPathField == null) {
            classPathField = extractManifestField("\rclass-path:", manifestLower, manifest);
        }
        if (classPathField != null) {
            // Add Class-Path manifest entry values to classpath
            if (log != null) {
                log.log("Found Class-Path entry in manifest file: " + classPathField);
            }
            for (final String classpathEntry : classPathField.split(" ")) {
                if (!classpathEntry.isEmpty()) {
                    addAdditionalClassPathEntryToScan(classpathEntry);
                }
            }
        }

        // Check for Spring-Boot manifest lines, in case the default prefixes of "BOOT-INF/classes/"
        // and "BOOT-INF/lib/" are overridden, which is unlikely but possible
        String springBootClassesField = extractManifestField("\nspring-boot-classes:", manifestLower, manifest);
        if (springBootClassesField == null) {
            springBootClassesField = extractManifestField("\rspring-boot-classes:", manifestLower, manifest);
        }
        if (springBootClassesField != null && !springBootClassesField.equals("BOOT-INF/classes")
                && !springBootClassesField.equals("BOOT-INF/classes/")) {
            throw new IOException("Spring boot classes are at \"" + springBootClassesField
                    + "\" rather than the standard location \"BOOT-INF/classes/\" -- please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
        }
        String springBootLibField = extractManifestField("\nspring-boot-lib:", manifestLower, manifest);
        if (springBootLibField == null) {
            springBootLibField = extractManifestField("\rspring-boot-lib:", manifestLower, manifest);
        }
        if (springBootLibField != null && !springBootLibField.equals("BOOT-INF/lib")
                && !springBootLibField.equals("BOOT-INF/lib/")) {
            throw new IOException("Spring boot classes are at \"" + springBootLibField
                    + "\" rather than the standard location \"BOOT-INF/lib/\" -- please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
        }

        // "If the JAR file does not contain "Multi-Release" attribute in its main manifest then 
        // it is not a multi-release JAR.":
        // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-October/013952.html
        isMultiReleaseJar = manifestLower.contains("\nmulti-release: true")
                || manifestLower.contains("\rmulti-release: true");
        if (isMultiReleaseJar) {
            if (log != null) {
                log.log("This is a multi-release jar");
            }

            // Sort in decreasing order of version in preparation for version masking
            Collections.sort(entries);

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
                    log.log(unversionedPathToVersionedPath.get(versionedZipEntry.entryNameUnversioned) + " masks "
                            + versionedZipEntry.entryName);
                }
            }

            // Override entries with version-masked entries
            this.entries = unversionedZipEntriesMasked;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    private static int getShort(final byte[] buf, final long off) throws IOException {
        final int ioff = (int) off;
        if (ioff < 0 || ioff > buf.length - 2) {
            throw new IndexOutOfBoundsException();
        }
        return ((buf[ioff + 1] & 0xff) << 8) | (buf[ioff + 0] & 0xff);
    }

    private static int getInt(final byte[] buf, final long off) throws IOException {
        final int ioff = (int) off;
        if (ioff < 0 || ioff > buf.length - 4) {
            throw new IndexOutOfBoundsException();
        }
        return ((buf[ioff + 3] & 0xff) << 24) | ((buf[ioff + 2] & 0xff) << 16) | ((buf[ioff + 1] & 0xff) << 8)
                | (buf[ioff + 0] & 0xff);
    }

    private static long getLong(final byte[] buf, final long off) throws IOException {
        final int ioff = (int) off;
        if (ioff < 0 || ioff > buf.length - 8) {
            throw new IndexOutOfBoundsException();
        }
        return ((buf[ioff + 7] & 0xffL) << 56) | ((buf[ioff + 6] & 0xffL) << 48)
                | ((buf[ioff + 5] & 0xffL) << 40) + ((buf[ioff + 4] & 0xffL) << 32)
                | ((buf[ioff + 3] & 0xffL) << 24) | ((buf[ioff + 2] & 0xffL) << 16) + ((buf[ioff + 1] & 0xffL) << 8)
                | (buf[ioff + 0] & 0xffL);
    }

    private static String getString(final byte[] buf, final long off, final int numBytes) throws IOException {
        final int ioff = (int) off;
        if (ioff < 0 || ioff > buf.length - numBytes) {
            throw new IndexOutOfBoundsException();
        }
        return new String(buf, ioff, numBytes, StandardCharsets.UTF_8);
    }

    /** Read the central directory of the zipfile. */
    private void readCentralDirectory(final ScanSpec scanSpec, final LogNode log) throws IOException {
        // Scan for End Of Central Directory (EOCD) signature
        long eocdPos = -1;
        for (long i = len - 22; i >= 0; --i) {
            if (zipFileSliceReader.getInt(i) == 0x06054b50) {
                eocdPos = i;
                break;
            }
        }
        if (eocdPos < 0) {
            throw new IOException("Jarfile central directory signature not found: " + physicalZipFile.file);
        }
        long numEnt = zipFileSliceReader.getShort(eocdPos + 8);
        if (zipFileSliceReader.getShort(eocdPos + 4) > 0 || zipFileSliceReader.getShort(eocdPos + 6) > 0
                || numEnt != zipFileSliceReader.getShort(eocdPos + 10)) {
            throw new IOException("Multi-disk jarfiles not supported: " + physicalZipFile.file);
        }
        long cenOff = zipFileSliceReader.getInt(eocdPos + 16);
        long cenSize = zipFileSliceReader.getInt(eocdPos + 12);
        final long cenPos = eocdPos - cenSize;
        if (cenSize > eocdPos) {
            throw new IOException("Central directory size out of range: " + cenSize + " vs. " + eocdPos + ": "
                    + physicalZipFile.file);
        }

        // Check for Zip64 End Of Central Directory Locator record
        final long zip64cdLocIdx = eocdPos - 20;
        long entriesTotSizeL;
        if (zip64cdLocIdx >= 0 && zipFileSliceReader.getInt(zip64cdLocIdx) == 0x07064b50) {
            if (zipFileSliceReader.getInt(zip64cdLocIdx + 4) > 0
                    || zipFileSliceReader.getInt(zip64cdLocIdx + 16) > 1) {
                throw new IOException("Multi-disk jarfiles not supported: " + physicalZipFile.file);
            }
            final long eocdPos64 = zipFileSliceReader.getLong(zip64cdLocIdx + 8);
            if (zipFileSliceReader.getInt(eocdPos64) != 0x06064b50) {
                throw new IOException("Zip64 central directory at location " + eocdPos64
                        + " does not have Zip64 central directory header: " + physicalZipFile.file);
            }
            final long numEnt64 = zipFileSliceReader.getLong(eocdPos64 + 24);
            if (zipFileSliceReader.getInt(eocdPos64 + 16) > 0 || zipFileSliceReader.getInt(eocdPos64 + 20) > 0
                    || numEnt64 != zipFileSliceReader.getLong(eocdPos64 + 32)) {
                throw new IOException("Multi-disk jarfiles not supported: " + physicalZipFile.file);
            }
            if (numEnt != numEnt64 && numEnt != 0xffff) {
                // Entry size mismatch -- trigger manual counting of entries
                numEnt = -1L;
            } else {
                numEnt = numEnt64;
            }

            final long cenSize64 = zipFileSliceReader.getLong(eocdPos64 + 40);
            if (cenSize != cenSize64 && cenSize != 0xffffffff) {
                throw new IOException("Mismatch in central directory size: " + cenSize + " vs. " + cenSize64 + ": "
                        + physicalZipFile.file);
            }
            cenSize = cenSize64;

            final long cenOff64 = zipFileSliceReader.getLong(eocdPos64 + 48);
            if (cenOff != cenOff64 && cenOff != 0xffffffff) {
                throw new IOException("Mismatch in central directory offset: " + cenOff + " vs. " + cenOff64 + ": "
                        + physicalZipFile.file);
            }
            cenOff = cenOff64;

            // Don't include Zip64 end of central directory header in central directory entries to read
            entriesTotSizeL = cenSize - 20;
        } else {
            // There was no Zip64 end of central directory header
            entriesTotSizeL = cenSize;
        }

        // Get offset of first local file header
        final long locPos = cenPos - cenOff;
        if (locPos < 0) {
            throw new IOException("Local file header offset out of range: " + locPos + ": " + physicalZipFile.file);
        }

        // Read entries into a byte array, if central directory is smaller than 2GB. If central directory
        // is larger than 2GB, need to read each entry field from the file directly using ZipFileSliceReader.
        final byte[] entryBytes = entriesTotSizeL > Integer.MAX_VALUE ? null : new byte[(int) entriesTotSizeL];
        if (entryBytes != null) {
            zipFileSliceReader.read(cenPos, entryBytes, 0, (int) entriesTotSizeL);
        }

        if (numEnt == -1L) {
            // numEnt and numEnt64 were inconsistent -- manually count entries
            numEnt = 0;
            for (long entOff = 0; entOff + 46 <= entriesTotSizeL;) {
                final int sig = entryBytes != null ? getInt(entryBytes, entOff)
                        : zipFileSliceReader.getInt(cenPos + entOff);
                if (sig != 0x02014b50) {
                    throw new IOException("Invalid central directory signature: 0x" + Integer.toString(sig, 16)
                            + ": " + physicalZipFile.file);
                }
                final int filenameLen = entryBytes != null ? getShort(entryBytes, entOff + 28)
                        : zipFileSliceReader.getShort(cenPos + entOff + 28);
                final int extraFieldLen = entryBytes != null ? getShort(entryBytes, entOff + 30)
                        : zipFileSliceReader.getShort(cenPos + entOff + 30);
                final int commentLen = entryBytes != null ? getShort(entryBytes, entOff + 32)
                        : zipFileSliceReader.getShort(cenPos + entOff + 32);
                entOff += 46 + filenameLen + extraFieldLen + commentLen;
                numEnt++;
            }
        }

        //  Can't have more than Integer.MAX_VALUE entries, since they are stored in an ArrayList
        if (numEnt > Integer.MAX_VALUE) {
            // One alternative in this (impossibly rare) situation would be to return only the first 2B entries
            throw new IOException("Too many zipfile entries: " + numEnt + " > 2B");
        }

        // Make sure there's no DoS attack vector by using a fake number of entries
        if (entryBytes != null && numEnt > entryBytes.length / 46) {
            // The smallest directory entry is 46 bytes in size
            throw new IOException("Too many zipfile entries: " + numEnt + " (expected a max of "
                    + entryBytes.length / 46 + " based on central directory size)");
        }

        // Enumerate entries
        entries = new ArrayList<>((int) numEnt);
        FastZipEntry manifestZipEntry = null;
        for (long entOff = 0, entSize; entOff + 46 <= entriesTotSizeL; entOff += entSize) {
            final int sig = entryBytes != null ? getInt(entryBytes, entOff)
                    : zipFileSliceReader.getInt(cenPos + entOff);
            if (sig != 0x02014b50) {
                throw new IOException("Invalid central directory signature: 0x" + Integer.toString(sig, 16) + ": "
                        + physicalZipFile.file);
            }
            final int filenameLen = entryBytes != null ? getShort(entryBytes, entOff + 28)
                    : zipFileSliceReader.getShort(cenPos + entOff + 28);
            final int extraFieldLen = entryBytes != null ? getShort(entryBytes, entOff + 30)
                    : zipFileSliceReader.getShort(cenPos + entOff + 30);
            final int commentLen = entryBytes != null ? getShort(entryBytes, entOff + 32)
                    : zipFileSliceReader.getShort(cenPos + entOff + 32);
            entSize = 46 + filenameLen + extraFieldLen + commentLen;

            // Get and sanitize entry name
            final long filenameStartOff = entOff + 46;
            final long filenameEndOff = filenameStartOff + filenameLen;
            if (filenameEndOff > entriesTotSizeL) {
                if (log != null) {
                    log.log("Filename extends past end of entry -- skipping entry at offset " + entOff);
                }
                break;
            }
            String entryName = entryBytes != null ? getString(entryBytes, filenameStartOff, filenameLen)
                    : zipFileSliceReader.getString(cenPos + filenameStartOff, filenameLen);
            while (entryName.startsWith("/")) {
                entryName = entryName.substring(1);
            }
            while (entryName.startsWith("./") || entryName.startsWith("../")) {
                entryName = entryName.startsWith("./") ? entryName.substring(2) : entryName.substring(3);
            }
            entryName = entryName.replace("/./", "/");
            entryName = entryName.replace("/../", "/");
            if (entryName.endsWith("/")) {
                // Skip directory entries
                continue;
            }

            // Check entry flag bits
            final int flags = entryBytes != null ? getShort(entryBytes, entOff + 8)
                    : zipFileSliceReader.getShort(cenPos + entOff + 8);
            if ((flags & 1) != 0) {
                if (log != null) {
                    log.log("Skipping encrypted zip entry: " + entryName);
                }
                continue;
            }

            // Check compression method
            final int compressionMethod = entryBytes != null ? getShort(entryBytes, entOff + 10)
                    : zipFileSliceReader.getShort(cenPos + entOff + 10);
            if (compressionMethod != /* stored */ 0 && compressionMethod != /* deflated */ 8) {
                if (log != null) {
                    log.log("Skipping zip entry with invalid compression method " + compressionMethod + ": "
                            + entryName);
                }
                continue;
            }
            final boolean isDeflated = compressionMethod == /* deflated */ 8;

            // Get compressed and uncompressed size
            long compressedSize = entryBytes != null ? getInt(entryBytes, entOff + 20)
                    : zipFileSliceReader.getInt(cenPos + entOff + 20);
            long uncompressedSize = entryBytes != null ? getInt(entryBytes, entOff + 24)
                    : zipFileSliceReader.getInt(cenPos + entOff + 24);
            long pos = entryBytes != null ? getInt(entryBytes, entOff + 42)
                    : zipFileSliceReader.getInt(cenPos + entOff + 42);

            // Check for Zip64 header in extra fields
            if (extraFieldLen > 0) {
                for (int extraFieldOff = 0; extraFieldOff + 4 < extraFieldLen;) {
                    final long tagOff = filenameEndOff + extraFieldOff;
                    final int tag = entryBytes != null ? getShort(entryBytes, tagOff)
                            : zipFileSliceReader.getShort(cenPos + tagOff);
                    final int size = entryBytes != null ? getShort(entryBytes, tagOff + 2)
                            : zipFileSliceReader.getShort(cenPos + tagOff + 2);
                    if (extraFieldOff + 4 + size > extraFieldLen) {
                        // Invalid size
                        break;
                    }
                    if (tag == /* EXTID_ZIP64 */ 1 && size >= 24) {
                        final long uncompressedSizeL = entryBytes != null ? getLong(entryBytes, tagOff + 4 + 0)
                                : zipFileSliceReader.getLong(cenPos + tagOff + 4 + 0);
                        if (uncompressedSize == 0xffffffff) {
                            uncompressedSize = uncompressedSizeL;
                        }
                        final long compressedSizeL = entryBytes != null ? getLong(entryBytes, tagOff + 4 + 8)
                                : zipFileSliceReader.getLong(cenPos + tagOff + 4 + 8);
                        if (compressedSize == 0xffffffff) {
                            compressedSize = compressedSizeL;
                        }
                        final long posL = entryBytes != null ? getLong(entryBytes, tagOff + 4 + 16)
                                : zipFileSliceReader.getLong(cenPos + tagOff + 4 + 16);
                        if (pos == 0xffffffff) {
                            pos = posL;
                        }
                        break;
                    }
                    extraFieldOff += 4 + size;
                }
            }
            if (compressedSize < 0 || pos < 0) {
                continue;
            }

            final long locHeaderPos = locPos + pos;
            if (locHeaderPos < 0) {
                if (log != null) {
                    log.log("Skipping zip entry with invalid loc header position: " + entryName);
                }
                continue;
            }
            if (locHeaderPos + 4 >= len) {
                if (log != null) {
                    log.log("Unexpected EOF when trying to read LOC header: " + entryName);
                }
                continue;
            }

            // Add zip entry
            final FastZipEntry entry = new FastZipEntry(this, locHeaderPos, entryName, isDeflated, compressedSize,
                    uncompressedSize);
            entries.add(entry);

            // Record manifest entry
            if (entry.entryName.equals(MANIFEST_PATH)) {
                manifestZipEntry = entry;
            }

            if (scanSpec.scanNestedJars) {
                // Add any lib jars to classpath
                if ((entry.entryNameUnversioned.startsWith("BOOT-INF/lib/")
                        || entry.entryNameUnversioned.startsWith("BOOT-INF/lib-provided/")
                        || entry.entryNameUnversioned.startsWith("WEB-INF/lib/")
                        || entry.entryNameUnversioned.startsWith("WEB-INF/lib-provided/")
                        || entry.entryNameUnversioned.startsWith("lib/")) //
                        && entry.entryNameUnversioned.endsWith(".jar")) {
                    final String entryPath = entry.getPath();
                    if (log != null) {
                        log.log("Adding nested lib jar to classpath: " + entryPath);
                    }
                    addAdditionalClassPathEntryToScan(entryPath);
                }
            }
        }

        // Parse manifest file, if present
        if (manifestZipEntry != null) {
            parseManifest(manifestZipEntry, log);
        }
    }
}
