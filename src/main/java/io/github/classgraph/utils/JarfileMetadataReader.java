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
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.classgraph.ScanSpec;

/** Fast parser for jar manifest files. */
public class JarfileMetadataReader {
    /** If true, this is a JRE jar. */
    public boolean isSystemJar;

    /**
     * The ZipEntries for the zipfile. ZipEntries contain only static metadata, so can be reused between different
     * ZipFile instances, even though ZipFile imposes a synchronized lock around each instance.
     */
    public List<ZipEntry> zipEntries;

    /**
     * "Class-Path" entries encountered in the manifest file. Also includes any "!BOOT-INF/classes" or
     * "!WEB-INF/classes" package roots. Will also include classpath entries for any jars found in one of the lib
     * directories of the jar ("lib/", "BOOT-INF/lib", "WEB-INF/lib", or "WEB-INF/lib-provided"), if
     * {@code ClassGraph#addNestedLibJarsToClasspath(true)} is called before scanning.
     */
    public List<String> classPathEntriesToScan;

    /** If non-empty, contains a multi-release version root path, e.g. "META-INF/versions/10/". */
    public String mainPackageRootPrefix = "";

    /**
     * If non-null, contains a list of other package roots to scan (e.g. for Spring-Boot jars, where in addition to
     * "BOOT-INF/classes", you also need to scan "" if you want the Spring-Boot boot classes to be findable).
     */
    public List<String> additionalPackageRootPrefixes;

    /**
     * Any jarfiles found in "lib/", "BOOT-INF/lib", "WEB-INF/lib", or "WEB-INF/lib-provided". If classes need to be
     * loaded from this jar, and no classloader can be found to load them, then each of these subdirectories will
     * get unzipped and added to the classpath of a new URLClassLoader.
     */
    private List<String> libJarPaths;

    // -------------------------------------------------------------------------------------------------------------

    private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";

    private static final String MULTI_RELEASE_PATH_PREFIX = "META-INF/versions/";

    // -------------------------------------------------------------------------------------------------------------

    private void addClassPathEntryToScan(final String classPathEntryPath) {
        if (this.classPathEntriesToScan == null) {
            this.classPathEntriesToScan = new ArrayList<>();
        }
        this.classPathEntriesToScan.add(classPathEntryPath);
    }

    private void addSpaceDelimitedClassPathToScan(final String zipFilePath, final String classPath) {
        for (final String classpathEntry : classPath.split(" ")) {
            if (!classpathEntry.isEmpty()) {
                addClassPathEntryToScan(zipFilePath == null ? classpathEntry
                        // For Spring-Boot, add zipfile name to beginning of classpath entry
                        : zipFilePath + "!" + classpathEntry);
            }
        }
    }

    private void addAdditionalPackageRootPrefix(final String additionalPackageRootPrefix) {
        if (this.additionalPackageRootPrefixes == null) {
            this.additionalPackageRootPrefixes = new ArrayList<>();
        }
        this.additionalPackageRootPrefixes.add(additionalPackageRootPrefix);
    }

    private void addLibJarEntry(final String libJarPath) {
        if (this.libJarPaths == null) {
            this.libJarPaths = new ArrayList<>();
        }
        this.libJarPaths.add(libJarPath);
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
            final List<ZipEntry> rawZipEntries = new ArrayList<>(numEntries);
            final String jarFileName = FastPathResolver.resolve(jarFile.getPath());

            // Get all ZipEntries for jar, and see if it is a multi-release jar
            int highestScannableVersion = 8;
            for (final Enumeration<? extends ZipEntry> iter = zipFile.entries(); iter.hasMoreElements();) {
                final ZipEntry zipEntry = iter.nextElement();
                if (!zipEntry.isDirectory()) {
                    // Store non-directory ZipEntries for later use, so that they don't have to be recreated every
                    // time the jar is read (this avoids duplicate work when multiple threads are scanning the same
                    // jar). We don't need the directory ZipEntries during scanning.
                    rawZipEntries.add(zipEntry);

                    // Get ZipEntry path
                    String zipEntryPath = zipEntry.getName();
                    if (zipEntryPath.endsWith("/")) {
                        zipEntryPath = zipEntryPath.substring(0, zipEntryPath.length() - 1);
                    }
                    if (zipEntryPath.startsWith("/")) {
                        zipEntryPath = zipEntryPath.substring(1);
                    }

                    if (zipEntryPath.startsWith(MULTI_RELEASE_PATH_PREFIX)
                            && zipEntryPath.length() > MULTI_RELEASE_PATH_PREFIX.length() + 1) {
                        // If this is a multi-release jar, don't scan the root -- instead, scan the highest version
                        // less than or equal to the current JVM version
                        final int nextSlashIdx = zipEntryPath.indexOf('/', MULTI_RELEASE_PATH_PREFIX.length());
                        final String versionStr = zipEntryPath.substring(MULTI_RELEASE_PATH_PREFIX.length(),
                                nextSlashIdx < 0 ? zipEntryPath.length() : nextSlashIdx);
                        try {
                            // Fur multi-release jars, the version number has to be an int, 9 or higher
                            final int versionInt = Integer.parseInt(versionStr);
                            if (versionInt <= VersionFinder.JAVA_MAJOR_VERSION
                                    && versionInt > highestScannableVersion) {
                                // Found a higher version number to scan in a multi-release jar
                                highestScannableVersion = versionInt;
                            }
                        } catch (final NumberFormatException e) {
                            // Ignore
                        }
                    }
                }
            }

            // Use META-INF/versions/X as the package root, if present
            if (highestScannableVersion >= 9) {
                final String versionRoot = MULTI_RELEASE_PATH_PREFIX + highestScannableVersion;
                if (log != null) {
                    log.log("Found multi-release version jar -- switching package root to: " + versionRoot);
                }
                mainPackageRootPrefix = versionRoot + "/";
            }

            // Find META-INF/versions/X/META-INF/MANIFEST.MF or META-INF/MANIFEST.MF if present
            ZipEntry manifestZipEntry = null;
            final String versionManifestZipEntryPath = mainPackageRootPrefix + MANIFEST_PATH;
            for (final ZipEntry zipEntry : rawZipEntries) {
                String zipEntryPath = zipEntry.getName();
                if (zipEntryPath.startsWith("/")) {
                    zipEntryPath = zipEntryPath.substring(1);
                }
                if (zipEntryPath.equals(versionManifestZipEntryPath)) {
                    manifestZipEntry = zipEntry;
                } else if (zipEntryPath.equals(MANIFEST_PATH)) {
                    // Fall back to META-INF/MANIFEST.MF if there is no version-specific manifest
                    manifestZipEntry = zipEntry;
                }
            }

            final String webInfClassesPrefix = mainPackageRootPrefix + "WEB-INF/classes/";
            final String webInfLibPrefix = mainPackageRootPrefix + "WEB-INF/lib/";
            final String webInfLibProvidedPrefix = mainPackageRootPrefix + "WEB-INF/lib-provided/";
            final String libPrefix = mainPackageRootPrefix + "lib/";

            // Parse manifest file, if present
            String springBootLibPrefix = mainPackageRootPrefix + "BOOT-INF/lib";
            String springBootClassesPrefix = mainPackageRootPrefix + "BOOT-INF/classes";
            if (manifestZipEntry != null) {
                try (InputStream inputStream = zipFile.getInputStream(manifestZipEntry)) {
                    final String manifest = FileUtils.readAllBytesAsString(inputStream, manifestZipEntry.getSize(),
                            log);

                    // Check if this is a JRE jar
                    this.isSystemJar = //
                            manifest.indexOf("\nImplementation-Title: Java Runtime Environment") > 0 || manifest
                                    .indexOf("\nSpecification-Title: Java Platform API Specification") > 0;

                    // Check for "Class-Path:" manifest line
                    final int classPathIdx = manifest.indexOf("\nClass-Path:");
                    if (classPathIdx >= 0) {
                        // Add Class-Path manifest entry value to classpath
                        final String classPathField = extractManifestField(manifest, classPathIdx + 12);
                        addSpaceDelimitedClassPathToScan(/* zipFilePath = */ null, classPathField);
                        if (log != null) {
                            log.log("Found Class-Path entry in manifest file: " + classPathField);
                        }
                    }

                    // Check for Spring-Boot manifest lines
                    final int springBootClassesIdx = manifest.indexOf("\nSpring-Boot-Classes:");
                    if (springBootClassesIdx >= 0) {
                        springBootClassesPrefix = mainPackageRootPrefix
                                + extractManifestField(manifest, springBootClassesIdx + 21);
                        if (springBootClassesPrefix.startsWith("/")) {
                            springBootClassesPrefix = springBootClassesPrefix.substring(1);
                        }
                        if (!springBootClassesPrefix.isEmpty() && !springBootClassesPrefix.endsWith("/")) {
                            springBootClassesPrefix += '/';
                        }
                    }
                    final int springBootLibIdx = manifest.indexOf("\nSpring-Boot-Lib:");
                    if (springBootLibIdx >= 0) {
                        springBootLibPrefix = mainPackageRootPrefix
                                + extractManifestField(manifest, springBootLibIdx + 17);
                        if (springBootLibPrefix.startsWith("/")) {
                            springBootLibPrefix = springBootLibPrefix.substring(1);
                        }
                        if (!springBootLibPrefix.isEmpty() && !springBootLibPrefix.endsWith("/")) {
                            springBootLibPrefix += '/';
                        }
                    }
                }
            }

            // Ignore non-version-specific ZipEntries, if this is a multi-release jar
            if (!mainPackageRootPrefix.isEmpty()) {
                zipEntries = new ArrayList<>(rawZipEntries.size());
                for (final ZipEntry zipEntry : rawZipEntries) {
                    if (zipEntry.getName().startsWith(mainPackageRootPrefix)) {
                        zipEntries.add(zipEntry);
                    }
                }
            } else {
                zipEntries = new ArrayList<>(rawZipEntries);
            }

            // Scan through non-directory zipfile entries for classpath roots and lib jars
            String bootInfZipEntryPathPrefix = null;
            String webInfZipEntryPathPrefix = null;
            for (int i = 0; i < zipEntries.size(); i++) {
                final ZipEntry zipEntry = zipEntries.get(i);
                String zipEntryPath = zipEntry.getName();
                if (zipEntryPath.endsWith("/")) {
                    zipEntryPath = zipEntryPath.substring(0, zipEntryPath.length() - 1);
                }
                if (zipEntryPath.startsWith("/")) {
                    zipEntryPath = zipEntryPath.substring(1);
                }

                // Add common package roots to the classpath (for Spring-Boot and Spring WAR files)
                if (bootInfZipEntryPathPrefix == null && zipEntryPath.startsWith(springBootClassesPrefix)) {
                    if (log != null) {
                        log.log("Found Spring-Boot package root: " + springBootClassesPrefix);
                    }
                    // Only add once
                    bootInfZipEntryPathPrefix = springBootClassesPrefix;
                }
                if (webInfZipEntryPathPrefix == null && zipEntryPath.startsWith(webInfClassesPrefix)) {
                    if (log != null) {
                        log.log("Found WAR class root: " + webInfClassesPrefix);
                    }
                    // Only add once
                    webInfZipEntryPathPrefix = webInfClassesPrefix;
                }

                // Scan for jars in common lib dirs (e.g. Spring-Boot and Spring WAR lib directories)
                if ((zipEntryPath.startsWith(springBootLibPrefix) || zipEntryPath.startsWith(webInfLibPrefix)
                        || zipEntryPath.startsWith(webInfLibProvidedPrefix) || zipEntryPath.startsWith(libPrefix))
                        // Look for jarfiles within the above lib paths
                        && zipEntryPath.endsWith(".jar")) {
                    // Found a jarfile in a lib dir. This jar may not be on the classpath, e.g. if this is
                    // a Spring-Boot jar and the scanner is running outside the jar.
                    if (scanSpec.scanNestedJars) {
                        if (log != null) {
                            log.log("Found lib jar: " + zipEntryPath);
                        }
                        final String libJarPath = jarFileName + "!" + zipEntryPath;
                        // Add the nested lib jar to the classpath to be scanned. This will cause the jar to
                        // be extracted from the zipfile by one of the worker threads.
                        // Also record the lib jar in case we need to construct a custom URLClassLoader to load
                        // classes from the jar (the entire classpath of the jar needs to be reconstructed if so)
                        addClassPathEntryToScan(libJarPath);
                        addLibJarEntry(libJarPath);
                    } else {
                        if (log != null) {
                            log.log("Skipping lib jar because nested jar scanning is disabled: " + zipEntryPath);
                        }
                    }
                }
            }

            // Use BOOT-INF or WEB-INF as main package root, if present
            if (bootInfZipEntryPathPrefix != null && !bootInfZipEntryPathPrefix.isEmpty()) {
                // Scan "BOOT-INF/classes" as the main package root
                mainPackageRootPrefix = bootInfZipEntryPathPrefix;

                // Also scan "" so that Spring-Boot classloader classes can be found
                if (log != null) {
                    log.log("Adding \"\" as a package root, so that Spring Boot classes will be scanned");
                }
                addAdditionalPackageRootPrefix("");

                // If both BOOT-INF and WEB-INF are present, schedule WEB-INF for scanning as a separate cp entry
                if (webInfZipEntryPathPrefix != null) {
                    addClassPathEntryToScan(jarFileName + "!"
                            + (webInfClassesPrefix.endsWith("/")
                                    ? webInfClassesPrefix.substring(0, webInfClassesPrefix.length() - 1)
                                    : webInfClassesPrefix));
                }
            } else if (webInfZipEntryPathPrefix != null) {
                // Scan "BOOT-INF/classes" as the main package root
                mainPackageRootPrefix = webInfZipEntryPathPrefix;

                // Also scan "" so that WAR classloader classes can be found
                if (log != null) {
                    log.log("Adding \"\" as a package root, so that WAR root package classes will be scanned");
                }
                addAdditionalPackageRootPrefix("");
            }
        } catch (final IOException e) {
            zipEntries = Collections.emptyList();
            if (log != null) {
                log.log("Exception while opening jarfile " + jarFile, e);
            }
        }
    }
}
