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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Fast parser for jar manifest files. */
public class JarfileMetadataReader {
    /** The File for this jarfile. */
    private final File jarFile;

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
     * {@code FastClasspathScanner#addNestedLibJarsToClasspath(true)} is called before scanning.
     */
    public List<String> classPathEntriesToScan;

    /**
     * Any jarfile package roots, including anything after "!" in the path to this jar in the classpath, and/or
     * "BOOT-INF/classes" or "WEB-INF/classes", if any files with this directory prefix are contained in the jar. If
     * classes need to be loaded from this jar, and no classloader can be found to load them, then each of these
     * subdirectories will get unzipped and added to the classpath of a new URLClassLoader.
     */
    public Set<String> packageRootRelativePaths;

    /**
     * Any jarfiles found in "lib/", "BOOT-INF/lib", "WEB-INF/lib", or "WEB-INF/lib-provided". If classes need to be
     * loaded from this jar, and no classloader can be found to load them, then each of these subdirectories will
     * get unzipped and added to the classpath of a new URLClassLoader.
     */
    public List<String> libJarPaths;

    // -------------------------------------------------------------------------------------------------------------

    public static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";

    // -------------------------------------------------------------------------------------------------------------

    private final Object customClassLoaderLock = new Object();

    private ClassLoader customClassLoader;

    /**
     * Create a custom URLClassLoader for the package root paths and lib jar entries in this jar. Called when the
     * environment classloaders cannot load a class from this jar. This may result in the unzipping of a large
     * number of lib jarfiles and/or classfiles (contained within the package root). Returns null if there were no
     * valid package roots or lib jars found.
     */
    public ClassLoader getCustomClassLoader(final NestedJarHandler nestedJarHandler, final LogNode log) {
        synchronized (customClassLoaderLock) {
            if (customClassLoader != null) {
                return customClassLoader;
            }
            if (packageRootRelativePaths == null && libJarPaths == null) {
                return null;
            }

            // Add package root paths to URLs for classloader
            final List<URL> urls = new ArrayList<>();
            for (final String packageRootPath : packageRootRelativePaths) {
                try {
                    final File extractedClasspathJarfileOrDir = packageRootPath.isEmpty()
                            // For package root of "", just add the whole jarfile to the classpath
                            ? jarFile
                            // For "BOOT-INF/classes" etc., unzip the package root do a temp dir
                            : nestedJarHandler.unzipToTempDir(jarFile, packageRootPath, log);
                    // Add URL for jarfile or dir to the classpath
                    urls.add(extractedClasspathJarfileOrDir.toURI().toURL());
                } catch (final IOException e) {
                    if (log != null) {
                        log.log("Cannot unzip package root " + packageRootPath + " in jarfile " + jarFile + " : "
                                + e);
                    }
                }
            }

            if (libJarPaths != null) {
                // There are lib jars -- get the File object for the extracted jar (should have already happened
                // during scanning, in parallel), and add a URL for this File to the URLs for the classloader
                for (final String libJarPath : libJarPaths) {
                    File innermostJarFile = null;
                    try {
                        // Extract inner jar, if it hasn't been extracted before
                        innermostJarFile = nestedJarHandler.getInnermostNestedJar(libJarPath, log).getKey();
                    } catch (final Exception e) {
                        if (log != null) {
                            log.log("Cannot extract lib jar " + libJarPath + " : " + e);
                        }
                    }
                    if (innermostJarFile != null) {
                        try {
                            urls.add(innermostJarFile.toURI().toURL());
                        } catch (final MalformedURLException e) {
                            if (log != null) {
                                log.log("Cannot create URL from lib jar path " + innermostJarFile + " : " + e);
                            }
                        }
                    }
                }
            }
            if (!urls.isEmpty()) {
                if (log != null) {
                    final LogNode subLog = log
                            .log("Creating custom ClassLoader for jar " + jarFile + " with URLs:");
                    for (final URL url : urls) {
                        subLog.log(url.toString());
                    }
                }
                customClassLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]));
            }
            return customClassLoader;
        }
    }

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

    public void addPackageRootPath(final String packageRoot) {
        if (this.packageRootRelativePaths == null) {
            this.packageRootRelativePaths = new HashSet<>();
        }
        this.packageRootRelativePaths.add(packageRoot);
    }

    public void addLibJarEntry(final String libJarPath) {
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
    public JarfileMetadataReader(final File jarFile, final LogNode log) {
        this.jarFile = jarFile;
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            final int numEntries = zipFile.size();
            zipEntries = new ArrayList<>(numEntries);
            final List<String> zipEntryPaths = new ArrayList<>(numEntries);
            boolean hasBootInfClasses = false;
            boolean hasWebInfClasses = false;
            final String jarFileName = FastPathResolver.resolve(jarFile.getPath());
            String springBootLibPrefix = "BOOT-INF/lib";
            String springBootClassesPrefix = "BOOT-INF/classes";
            for (final Enumeration<? extends ZipEntry> iter = zipFile.entries(); iter.hasMoreElements();) {
                final ZipEntry zipEntry = iter.nextElement();
                if (!zipEntry.isDirectory()) {
                    // Store non-directory ZipEntries for later use, so that they don't have to be recreated every
                    // time the jar is read (this avoids duplicate work when multiple threads are scanning the same
                    // jar). We don't need the directory ZipEntries during scanning.
                    zipEntries.add(zipEntry);

                    // Get ZipEntry path
                    String zipEntryPath = zipEntry.getName();
                    if (zipEntryPath.endsWith("/")) {
                        zipEntryPath = zipEntryPath.substring(0, zipEntryPath.length() - 1);
                    }
                    if (zipEntryPath.startsWith("/")) {
                        zipEntryPath = zipEntryPath.substring(1);
                    }
                    zipEntryPaths.add(zipEntryPath);

                    if (zipEntryPath.equals(MANIFEST_PATH)) {
                        // Parse manifest file, if present
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

                            // Check if this is a JRE jar
                            this.isSystemJar = //
                                    manifest.indexOf("\nImplementation-Title: Java Runtime Environment") > 0
                                            || manifest.indexOf(
                                                    "\nSpecification-Title: Java Platform API Specification") > 0;

                            // Check for "Class-Path:" manifest line
                            final int classPathIdx = manifest.indexOf("\nClass-Path:");
                            if (classPathIdx >= 0) {
                                // Add Class-Path manifest entry value to classpath
                                addSpaceDelimitedClassPathToScan(/* zipFilePath = */ null,
                                        extractManifestField(manifest, classPathIdx + 12));
                            }

                            // Check for Spring-Boot manifest lines
                            final int springBootClassesIdx = manifest.indexOf("\nSpring-Boot-Classes:");
                            if (springBootClassesIdx >= 0) {
                                springBootClassesPrefix = extractManifestField(manifest, springBootClassesIdx + 21);
                                if (springBootClassesPrefix.startsWith("/")) {
                                    springBootClassesPrefix = springBootClassesPrefix.substring(1);
                                }
                                if (!springBootClassesPrefix.endsWith("/")) {
                                    springBootClassesPrefix += '/';
                                }
                                if (springBootClassesPrefix.equals("/")) {
                                    springBootClassesPrefix = "";
                                }
                            }
                            final int springBootLibIdx = manifest.indexOf("\nSpring-Boot-Lib:");
                            if (springBootLibIdx >= 0) {
                                springBootLibPrefix = extractManifestField(manifest, springBootLibIdx + 17);
                                if (springBootLibPrefix.startsWith("/")) {
                                    springBootLibPrefix = springBootLibPrefix.substring(1);
                                }
                                if (!springBootLibPrefix.endsWith("/")) {
                                    springBootLibPrefix += '/';
                                }
                                if (springBootLibPrefix.equals("/")) {
                                    springBootLibPrefix = "";
                                }
                            }
                        }
                    }
                }
            }

            // Scan through non-directory zipfile entries for classpath roots and lib jars
            for (int i = 0; i < zipEntryPaths.size(); i++) {
                final String zipEntryPath = zipEntryPaths.get(i);

                // Add common package roots to the classpath (for Spring-Boot and Spring WAR files)
                if (!hasBootInfClasses && zipEntryPath.startsWith(springBootClassesPrefix)) {
                    if (log != null) {
                        log.log("Found Spring-Boot package root: " + springBootClassesPrefix);
                    }
                    // Only add once
                    hasBootInfClasses = true;
                    final String springBootClasses = springBootClassesPrefix.endsWith("/")
                            ? springBootClassesPrefix.substring(0, springBootClassesPrefix.length() - 1)
                            : springBootClassesPrefix;
                    final String classPathEntryPath = jarFileName + "!" + springBootClasses;
                    addClassPathEntryToScan(classPathEntryPath);
                    addPackageRootPath(springBootClasses);
                }
                if (!hasWebInfClasses && zipEntryPath.startsWith("WEB-INF/classes/")) {
                    if (log != null) {
                        log.log("Found WAR class root: " + zipEntryPath);
                    }
                    // Only add once
                    hasWebInfClasses = true;
                    addClassPathEntryToScan(jarFileName + "!WEB-INF/classes");
                    addPackageRootPath("WEB-INF/classes");
                }

                // Scan for jars in common lib dirs (e.g. Spring-Boot and Spring WAR lib directories)
                if ((zipEntryPath.startsWith(springBootLibPrefix) || zipEntryPath.startsWith("WEB-INF/lib/")
                        || zipEntryPath.startsWith("WEB-INF/lib-provided/") || zipEntryPath.startsWith("lib/"))
                        // Look for jarfiles within the above lib paths
                        && zipEntryPath.endsWith(".jar")) {
                    // Found a jarfile in a lib dir. This jar may not be on the classpath, e.g. if this is
                    // a Spring-Boot jar and the scanner is running outside the jar.
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
                }
            }
        } catch (final IOException e) {
            zipEntries = Collections.emptyList();
            if (log != null) {
                log.log("Exception while opening jarfile " + jarFile, e);
            }
        }
    }
}
