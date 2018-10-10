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
package io.github.classgraph.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.classgraph.ScanSpec;

/** Fast parser for jar manifest files. */
public class JarfileMetadataReader {
    /** If true, this is a JRE jar. */
    public boolean isSystemJar;

    /**
     * The VersionedZipEntries for the zipfile, consisting of the ZipEntry, the version number (for multi-release
     * jars), and the ZipEntry path with any version prefix path and/or Spring-Boot prefix path stripped.
     * 
     * N.B. the wrapped ZipEntries contain only static metadata, so can be reused between different ZipFile
     * instances, even though ZipFile imposes a synchronized lock around each instance.
     */
    public List<VersionedZipEntry> versionedZipEntries;

    /**
     * "Class-Path" entries encountered in the manifest file. Also includes any "BOOT-INF/classes" or
     * "WEB-INF/classes" package roots. Will also include classpath entries for any jars found in one of the lib
     * directories of the jar ("lib/", "BOOT-INF/lib", "WEB-INF/lib", or "WEB-INF/lib-provided"), if
     * {@code ClassGraph#addNestedLibJarsToClasspath(true)} is called before scanning.
     */
    public List<String> classPathEntriesToScan;

    /**
     * A set of path prefixes that have been (or may have been) stripped from
     * {@link VersionedZipEntry#unversionedPath} path strings.
     */
    public List<String> strippedPathPrefixes;

    // -------------------------------------------------------------------------------------------------------------

    private static final String META_INF_PATH_PREFIX = "META-INF/";

    private static final String MANIFEST_PATH = META_INF_PATH_PREFIX + "MANIFEST.MF";

    private static final String MULTI_RELEASE_PATH_PREFIX = META_INF_PATH_PREFIX + "versions/";

    private static final String VERSIONED_META_INF_PATH_PREFIX = MULTI_RELEASE_PATH_PREFIX + META_INF_PATH_PREFIX;

    // -------------------------------------------------------------------------------------------------------------

    private void addClassPathEntryToScan(final String classPathEntryPath) {
        if (this.classPathEntriesToScan == null) {
            this.classPathEntriesToScan = new ArrayList<>();
        }
        this.classPathEntriesToScan.add(classPathEntryPath);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Extract a field from the manifest at a specific character index, and add entries to the classpath. */
    private String extractManifestField(final String manifest, final int classPathIdx) {
        // Manifest files support three different line terminator types, and entries can be split across
        // lines with a line terminator followed by a space.
        final int len = manifest.length();
        final StringBuilder buf = new StringBuilder();
        int curr = classPathIdx;
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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Fast parser for jar manifest files. Doesn't intern strings or create Attribute objects like the Manifest
     * class, so has lower overhead. Only extracts a few specific entries from the manifest file, if present.
     * Assumes there is only one of each entry present in the manifest.
     */
    JarfileMetadataReader(final File jarFile, final ScanSpec scanSpec, final LogNode log) {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            final int numEntries = zipFile.size();
            final String jarFileName = FastPathResolver.resolve(jarFile.getPath());

            // Get all ZipEntries for jar, and see if it is a multi-release jar
            ZipEntry manifestZipEntry = null;
            final List<ZipEntry> zipEntries = new ArrayList<>(zipFile.size());
            for (final Enumeration<? extends ZipEntry> iter = zipFile.entries(); iter.hasMoreElements();) {
                final ZipEntry zipEntry = iter.nextElement();
                if (!zipEntry.isDirectory()) {
                    // Get ZipEntry path
                    String zipEntryPath = zipEntry.getName();
                    if (zipEntryPath.endsWith("/")) {
                        zipEntryPath = zipEntryPath.substring(0, zipEntryPath.length() - 1);
                    }
                    if (zipEntryPath.startsWith("/")) {
                        zipEntryPath = zipEntryPath.substring(1);
                    }
                    zipEntries.add(zipEntry);

                    if (zipEntryPath.equals(MANIFEST_PATH) && manifestZipEntry == null) {
                        manifestZipEntry = zipEntry;
                    }
                }
            }

            boolean isMultiReleaseJar = false;
            String springBootLibPrefix = "BOOT-INF/lib/";
            String springBootClassesPrefix = "BOOT-INF/classes/";
            if (manifestZipEntry != null) {
                try (InputStream inputStream = zipFile.getInputStream(manifestZipEntry)) {
                    final String manifest = FileUtils.readAllBytesAsString(inputStream, manifestZipEntry.getSize(),
                            log);
                    // Attribute names are case insensitive in manifest files
                    final String manifestLower = manifest.toLowerCase();

                    // Check if this is a JRE jar
                    this.isSystemJar = manifestLower
                            .indexOf("\nimplementation-title: java runtime environment") >= 0
                            || manifestLower.indexOf("\nspecification-title: java platform api specification") >= 0
                            || manifestLower.indexOf("\rimplementation-title: java runtime environment") >= 0
                            || manifestLower.indexOf("\rspecification-title: java platform api specification") >= 0;

                    // Check for "Class-Path:" manifest line
                    int classPathIdx = manifestLower.indexOf("\nclass-path:");
                    if (classPathIdx < 0) {
                        classPathIdx = manifestLower.indexOf("\rclass-path:");
                    }
                    if (classPathIdx >= 0) {
                        // Add Class-Path manifest entry value to classpath
                        final String classPathField = extractManifestField(manifest, classPathIdx + 12);
                        for (final String classpathEntry : classPathField.split(" ")) {
                            if (!classpathEntry.isEmpty()) {
                                addClassPathEntryToScan(classpathEntry);
                            }
                        }
                        if (log != null) {
                            log.log("Found Class-Path entry in manifest file: " + classPathField);
                        }
                    }

                    // "If the JAR file does not contain "Multi-Release" attribute in its main manifest then 
                    // it is not a multi-release JAR.":
                    // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-October/013952.html
                    isMultiReleaseJar = manifestLower.indexOf("\nmulti-release: true") >= 0
                            || manifestLower.indexOf("\rmulti-release: true") >= 0;
                    if (isMultiReleaseJar && log != null) {
                        log.log("This is a multi-release jar");
                    }

                    // Check for Spring-Boot manifest lines (in case the default prefixes of "BOOT-INF/classes/"
                    // and "BOOT-INF/lib/" are overridden, which is unlikely but possible)
                    int springBootClassesIdx = manifestLower.indexOf("\nspring-boot-classes:");
                    if (springBootClassesIdx < 0) {
                        springBootClassesIdx = manifest.indexOf("\rspring-boot-classes:");
                    }
                    if (springBootClassesIdx >= 0) {
                        springBootClassesPrefix = extractManifestField(manifest, springBootClassesIdx + 21);
                        while (springBootClassesPrefix.startsWith("/")) {
                            springBootClassesPrefix = springBootClassesPrefix.substring(1);
                        }
                        if (!springBootClassesPrefix.isEmpty() && !springBootClassesPrefix.endsWith("/")) {
                            springBootClassesPrefix += "/";
                        }
                    }
                    int springBootLibIdx = manifestLower.indexOf("\nspring-boot-lib:");
                    if (springBootLibIdx < 0) {
                        springBootLibIdx = manifestLower.indexOf("\rspring-boot-lib:");
                    }
                    if (springBootLibIdx >= 0) {
                        springBootLibPrefix = extractManifestField(manifest, springBootLibIdx + 17);
                        while (springBootLibPrefix.startsWith("/")) {
                            springBootLibPrefix = springBootLibPrefix.substring(1);
                        }
                        if (!springBootLibPrefix.isEmpty() && !springBootLibPrefix.endsWith("/")) {
                            springBootLibPrefix += "/";
                        }
                    }
                }
            }

            // For multi-release jars, find and strip any "META-INF/versions/{versionInt}/" prefix 
            final List<VersionedZipEntry> versionedZipEntriesRaw = new ArrayList<>(numEntries);
            for (final ZipEntry zipEntry : zipEntries) {
                String zipEntryPath = zipEntry.getName();
                if (zipEntryPath.endsWith("/")) {
                    zipEntryPath = zipEntryPath.substring(0, zipEntryPath.length() - 1);
                }
                if (zipEntryPath.startsWith("/")) {
                    zipEntryPath = zipEntryPath.substring(1);
                }

                // If this is not a multi-release jar, or this is the "base layer" (unversioned section of jar) 
                if (!isMultiReleaseJar //
                        || !zipEntryPath.startsWith(MULTI_RELEASE_PATH_PREFIX)
                        // For META-INF/versions/{versionInt}/META-INF/*, don't strip version prefix.
                        // "The intention is that the META-INF directory cannot be versioned."
                        // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-October/013954.html
                        || zipEntryPath.startsWith(VERSIONED_META_INF_PATH_PREFIX)) {
                    // Give the base section a version number of 8, so that it sorts after versioned sections
                    versionedZipEntriesRaw.add(new VersionedZipEntry(zipEntry, /* version = */ 8, zipEntryPath));
                } else if (zipEntryPath.length() > MULTI_RELEASE_PATH_PREFIX.length() + 1) {
                    // This is a multi-release jar path
                    final int nextSlashIdx = zipEntryPath.indexOf('/', MULTI_RELEASE_PATH_PREFIX.length());
                    if (nextSlashIdx > 0) {
                        // Get path after version number, i.e. strip "META-INF/versions/{versionInt}/" prefix
                        final String unversionedPath = zipEntryPath.substring(nextSlashIdx + 1);
                        if (!unversionedPath.isEmpty()) {
                            // If path is not empty, parse version number
                            final String versionStr = zipEntryPath.substring(MULTI_RELEASE_PATH_PREFIX.length(),
                                    nextSlashIdx);
                            try {
                                // For multi-release jars, the version number has to be an int >= 9
                                final int versionInt = Integer.parseInt(versionStr);
                                if (versionInt >= 9
                                        // Only accept version numbers up to the JRE version number
                                        && versionInt <= VersionFinder.JAVA_MAJOR_VERSION) {
                                    // Found a scannable versioned section of the multi-release jar
                                    if (!zipEntryPath.startsWith(VERSIONED_META_INF_PATH_PREFIX)) {
                                        // Strip "META-INF/versions/{versionInt}/" prefix
                                        isMultiReleaseJar = true;
                                        versionedZipEntriesRaw
                                                .add(new VersionedZipEntry(zipEntry, versionInt, unversionedPath));
                                    }
                                } else {
                                    // Ignore out-of-range versions
                                }
                            } catch (final NumberFormatException e) {
                                // Ignore
                            }
                        }
                    }
                }
            }

            // Sort in decreasing order of version number, so that higher versions mask lower versions. See:
            // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2018-September/013935.html
            Collections.sort(versionedZipEntriesRaw);

            // Mask files that appear in multiple version sections, so that there is only one VersionedZipEntry
            // for each unversioned path (the versioned path with the highest version number).
            // (There may be multiple versioned resources with the same unversioned path.)
            final List<VersionedZipEntry> versionedZipEntriesMasked = new ArrayList<>(numEntries);
            final Map<String, VersionedZipEntry> unversionedPathsEncountered = new HashMap<>();
            for (final VersionedZipEntry versionedZipEntry : versionedZipEntriesRaw) {
                // Find first ZipEntry for each unversioned path
                if (!unversionedPathsEncountered.containsKey(versionedZipEntry.unversionedPath)) {
                    unversionedPathsEncountered.put(versionedZipEntry.unversionedPath, versionedZipEntry);
                    versionedZipEntriesMasked.add(versionedZipEntry);
                } else if (log != null) {
                    log.log(unversionedPathsEncountered.get(versionedZipEntry.unversionedPath).zipEntry.getName()
                            + " masks " + versionedZipEntry.zipEntry.getName());
                }
            }

            // String Spring-Boot prefixes ("BOOT-INF/classes/", "WEB-INF/classes/") from paths, and again mask
            // classfiles, in case there are multiple classfiles with the same path once prefixes are stripped.
            // Since the paths were sorted lexicographically above, masking is applied in the order of "", then
            // "BOOT-INF/classes/", then "WEB-INF/classes/", which might be different from Spring-Boot's own
            // class resolution order, but having the same class defined in more than one of these three package
            // roots should be more or less non-existent, so this shouldn't be a problem.
            // Also search for lib jars ("lib/*.jar", "BOOT-INF/lib/*.jar", etc.).
            strippedPathPrefixes = Arrays.asList(springBootClassesPrefix, "WEB-INF/classes/");
            versionedZipEntries = new ArrayList<>();
            final Set<String> strippedPathsEncountered = new HashSet<>();
            for (final VersionedZipEntry versionedZipEntry : versionedZipEntriesMasked) {
                final String unversionedPath = versionedZipEntry.unversionedPath;

                // Strip "BOOT-INF/classes/" or "WEB-INF/classes/" from beginning of classfile paths, if present
                String strippedPath = unversionedPath;
                for (final String prefixToStrip : strippedPathPrefixes) {
                    if (unversionedPath.startsWith(prefixToStrip)) {
                        strippedPath = unversionedPath.substring(prefixToStrip.length());
                        break;
                    }
                }

                // Apply path masking
                if (strippedPathsEncountered.add(strippedPath)) {
                    // This stripped path is unique -- schedule it for scanning
                    versionedZipEntries.add(strippedPath.equals(unversionedPath) ? versionedZipEntry
                            : new VersionedZipEntry(versionedZipEntry.zipEntry, versionedZipEntry.version,
                                    strippedPath));

                    // Look for nested jarfiles in lib directories
                    if ((unversionedPath.startsWith(springBootLibPrefix)
                            || unversionedPath.startsWith("WEB-INF/lib/")
                            || unversionedPath.startsWith("WEB-INF/lib-provided/")
                            || unversionedPath.startsWith("lib/")) //
                            && unversionedPath.endsWith(".jar")) {
                        // Found a nested jar
                        if (scanSpec.scanNestedJars) {
                            String zipEntryPath = versionedZipEntry.zipEntry.getName();
                            if (zipEntryPath.endsWith("/")) {
                                zipEntryPath = zipEntryPath.substring(0, zipEntryPath.length() - 1);
                            }
                            if (zipEntryPath.startsWith("/")) {
                                zipEntryPath = zipEntryPath.substring(1);
                            }
                            if (log != null) {
                                log.log("Found lib jar: " + zipEntryPath);
                            }
                            // Add the nested lib jar to the classpath to be scanned. This will cause the jar to
                            // be extracted from the zipfile by one of the worker threads.
                            final String libJarPath = jarFileName + "!" + zipEntryPath;
                            addClassPathEntryToScan(libJarPath);
                        } else {
                            // Not scanning nested jars
                            if (log != null) {
                                log.log("Skipping lib jar because nested jar scanning is disabled: "
                                        + versionedZipEntry.zipEntry.getName());
                            }
                        }
                    }
                }
            }

            // Sort the final ZipEntries lexicographically again (since prefixes may have been stripped)
            Collections.sort(versionedZipEntries);

        } catch (final IOException e) {
            versionedZipEntries = Collections.emptyList();
            if (log != null) {
                log.log("Exception while opening jarfile " + jarFile, e);
            }
        }
    }
}
