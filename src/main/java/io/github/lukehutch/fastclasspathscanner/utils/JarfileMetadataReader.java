/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Fast parser for jar manifest files. */
public class JarfileMetadataReader {
    public ArrayList<String> manifestClassPathEntries;
    public boolean isSystemJar;

    /**
     * The ZipEntries for the zipfile. ZipEntries contain only static metadata, so can be reused between different
     * ZipFile instances, even though ZipFile imposes a synchronized lock around each instance.
     */
    public final List<ZipEntry> zipEntries;

    public static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";

    private void addClassPathEntry(final String classPathEntry) {
        if (this.manifestClassPathEntries == null) {
            this.manifestClassPathEntries = new ArrayList<>();
        }
        this.manifestClassPathEntries.add(classPathEntry);
    }

    private void addSpaceDelimitedClassPath(final String zipFilePath, final String classPath) {
        for (final String classpathEntry : classPath.split(" ")) {
            if (!classpathEntry.isEmpty()) {
                addClassPathEntry(zipFilePath == null ? classpathEntry
                        // For Spring-Boot, add zipfile name to beginning of classpath entry
                        : zipFilePath + "!" + classpathEntry);
            }
        }
    }

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

    /**
     * Fast parser for jar manifest files. Doesn't intern strings or create Attribute objects like the Manifest
     * class, so has lower overhead. Only extracts a few specific entries from the manifest file, if present.
     * Assumes there is only one of each entry present in the manifest.
     */
    public JarfileMetadataReader(final File jarFile, final LogNode log) {
        zipEntries = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            boolean hasBootInfClasses = false;
            boolean hasWebInfClasses = false;
            String nonStandardSpringBootLib = null;
            final String jarFileName = FastPathResolver.resolve(jarFile.getPath());
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

                    // Store non-directory ZipEntries for later use, so that they don't have to be recreated every
                    // time the jar is read (this avoids duplicate work when multiple threads are scanning the same
                    // jar). We don't need the directory ZipEntries during scanning.
                    zipEntries.add(zipEntry);

                    // Scan for jars in common lib dirs (e.g. Spring-Boot and Spring WAR lib directories)
                    if ((zipEntryPath.startsWith("BOOT-INF/lib/") || zipEntryPath.startsWith("WEB-INF/lib/")
                            || zipEntryPath.startsWith("WEB-INF/lib-provided/") || zipEntryPath.startsWith("lib/"))
                            // Look for jarfiles within the above lib dirs
                            && zipEntryPath.endsWith(".jar")) {
                        // Found a jarfile that should probably be added to the classpath. This is needed because
                        // if for example you manually add a Spring-Boot jar to your classpath, but your scanning
                        // code is not running within the Spring-Boot jar itself, then the classloader for that
                        // jar will not be available, so the lib directories will not be found.
                        addClassPathEntry(jarFileName + "!" + zipEntryPath);
                        if (log != null) {
                            log.log("Found lib jar: " + zipEntryPath);
                        }
                    }

                    // Add common package roots to the classpath (for Spring-Boot and Spring WAR files)
                    if (!hasBootInfClasses && zipEntryPath.startsWith("BOOT-INF/classes/")) {
                        hasBootInfClasses = true;
                        addClassPathEntry(jarFileName + "!BOOT-INF/classes");
                        if (log != null) {
                            log.log("Found Spring-Boot package root: " + zipEntryPath);
                        }
                    }
                    if (!hasWebInfClasses && zipEntryPath.startsWith("WEB-INF/classes/")) {
                        hasWebInfClasses = true;
                        addClassPathEntry(jarFileName + "!WEB-INF/classes");
                        if (log != null) {
                            log.log("Found WAR class root: " + zipEntryPath);
                        }
                    }

                    if (zipEntryPath.equals(MANIFEST_PATH)) {
                        // Found manifest file
                        try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
                            final ByteArrayOutputStream byteBuf = new ByteArrayOutputStream();
                            // Start with '\n' so every field (including the first line) has newline as a prefix
                            byteBuf.write('\n');
                            final byte[] data = new byte[16384];
                            for (int bytesRead; (bytesRead = inputStream.read(data, 0, data.length)) != -1;) {
                                byteBuf.write(data, 0, bytesRead);
                            }
                            byteBuf.flush();
                            final String manifest = byteBuf.toString("UTF-8");
                            this.isSystemJar = //
                                    manifest.indexOf("\nImplementation-Title: Java Runtime Environment") > 0
                                            || manifest.indexOf(
                                                    "\nSpecification-Title: Java Platform API Specification") > 0;
                            final int classPathIdx = manifest.indexOf("\nClass-Path:");
                            if (classPathIdx >= 0) {
                                // Add Class-Path manifest entry value to classpath
                                addSpaceDelimitedClassPath(/* zipFilePath = */ null,
                                        extractManifestField(manifest, classPathIdx + 12));
                            }

                            final int springBootClassesIdx = manifest.indexOf("\nSpring-Boot-Classes:");
                            if (springBootClassesIdx >= 0) {
                                String springBootClasses = extractManifestField(manifest,
                                        springBootClassesIdx + 21);
                                if (springBootClasses.endsWith("/")) {
                                    springBootClasses = springBootClasses.substring(0,
                                            springBootClasses.length() - 1);
                                }
                                if (springBootClasses.startsWith("/")) {
                                    springBootClasses = springBootClasses.substring(1);
                                }
                                if (!springBootClasses.equals("BOOT-INF/classes")) {
                                    // Non-standard Spring-Boot package root
                                    addClassPathEntry(springBootClasses);
                                    if (log != null) {
                                        log.log("Found Spring-Boot package root: " + springBootClasses);
                                    }
                                }
                            }
                            final int springBootLibIdx = manifest.indexOf("\nSpring-Boot-Lib:");
                            if (springBootLibIdx >= 0) {
                                String springBootLib = extractManifestField(manifest, springBootLibIdx + 17);
                                if (springBootLib.endsWith("/")) {
                                    springBootLib = springBootLib.substring(0, springBootLib.length() - 1);
                                }
                                if (springBootLib.startsWith("/")) {
                                    springBootLib = springBootLib.substring(1);
                                }
                                if (!springBootLib.equals("BOOT-INF/lib")) {
                                    // Non-standard Spring-Boot lib dir
                                    nonStandardSpringBootLib = springBootLib;
                                    if (log != null) {
                                        log.log("Found Spring-Boot lib dir: " + springBootLib);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (nonStandardSpringBootLib != null) {
                // Non-standard Spring-Boot lib dir -- add any ".jar" files found within this dir
                final String prefix = nonStandardSpringBootLib + "/";
                for (final ZipEntry zipEntry : zipEntries) {
                    // Get ZipEntry path
                    String zipEntryPath = zipEntry.getName();
                    if (zipEntryPath.endsWith("/")) {
                        zipEntryPath = zipEntryPath.substring(0, zipEntryPath.length() - 1);
                    }
                    if (zipEntryPath.startsWith("/")) {
                        zipEntryPath = zipEntryPath.substring(1);
                    }
                    if (zipEntryPath.startsWith(prefix) && zipEntryPath.endsWith(".jar")) {
                        addClassPathEntry(jarFileName + "!" + zipEntryPath);
                        if (log != null) {
                            log.log("Found lib jar: " + zipEntryPath);
                        }
                    }
                }
            }
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception while opening jarfile " + jarFile, e);
            }
        }
    }
}
