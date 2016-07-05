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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.utils.Log;

public class RecursiveScanner {
    /** The classpath finder. */
    private final ClasspathFinder classpathFinder;

    /** The scanspec (whitelisted and blacklisted packages, etc.). */
    private final ScanSpec scanSpec;

    /** A list of file path testers and match processor wrappers to use for file matching. */
    private final List<FilePathTesterAndMatchProcessorWrapper> filePathTestersAndMatchProcessorWrappers = //
            new ArrayList<>();

    /**
     * The set of absolute paths scanned (after symlink resolution), to prevent the same resource from being scanned
     * twice.
     */
    private final Set<String> previouslyScannedCanonicalPaths = new HashSet<>();

    /** The total number of regular directories scanned. */
    private final AtomicInteger numDirsScanned = new AtomicInteger();

    /** The total number of jarfile-internal directories scanned. */
    private final AtomicInteger numJarfileDirsScanned = new AtomicInteger();

    /** The total number of regular files scanned. */
    private final AtomicInteger numFilesScanned = new AtomicInteger();

    /** The total number of jarfile-internal files scanned. */
    private final AtomicInteger numJarfileFilesScanned = new AtomicInteger();

    /** The total number of jarfiles scanned. */
    private final AtomicInteger numJarfilesScanned = new AtomicInteger();

    /**
     * The latest last-modified timestamp of any file, directory or sub-directory in the classpath, in millis since
     * the Unix epoch. Does not consider timestamps inside zipfiles/jarfiles, but the timestamp of the zip/jarfile
     * itself is considered.
     */
    private long lastModified = 0;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursive classpath scanner. Pass in a specification of whitelisted packages/jars to scan and blacklisted
     * packages/jars not to scan, where blacklisted entries are prefixed with the '-' character.
     * 
     * Examples of values for scanSpecs:
     * 
     * ["com.x"] => scans the package "com.x" and its sub-packages in all directories and jars on the classpath.
     * 
     * ["com.x", "-com.x.y"] => scans "com.x" and all sub-packages except "com.x.y" in all directories and jars on
     * the classpath.
     * 
     * ["com.x", "-com.x.y", "jar:deploy.jar"] => scans "com.x" and all sub-packages except "com.x.y", but only
     * looks in jars named "deploy.jar" on the classpath (i.e. whitelisting a "jar:" entry prevents non-jar entries
     * from being searched). Note that only the leafname of a jarfile can be specified.
     * 
     * ["com.x", "-jar:irrelevant.jar"] => scans "com.x" and all sub-packages in all directories and jars on the
     * classpath *except* "irrelevant.jar" (i.e. blacklisting a jarfile doesn't prevent directories from being
     * scanned the way that whitelisting a jarfile does).
     * 
     * ["com.x", "jar:"] => scans "com.x" and all sub-packages, but only looks in jarfiles on the classpath, doesn't
     * scan directories (i.e. all jars are whitelisted, and whitelisting jarfiles prevents non-jars (directories)
     * from being scanned).
     * 
     * ["com.x", "-jar:"] => scans "com.x" and all sub-packages, but only looks in directories on the classpath,
     * doesn't scan jarfiles (i.e. all jars are blacklisted.)
     */
    public RecursiveScanner(final ClasspathFinder classpathFinder, final ScanSpec scanSpec) {
        this.classpathFinder = classpathFinder;
        this.scanSpec = scanSpec;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** An interface used to test whether a file's relative path matches a given specification. */
    public static interface FilePathTester {
        public boolean filePathMatches(final File classpathElt, final String relativePathStr);
    }

    /** An interface called when the corresponding FilePathTester returns true. */
    public static interface FileMatchProcessorWrapper {
        public void processMatch(final File classpathElt, final String relativePathStr,
                final InputStream inputStream, final long fileSize) throws IOException;
    }

    private static class FilePathTesterAndMatchProcessorWrapper {
        FilePathTester filePathTester;
        FileMatchProcessorWrapper fileMatchProcessorWrapper;

        public FilePathTesterAndMatchProcessorWrapper(final FilePathTester filePathTester,
                final FileMatchProcessorWrapper fileMatchProcessorWrapper) {
            this.filePathTester = filePathTester;
            this.fileMatchProcessorWrapper = fileMatchProcessorWrapper;
        }
    }

    public void addFilePathMatcher(final FilePathTester filePathTester,
            final FileMatchProcessorWrapper fileMatchProcessorWrapper) {
        filePathTestersAndMatchProcessorWrappers
                .add(new FilePathTesterAndMatchProcessorWrapper(filePathTester, fileMatchProcessorWrapper));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return true if the real path for this path hasn't been seen before during this scan. (Getting the real path
     * resolves symlinks.)
     */
    private boolean previouslyScanned(final File classpathElt) {
        try {
            // Get canonical path (resolve symlinks, and make the path absolute)
            final String canonicalPath = classpathElt.getCanonicalPath();
            // See if this canonical path has been scanned before
            final boolean previouslyScanned = !previouslyScannedCanonicalPaths.add(canonicalPath);
            if (previouslyScanned && FastClasspathScanner.verbose) {
                Log.log(3, "Reached duplicate classpath element, ignoring: " + classpathElt);
            }
            return previouslyScanned;
        } catch (final IOException | SecurityException e) {
            // If something goes wrong while getting the real path, just return true.
            return true;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Scan a file. */
    private void scanFile(final File classpathElt, final String relativePath, final File file,
            final boolean scanTimestampsOnly) {
        if (previouslyScanned(file)) {
            if (FastClasspathScanner.verbose) {
                Log.log(3, "Reached duplicate file, ignoring: " + file);
            }
            return;
        }
        if (FastClasspathScanner.verbose) {
            Log.log(3, "Scanning file: " + file);
        }
        numFilesScanned.incrementAndGet();

        if (!scanTimestampsOnly) {
            final long startTime = System.nanoTime();
            // Match file paths against path patterns
            boolean filePathMatches = false;
            for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : // 
            filePathTestersAndMatchProcessorWrappers) {
                if (fileMatcher.filePathTester.filePathMatches(classpathElt, relativePath)) {
                    if (FastClasspathScanner.verbose) {
                        Log.log(4, "Calling MatchProcessor for file " + file);
                    }
                    try (InputStream inputStream = new FileInputStream(file)) {
                        fileMatcher.fileMatchProcessorWrapper.processMatch(classpathElt, relativePath, inputStream,
                                file.length());
                    } catch (final Exception e) {
                        if (FastClasspathScanner.verbose) {
                            Log.log(5, "Exception while processing classpath element " + classpathElt + ", file "
                                    + relativePath + " : " + e.getMessage());
                        }
                    }
                    filePathMatches = true;
                }
            }
            if (FastClasspathScanner.verbose && filePathMatches) {
                Log.log(4, "Scanned file " + file, System.nanoTime() - startTime);
            }
        }
    }

    /**
     * Scan a zipfile for matching file path patterns.
     */
    private void scanZipfile(final File classpathElt, final boolean scanTimestampsOnly) {
        if (previouslyScanned(classpathElt)) {
            if (FastClasspathScanner.verbose) {
                Log.log(3, "Reached duplicate jarfile, ignoring: " + classpathElt);
            }
            return;
        }
        if (scanSpec.jarIsWhitelisted(classpathElt.getName())) {
            if (FastClasspathScanner.verbose) {
                Log.log(3, "Scanning jarfile: " + classpathElt);
            }
            final long startTime = System.nanoTime();
            try (ZipFile zipfile = new ZipFile(classpathElt)) {
                for (final Enumeration<? extends ZipEntry> entries = zipfile.entries(); entries
                        .hasMoreElements();) {
                    final long entryStartTime = System.nanoTime();
                    final ZipEntry entry = entries.nextElement();
                    String relativePath = entry.getName();
                    if (relativePath.startsWith("/")) {
                        // Shouldn't happen with the standard Java zipfile implementation (but just to be safe)
                        relativePath = relativePath.substring(1);
                    }
                    // Ignore directory entries
                    if (entry.isDirectory()) {
                        if (scanSpec.pathIsWhitelisted(relativePath)) {
                            numJarfileDirsScanned.incrementAndGet();
                            if (FastClasspathScanner.verbose) {
                                numJarfileFilesScanned.incrementAndGet();
                                Log.log(4, "Scanned jarfile-internal directory " + relativePath,
                                        System.nanoTime() - entryStartTime);
                            }
                        } else if (FastClasspathScanner.verbose) {
                            Log.log(4, "Skipping non-whitelisted jarfile-internal directory " + relativePath);
                        }

                        continue;
                    }
                    // If whitelisted
                    if (scanSpec.pathIsWhitelisted(relativePath)) {
                        if (!scanTimestampsOnly) {
                            // Match file paths against path patterns
                            boolean filePathMatches = false;
                            for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : // 
                            filePathTestersAndMatchProcessorWrappers) {
                                if (fileMatcher.filePathTester.filePathMatches(classpathElt, relativePath)) {
                                    if (FastClasspathScanner.verbose) {
                                        Log.log(4, "Calling MatchProcessor for file " + classpathElt + "!"
                                                + relativePath);
                                    }
                                    try (InputStream inputStream = zipfile.getInputStream(entry)) {
                                        fileMatcher.fileMatchProcessorWrapper.processMatch(classpathElt,
                                                relativePath, inputStream, classpathElt.length());
                                    } catch (final Exception e) {
                                        if (FastClasspathScanner.verbose) {
                                            Log.log(5,
                                                    "Exception while processing classpath element " + classpathElt
                                                            + ", file " + relativePath + " : " + e.getMessage());
                                        }
                                    }
                                    filePathMatches = true;
                                }
                            }
                            if (FastClasspathScanner.verbose && filePathMatches) {
                                numJarfileFilesScanned.incrementAndGet();
                                Log.log(5, "Scanned jarfile-internal file " + relativePath,
                                        System.nanoTime() - entryStartTime);
                            }
                        } else if (FastClasspathScanner.verbose) {
                            Log.log(4, "Skipping non-whitelisted jarfile-internal file " + relativePath);
                        }

                    }
                }
            } catch (final IOException e) {
                if (FastClasspathScanner.verbose) {
                    Log.log(3, "Error while opening zipfile " + classpathElt + " : " + e.toString());
                }
            }
            if (FastClasspathScanner.verbose) {
                Log.log(4, "Scanned jarfile " + classpathElt, System.nanoTime() - startTime);
            }
        } else {
            if (FastClasspathScanner.verbose) {
                Log.log(3, "Skipping jarfile that did not match whitelist/blacklist criteria: "
                        + classpathElt.getName());
            }
        }
    }

    /**
     * Scan a directory for matching file path patterns.
     */
    private void scanDir(final File classpathElt, final File dir, final int ignorePrefixLen,
            boolean inWhitelistedPath, final boolean scanTimestampsOnly) {
        final String dirPath = dir.getPath();
        final String relativePath = ignorePrefixLen > dirPath.length() ? "/" //
                : dirPath.substring(ignorePrefixLen).replace(File.separatorChar, '/') + "/";
        if (previouslyScanned(dir)) {
            if (FastClasspathScanner.verbose) {
                Log.log(3, "Reached duplicate directory, ignoring: " + dir);
            }
            return;
        }
        if (FastClasspathScanner.verbose) {
            Log.log(3, "Scanning directory: " + dir);
        }
        numDirsScanned.incrementAndGet();

        boolean keepRecursing = false;
        boolean atWhitelistedClassPackage = false;
        switch (scanSpec.pathWhitelistMatchStatus(relativePath)) {
        case WITHIN_BLACKLISTED_PATH:
            // Reached a blacklisted path -- stop scanning files and dirs
            if (FastClasspathScanner.verbose) {
                Log.log(3, "Reached blacklisted path: " + relativePath);
            }
            return;
        case WITHIN_WHITELISTED_PATH:
            // Reached a whitelisted path -- can start scanning directories and files from this point
            if (FastClasspathScanner.verbose) {
                Log.log(3, "Reached whitelisted path: " + relativePath);
            }
            inWhitelistedPath = true;
            break;
        case ANCESTOR_OF_WHITELISTED_PATH:
            // In a path that is a prefix of a whitelisted path -- keep recursively scanning dirs
            // in case we can reach a whitelisted path.
            keepRecursing = true;
            break;
        case AT_WHITELISTED_CLASS_PACKAGE:
            // Reached a package that is itself not whitelisted, but contains a specifically-whitelisted class.
            atWhitelistedClassPackage = true;
            break;
        case NOT_WITHIN_WHITELISTED_PATH:
            break;
        default:
            throw new RuntimeException("Unknown match status");
        }
        if (keepRecursing || inWhitelistedPath || atWhitelistedClassPackage) {
            final long startTime = System.nanoTime();
            lastModified = Math.max(lastModified, dir.lastModified());
            final File[] subFiles = dir.listFiles();
            if (subFiles != null) {
                for (final File subFile : subFiles) {
                    File subFileCanonical = null;
                    try {
                        // N.B. we need NOFOLLOW_LINKS, because otherwise resolved paths may no longer appear
                        // as a child of a classpath element, and/or the path tree may no longer conform to
                        // the package tree. 
                        subFileCanonical = subFile.getCanonicalFile();
                    } catch (IOException | SecurityException e) {
                        if (FastClasspathScanner.verbose) {
                            Log.log(4, "Could not access file " + subFile + ": " + e.getMessage());
                        }
                    }
                    if (subFileCanonical != null) {
                        if (subFileCanonical.isDirectory()) {
                            if (inWhitelistedPath || keepRecursing) {
                                // Recurse into subdirectory
                                scanDir(classpathElt, subFileCanonical, ignorePrefixLen, inWhitelistedPath,
                                        scanTimestampsOnly);
                            }
                        } else if (subFileCanonical.isFile()) {
                            final String subFileName = subFileCanonical.getName();
                            // If in whitelisted path, or in the same non-whitelisted package as a whitelisted class
                            boolean fileIsWhitelisted = false;
                            if (inWhitelistedPath) {
                                fileIsWhitelisted = true;
                            } else if (atWhitelistedClassPackage && subFileName.endsWith(".class")) {
                                // Look for specifically-whitelisted classes in non-whitelisted packages
                                final String className = (relativePath
                                        + subFileName.substring(0, subFileName.length() - 6)).replace('/', '.');
                                fileIsWhitelisted = scanSpec.classIsWhitelisted(className);
                            }
                            if (fileIsWhitelisted) {
                                // Scan whitelisted file
                                scanFile(classpathElt,
                                        "/".equals(relativePath) ? subFileName : relativePath + subFileName,
                                        subFileCanonical, scanTimestampsOnly);
                            }
                        }
                    }
                }
            }
            if (FastClasspathScanner.verbose) {
                Log.log(4, "Scanned directory " + dir + " and any subdirectories", System.nanoTime() - startTime);
            }
        }
    }

    /**
     * If scanTimestampsOnly is true, scans the classpath for matching files, and calls any match processors if a
     * match is identified. If scanTimestampsOnly is false, only scans timestamps of files.
     */
    private synchronized void scan(final boolean scanTimestampsOnly) {
        final List<File> uniqueClasspathElts = classpathFinder.getUniqueClasspathElements();
        if (FastClasspathScanner.verbose) {
            Log.log(1, "Starting scan" + (scanTimestampsOnly ? " (scanning classpath timestamps only)" : ""));
        }
        final Map<String, String> env = new HashMap<>();
        env.put("create", "false");

        previouslyScannedCanonicalPaths.clear();
        numDirsScanned.set(0);
        numFilesScanned.set(0);
        numJarfileDirsScanned.set(0);
        numJarfileFilesScanned.set(0);
        numJarfilesScanned.set(0);

        // Iterate through path elements and recursively scan within each directory and zipfile
        for (final File classpathElt : uniqueClasspathElts) {
            final String path = classpathElt.getPath();
            final boolean isDirectory = classpathElt.isDirectory();
            final boolean isFile = classpathElt.isFile();
            if (!isDirectory && !isFile) {
                if (FastClasspathScanner.verbose) {
                    Log.log(2, "Skipping non-file/non-dir on classpath: " + classpathElt);
                }
            } else {
                final boolean isJar = isFile && ClasspathFinder.isJar(path);
                if (FastClasspathScanner.verbose) {
                    Log.log(2, "Found " + (isDirectory ? "directory" : isJar ? "jar" : "file") + " on classpath: "
                            + path);
                }
                if (isDirectory && scanSpec.scanNonJars) {
                    // Scan within directory
                    numDirsScanned.incrementAndGet();
                    scanDir(classpathElt, classpathElt, /* ignorePrefixLen = */ path.length() + 1,
                            /* inWhitelistedPath = */ false, scanTimestampsOnly);

                } else if (isJar && scanSpec.scanJars) {
                    // For jar/zipfile, use the timestamp of the jar/zipfile as the timestamp for all files,
                    // since the timestamps within the zip directory may be unreliable.
                    if (!scanTimestampsOnly) {
                        // Scan within jar/zipfile
                        final long startTime = System.nanoTime();
                        // Scan within jar/zipfile
                        scanZipfile(classpathElt, scanTimestampsOnly);
                        if (FastClasspathScanner.verbose) {
                            Log.log(2, "Scanned jarfile " + classpathElt, System.nanoTime() - startTime);
                        }
                    }
                    updateLastModifiedTimestamp(classpathElt.lastModified());
                    numJarfilesScanned.incrementAndGet();

                } else if (!isJar && scanSpec.scanNonJars) {
                    // File listed directly on classpath
                    if (previouslyScanned(classpathElt)) {
                        if (FastClasspathScanner.verbose) {
                            Log.log(3, "Reached duplicate file, ignoring: " + classpathElt);
                        }
                        continue;
                    }
                    final String parentStr = classpathElt.getParent();
                    if (parentStr != null) {
                        scanFile(classpathElt, classpathElt.getName(), classpathElt, scanTimestampsOnly);
                        updateLastModifiedTimestamp(classpathElt.lastModified());
                        numFilesScanned.incrementAndGet();
                    }

                } else {
                    if (FastClasspathScanner.verbose) {
                        Log.log(2, "Skipping classpath element due to scan spec restriction: " + path);
                    }
                }
            }
        }
        if (FastClasspathScanner.verbose) {
            Log.log(1,
                    "Number of resources scanned: directories: " + numDirsScanned.get() + "; files: "
                            + numFilesScanned.get() + "; jarfiles: " + numJarfilesScanned.get()
                            + "; jarfile-internal directories: " + numJarfileDirsScanned
                            + "; jarfile-internal files: " + numJarfileFilesScanned);
        }
    }

    /** Scans the classpath for matching files, and calls any FileMatchProcessors if a match is identified. */
    public void scan() {
        scan(/* scanTimestampsOnly = */false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Update the last modified timestamp, given the timestamp of a Path. */
    private void updateLastModifiedTimestamp(final long fileLastModified) {
        // Find max last modified timestamp, but don't accept values greater than the current time
        lastModified = Math.max(lastModified, Math.min(System.currentTimeMillis(), fileLastModified));
    }

    /**
     * Returns true if the classpath contents have been changed since scan() was last called. Only considers
     * classpath prefixes whitelisted in the call to the constructor. Returns true if scan() has not yet been run.
     * Much faster than standard classpath scanning, because only timestamps are checked, and jarfiles don't have to
     * be opened.
     */
    public boolean classpathContentsModifiedSinceScan() {
        final long oldLastModified = this.lastModified;
        if (oldLastModified == 0) {
            return true;
        } else {
            scan(/* scanTimestampsOnly = */true);
            final long newLastModified = this.lastModified;
            return newLastModified > oldLastModified;
        }
    }

    /**
     * Returns the maximum "last modified" timestamp in the classpath (in epoch millis), or zero if scan() has not
     * yet been called (or if nothing was found on the classpath).
     * 
     * The returned timestamp should be less than the current system time if the timestamps of files on the
     * classpath and the system time are accurate. Therefore, if anything changes on the classpath, this value
     * should increase.
     */
    public long classpathContentsLastModifiedTime() {
        return this.lastModified;
    }
}
